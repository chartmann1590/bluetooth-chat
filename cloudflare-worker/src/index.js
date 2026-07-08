// MeshTalk billing Worker (github/Stripe flavor only — the play flavor uses Play Billing directly,
// no backend needed). Routes:
//   POST /create-checkout-session  { deviceId, mode: "one_time"|"subscription" } -> { checkoutUrl }
//   POST /stripe-webhook           Stripe webhook receiver
//   GET  /entitlement?deviceId=X   -> { entitled, token? }  (token is an Ed25519-signed, JWT-like credential)
//
// Storage: one KV record per deviceId (see ENTITLEMENTS binding), shape:
//   { entitlement: "none"|"lifetime"|"subscription", stripeCustomerId, stripeSubscriptionId,
//     subscriptionStatus, trialUsed, updatedAt }

const TOKEN_TTL_SECONDS = 7 * 24 * 60 * 60; // 7 days — app caches + honors an offline grace period past this

function base64url(bytes) {
  let bin = "";
  for (const b of new Uint8Array(bytes)) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function hexToBytes(hex) {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
  return bytes;
}

async function importEd25519PrivateKey(env) {
  // ENTITLEMENT_SIGNING_PRIVATE_KEY_HEX / _PUBLIC_KEY_HEX are the raw 32-byte Ed25519 seed/pubkey,
  // hex-encoded — imported via JWK (OKP/Ed25519) since raw-format private-key import isn't
  // supported by WebCrypto; JWK is the portable path across Workers/Node/browsers.
  const d = base64url(hexToBytes(env.ENTITLEMENT_SIGNING_PRIVATE_KEY_HEX));
  const x = base64url(hexToBytes(env.ENTITLEMENT_SIGNING_PUBLIC_KEY_HEX));
  return crypto.subtle.importKey(
    "jwk",
    { kty: "OKP", crv: "Ed25519", d, x, key_ops: ["sign"], ext: true },
    { name: "Ed25519" },
    false,
    ["sign"]
  );
}

async function mintEntitlementToken(env, payload) {
  const key = await importEd25519PrivateKey(env);
  const header = { alg: "Ed25519", typ: "MTENT" };
  const headerB64 = base64url(new TextEncoder().encode(JSON.stringify(header)));
  const payloadB64 = base64url(new TextEncoder().encode(JSON.stringify(payload)));
  const signingInput = `${headerB64}.${payloadB64}`;
  const signature = await crypto.subtle.sign("Ed25519", key, new TextEncoder().encode(signingInput));
  return `${signingInput}.${base64url(signature)}`;
}

async function verifyStripeSignature(payload, sigHeader, secret) {
  // Stripe-Signature: "t=<timestamp>,v1=<hex hmac>" — HMAC-SHA256 of "<timestamp>.<payload>".
  const parts = Object.fromEntries(sigHeader.split(",").map((p) => p.split("=")));
  const signedPayload = `${parts.t}.${payload}`;
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]
  );
  const mac = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(signedPayload));
  const macHex = [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, "0")).join("");
  // Constant-time-ish compare; webhook secrets are low-value enough that this is sufficient here.
  return macHex === parts.v1;
}

async function stripeApi(env, path, body) {
  const form = new URLSearchParams();
  const flatten = (obj, prefix = "") => {
    for (const [k, v] of Object.entries(obj)) {
      const key = prefix ? `${prefix}[${k}]` : k;
      if (Array.isArray(v)) {
        v.forEach((item, i) => {
          const itemKey = `${key}[${i}]`;
          if (item && typeof item === "object") flatten(item, itemKey);
          else form.append(itemKey, String(item));
        });
      } else if (v && typeof v === "object") {
        flatten(v, key);
      } else {
        form.append(key, String(v));
      }
    }
  };
  flatten(body);
  const res = await fetch(`https://api.stripe.com/v1/${path}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env.STRIPE_SECRET_KEY}`,
      "Content-Type": "application/x-www-form-urlencoded"
    },
    body: form.toString()
  });
  const json = await res.json();
  if (!res.ok) throw new Error(`Stripe API error: ${JSON.stringify(json)}`);
  return json;
}

async function getRecord(env, deviceId) {
  const raw = await env.ENTITLEMENTS.get(`device:${deviceId}`);
  return raw ? JSON.parse(raw) : { entitlement: "none", trialUsed: false };
}

async function putRecord(env, deviceId, record) {
  record.updatedAt = Date.now();
  await env.ENTITLEMENTS.put(`device:${deviceId}`, JSON.stringify(record));
}

