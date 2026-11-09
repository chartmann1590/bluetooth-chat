package com.charles.meshtalk.app.billing

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.util.Log
import android.util.Patterns
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.charles.meshtalk.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Talks to the Cloudflare Worker (`cloudflare-worker/`) which fronts Stripe — this flavor cannot
 * use Play Billing at all, and Stripe's Checkout Session creation + webhook verification must
 * happen server-side (no secret keys can live in the app). Entitlement is proven by a cached,
 * locally-verifiable Ed25519 token (see [EntitlementTokenVerifier]), so the unlock check itself
 * works fully offline once a token has been fetched at least once — matching this app's
 * offline-first nature. `deviceId` is a random UUID generated on first use.
 *
 * 2026-11 (Hartmann Studios shared billing): new purchases go through the shared Stripe billing
 * service (the /v2 routes, app key `meshtalk`) and are tied to the buyer's email, so "Restore purchase"
 * brings them back after a reinstall or on a new phone (the server checks Stripe's records for that
 * email; an unknown email gets nothing). Purchases made earlier through the device-id Worker are
 * still honoured on the phone that made them.
 */
class StripeBillingRepository(private val appContext: Context) : BillingRepository {
    companion object {
        private const val PREFS = "meshtalk_billing_prefs"
        private const val PREF_DEVICE_ID = "device_id"
        private const val PREF_CACHED_TOKEN = "cached_token"
        private const val PREF_LAST_IAT = "last_iat"
        // Worst case a lapsed/cancelled subscriber stays unlocked ~10 days past their last real
        // check (7-day token lifetime + 3-day grace) — appropriate for an app that may go long
        // stretches without connectivity.
        private const val OFFLINE_GRACE_SECONDS = 3L * 24 * 60 * 60
        private const val CHECKOUT_POLL_ATTEMPTS = 10
        private const val CHECKOUT_POLL_DELAY_MS = 2_000L
        private const val TAG = "DirectBilling"
        private const val V2_BASE = "https://livecaptionn-premium.charles-h-hartmann1.workers.dev"
        private const val V2_APP = "meshtalk"
        private const val V2_LIFETIME = "walkietalkie_lifetime"
        private const val V2_MONTHLY = "walkietalkie_monthly"
        private const val PREF_EMAIL = "v2_email"
        private const val PREF_V2_ENTS = "v2_entitlements"
        private const val PREF_V2_EXP = "v2_license_exp"
        private const val PREF_V2_REVALIDATE = "v2_revalidate_after"
    }

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val deviceId: String by lazy {
        prefs.getString(PREF_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(PREF_DEVICE_ID, it).apply()
        }
    }

    private val _entitlementState = MutableStateFlow<EntitlementState>(EntitlementState.Unknown)
    override val entitlementState: StateFlow<EntitlementState> = _entitlementState.asStateFlow()
    @Volatile private var currentLegacy: EntitlementState? = null

    init {
        // Resolves from any cached token immediately so the UI has an answer before the first
        // network round-trip completes (or even if one never succeeds).
        applyCachedTokenIfValid()
    }

    override suspend fun refreshEntitlement() {
        // Shared-billing (email) purchases: re-checked at most once a day (the server allows a few
        // lookups per email per day); the signed license cached on the phone covers the rest.
        val email = prefs.getString(PREF_EMAIL, null)
        if (email != null && System.currentTimeMillis() / 1000 >= prefs.getLong(PREF_V2_REVALIDATE, 0)) {
            v2Fetch(JSONObject().put("app", V2_APP).put("email", email))
        }
        if (!fetchAndCacheToken()) {
            // Network unreachable, or nothing changed server-side — fall back to whatever the
            // cached token (plus its offline grace period) still supports, rather than immediately
            // declaring NotEntitled just because this one refresh attempt failed.
            applyCachedTokenIfValid()
        }
    }

    override suspend fun startOneTimePurchase(activity: Activity) = startV2Checkout(activity, V2_LIFETIME)
    override suspend fun startSubscriptionPurchase(activity: Activity) = startV2Checkout(activity, V2_MONTHLY)

    override val supportsRestore: Boolean get() = true

    /** "Restore purchase": asks for the email used at checkout and asks the server which MeshTalk
     * purchases that email owns (works after a reinstall or on another phone). */
    override suspend fun restorePurchases(activity: Activity) {
        val email = askEmail(activity, "Restore purchase", "Enter the email you used at checkout.") ?: return
        val ok = v2Fetch(JSONObject().put("app", V2_APP).put("email", email))
        val owned = v2Entitlements().isNotEmpty()
        withContext(Dispatchers.Main) {
            Toast.makeText(activity,
                if (!ok) "Couldn't reach the license server. Try again."
                else if (owned) "Purchases restored. Thank you!" else "No purchases found for $email.",
                Toast.LENGTH_LONG).show()
        }
    }

    override suspend fun onCheckoutReturned(sessionId: String?) {
        if (!sessionId.isNullOrBlank()) {
            repeat(CHECKOUT_POLL_ATTEMPTS) {
                if (v2Fetch(JSONObject().put("app", V2_APP).put("sessionId", sessionId)) && v2Entitlements().isNotEmpty()) return
                delay(CHECKOUT_POLL_DELAY_MS)
            }
        }
        val email = prefs.getString(PREF_EMAIL, null)
        if (email != null) v2Fetch(JSONObject().put("app", V2_APP).put("email", email))
        onExternalCheckoutReturned()
    }

    private suspend fun startV2Checkout(activity: Activity, product: String) {
        val email = askEmail(activity, "Checkout with Stripe",
            "Your receipt and license are tied to this email. Use the same email to restore on another phone.") ?: return
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().put("app", V2_APP).put("product", product).put("email", email).toString()
                    .toRequestBody("application/json".toMediaType())
                val response = client.newCall(Request.Builder().url("$V2_BASE/v2/checkout").post(body).build()).execute()
                val json = JSONObject(response.body?.string().orEmpty())
                val checkoutUrl = json.optString("checkoutUrl").takeIf { it.isNotBlank() }
                if (checkoutUrl == null) {
                    Log.w(TAG, "checkout failed: ${json.optString("error")}")
                    withContext(Dispatchers.Main) { Toast.makeText(activity, "Checkout is unavailable right now. Try again.", Toast.LENGTH_LONG).show() }
                    return@withContext
                }
                withContext(Dispatchers.Main) {
                    CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(checkoutUrl))
                }
            } catch (e: Exception) {
                Log.w(TAG, "checkout failed: $e")
            }
        }
    }

    /** Asks the shared billing service; true when it answered (entitled or not). */
    private suspend fun v2Fetch(req: JSONObject): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = client.newCall(Request.Builder().url("$V2_BASE/v2/entitlement")
                .post(req.toString().toRequestBody("application/json".toMediaType())).build()).execute()
            val json = JSONObject(response.body?.string().orEmpty())
            if (!json.has("entitlements")) { Log.w(TAG, "entitlement: ${json.optString("error")}"); return@withContext false }
            val ents = json.optJSONArray("entitlements") ?: JSONArray()
            val token = json.optString("licenseToken")
            prefs.edit()
                .putString(PREF_EMAIL, json.optString("email").ifBlank { prefs.getString(PREF_EMAIL, null) })
                .putString(PREF_V2_ENTS, ents.toString())
                .putLong(PREF_V2_EXP, tokenExp(token))
                .putLong(PREF_V2_REVALIDATE, json.optLong("revalidateAfter", 0))
                .apply()
            Log.i(TAG, "license saved app=$V2_APP entitlements=$ents")
            publish()
            true
        } catch (e: Exception) {
            Log.w(TAG, "entitlement failed: $e"); false
        }
    }

    private fun v2Entitlements(): Set<String> {
        val exp = prefs.getLong(PREF_V2_EXP, 0)
        if (exp > 0 && System.currentTimeMillis() / 1000 > exp + OFFLINE_GRACE_SECONDS) return emptySet()
        val raw = prefs.getString(PREF_V2_ENTS, null) ?: return emptySet()
        return try { val a = JSONArray(raw); (0 until a.length()).map { a.getString(it) }.toSet() } catch (e: Exception) { emptySet() }
    }

    private fun v2State(): EntitlementState? {
        val e = v2Entitlements()
        return when {
            V2_LIFETIME in e -> EntitlementState.LifetimePurchased
            V2_MONTHLY in e -> EntitlementState.Subscribed(renewsOrExpiresAtEpochMs = prefs.getLong(PREF_V2_REVALIDATE, 0) * 1000)
            else -> null
        }
    }

    /** Combines the shared-billing license with the legacy device-id token. */
    private fun publish(legacy: EntitlementState? = null) {
        val lg = legacy ?: currentLegacy
        _entitlementState.value = v2State() ?: lg ?: EntitlementState.NotEntitled
    }
    private fun setLegacy(st: EntitlementState) {
        currentLegacy = if (st.isUnlocked()) st else null
        publish(currentLegacy)
    }

    private fun tokenExp(token: String): Long = try {
        val body = token.substringBefore('.')
        JSONObject(String(android.util.Base64.decode(body, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))).optLong("exp", 0)
    } catch (e: Exception) { 0 }

    private suspend fun askEmail(activity: Activity, title: String, msg: String): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val input = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                hint = "you@example.com"
                setText(prefs.getString(PREF_EMAIL, null).orEmpty())
            }
            val box = LinearLayout(activity).apply {
                setPadding(48, 16, 48, 0)
                addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            var done = false
            val dlg = AlertDialog.Builder(activity).setTitle(title).setMessage(msg).setView(box)
                .setPositiveButton("Continue") { _, _ ->
                    val e = input.text.toString().trim().lowercase()
                    done = true
                    if (Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
                        prefs.edit().putString(PREF_EMAIL, e).apply(); if (cont.isActive) cont.resume(e)
                    } else {
                        Toast.makeText(activity, "Please enter a valid email.", Toast.LENGTH_LONG).show(); if (cont.isActive) cont.resume(null)
                    }
                }
                .setNegativeButton("Cancel") { _, _ -> done = true; if (cont.isActive) cont.resume(null) }
                .setOnDismissListener { if (!done && cont.isActive) cont.resume(null) }
                .create()
            dlg.show()
            cont.invokeOnCancellation { dlg.dismiss() }
        }
    }

    /** Called by MainActivity when the `meshtalk://billing/success` deep link fires. Stripe's
     * webhook can land slightly after the redirect, so this polls briefly rather than assuming
     * the entitlement is immediately visible. */
    override suspend fun onExternalCheckoutReturned() {
        repeat(CHECKOUT_POLL_ATTEMPTS) {
            if (fetchAndCacheToken()) return
            delay(CHECKOUT_POLL_DELAY_MS)
        }
    }

    private suspend fun startCheckout(activity: Activity, mode: String) = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().put("deviceId", deviceId).put("mode", mode).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${BuildConfig.CLOUDFLARE_WORKER_BASE_URL}/create-checkout-session")
                .post(requestBody)
                .build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string().orEmpty())
            val checkoutUrl = json.optString("checkoutUrl").takeIf { it.isNotBlank() } ?: return@withContext
            withContext(Dispatchers.Main) {
                CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(checkoutUrl))
            }
        } catch (e: Exception) {
            // No connectivity or the Worker is unreachable — nothing to launch; the paywall stays up.
        }
    }

    /** Returns true once a definitive answer (entitled or not) was fetched from the Worker —
     * false only on network/parse failure, so the caller knows to fall back to the cached token. */
    private suspend fun fetchAndCacheToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${BuildConfig.CLOUDFLARE_WORKER_BASE_URL}/entitlement?deviceId=$deviceId")
                .get()
                .build()
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body?.string().orEmpty())
            if (!json.optBoolean("entitled", false)) {
                setLegacy(EntitlementState.NotEntitled)
                return@withContext true
            }
            val token = json.getString("token")
            val payload = EntitlementTokenVerifier.verify(token) ?: return@withContext false

            // Reject a token with an iat older than one already seen locally — a lightweight
            // mitigation against replaying a stale token (e.g. after a device clock rollback), not
            // a full defense; see the walkie-talkie feature plan for why this tradeoff is accepted.
            val lastIat = prefs.getLong(PREF_LAST_IAT, 0)
            if (payload.iatEpochSeconds < lastIat) return@withContext false

            prefs.edit()
                .putString(PREF_CACHED_TOKEN, token)
                .putLong(PREF_LAST_IAT, payload.iatEpochSeconds)
                .apply()
            setLegacy(stateFor(payload))
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun applyCachedTokenIfValid() {
        val token = prefs.getString(PREF_CACHED_TOKEN, null)
        if (token == null) {
            setLegacy(EntitlementState.NotEntitled)
            return
        }
        val payload = EntitlementTokenVerifier.verify(token)
        if (payload == null) {
            setLegacy(EntitlementState.NotEntitled)
            return
        }
        val nowSeconds = System.currentTimeMillis() / 1000
        setLegacy(when {
            nowSeconds <= payload.expEpochSeconds -> stateFor(payload)
            nowSeconds <= payload.expEpochSeconds + OFFLINE_GRACE_SECONDS ->
                EntitlementState.GracePeriod(
                    until = (payload.expEpochSeconds + OFFLINE_GRACE_SECONDS) * 1000,
                    underlying = stateFor(payload)
                )
            else -> EntitlementState.NotEntitled
        })
    }

    private fun stateFor(payload: EntitlementPayload): EntitlementState = when (payload.entitlement) {
        "lifetime" -> EntitlementState.LifetimePurchased
        "subscription" -> EntitlementState.Subscribed(renewsOrExpiresAtEpochMs = payload.expEpochSeconds * 1000)
        else -> EntitlementState.NotEntitled
    }
}