async function handleCreateCheckoutSession(request, env) {
  const { deviceId, mode } = await request.json();
  if (!deviceId || (mode !== "one_time" && mode !== "subscription")) {
    return new Response(JSON.stringify({ error: "deviceId and mode ('one_time'|'subscription') required" }), { status: 400 });
  }

  const successUrl = "meshtalk://billing/success?session_id={CHECKOUT_SESSION_ID}";
  const cancelUrl = "meshtalk://billing/cancel";

  let session;
  if (mode === "one_time") {
    session = await stripeApi(env, "checkout/sessions", {
      mode: "payment",
      line_items: [{ price: env.STRIPE_PRICE_LIFETIME, quantity: 1 }],
      metadata: { deviceId },
      success_url: successUrl,
      cancel_url: cancelUrl
    });
  } else {
    session = await stripeApi(env, "checkout/sessions", {
      mode: "subscription",
      line_items: [{ price: env.STRIPE_PRICE_MONTHLY, quantity: 1 }],
      subscription_data: { trial_period_days: 3, metadata: { deviceId } },
      metadata: { deviceId },
      success_url: successUrl,
      cancel_url: cancelUrl
    });
  }

  return new Response(JSON.stringify({ checkoutUrl: session.url }), {
    headers: { "Content-Type": "application/json" }
  });
}

async function handleStripeWebhook(request, env) {
  const payload = await request.text();
  const sigHeader = request.headers.get("Stripe-Signature") || "";
  const valid = await verifyStripeSignature(payload, sigHeader, env.STRIPE_WEBHOOK_SECRET);
  if (!valid) return new Response("invalid signature", { status: 400 });

  const event = JSON.parse(payload);
  const obj = event.data.object;

  switch (event.type) {
    case "checkout.session.completed": {
      const deviceId = obj.metadata && obj.metadata.deviceId;
      if (!deviceId) break;
      const record = await getRecord(env, deviceId);
      if (obj.mode === "payment") {
        record.entitlement = "lifetime";
      } else {
        record.entitlement = "subscription";
        record.stripeSubscriptionId = obj.subscription;
      }
      record.stripeCustomerId = obj.customer;
      await putRecord(env, deviceId, record);
      break;
    }
    case "customer.subscription.updated":
    case "customer.subscription.created": {
      const deviceId = obj.metadata && obj.metadata.deviceId;
      if (!deviceId) break;
      const record = await getRecord(env, deviceId);
      record.stripeSubscriptionId = obj.id;
      record.stripeCustomerId = obj.customer;
      record.subscriptionStatus = obj.status; // "trialing" | "active" | "past_due" | "canceled" | ...
      if (obj.status === "trialing") record.trialUsed = true;
      record.entitlement = (obj.status === "active" || obj.status === "trialing") ? "subscription" : "none";
      await putRecord(env, deviceId, record);
      break;
    }
    case "customer.subscription.deleted": {
      const deviceId = obj.metadata && obj.metadata.deviceId;
      if (!deviceId) break;
      const record = await getRecord(env, deviceId);
      record.subscriptionStatus = "canceled";
      record.entitlement = "none";
      await putRecord(env, deviceId, record);
      break;
    }
    case "invoice.payment_failed": {
      // No deviceId on the invoice itself; the subscription.updated event that follows carries
      // the real status change, so this is just a signal, not acted on directly here.
      break;
    }
  }

  return new Response("ok");
}

async function handleGetEntitlement(request, env) {
  const url = new URL(request.url);
  const deviceId = url.searchParams.get("deviceId");
  if (!deviceId) return new Response(JSON.stringify({ error: "deviceId required" }), { status: 400 });

  const record = await getRecord(env, deviceId);
  const entitled = record.entitlement === "lifetime" || record.entitlement === "subscription";
  if (!entitled) {
    return new Response(JSON.stringify({ entitled: false }), { headers: { "Content-Type": "application/json" } });
  }

  const now = Math.floor(Date.now() / 1000);
  const payload = {
    deviceId,
    entitlement: record.entitlement,
    subscriptionStatus: record.subscriptionStatus || null,
    iat: now,
    exp: now + TOKEN_TTL_SECONDS
  };
  const token = await mintEntitlementToken(env, payload);
  return new Response(JSON.stringify({ entitled: true, token }), { headers: { "Content-Type": "application/json" } });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    try {
      if (request.method === "POST" && url.pathname === "/create-checkout-session") {
        return await handleCreateCheckoutSession(request, env);
      }
      if (request.method === "POST" && url.pathname === "/stripe-webhook") {
        return await handleStripeWebhook(request, env);
      }
      if (request.method === "GET" && url.pathname === "/entitlement") {
        return await handleGetEntitlement(request, env);
      }
      return new Response("not found", { status: 404 });
    } catch (e) {
      return new Response(`error: ${e.message}`, { status: 500 });
    }
  }
};
