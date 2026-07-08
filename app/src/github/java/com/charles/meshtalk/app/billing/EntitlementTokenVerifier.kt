package com.charles.meshtalk.app.billing

import android.util.Base64
import com.charles.meshtalk.app.BuildConfig
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject

/** Decoded, signature-verified contents of an entitlement token minted by the Cloudflare Worker. */
data class EntitlementPayload(
    val deviceId: String,
    val entitlement: String, // "lifetime" | "subscription"
    val subscriptionStatus: String?,
    val iatEpochSeconds: Long,
    val expEpochSeconds: Long
)

/**
 * Verifies the Ed25519-signed, JWT-like token minted by the Worker's `GET /entitlement` endpoint
 * (see `cloudflare-worker/src/index.js`, `mintEntitlementToken`). Verification is entirely local —
 * the embedded public key (safe to ship in the app; only the Worker holds the private key) means
 * a cached token can be re-validated fully offline, which matters for this app's offline-first
 * nature. Uses the same low-level BouncyCastle Ed25519 API as [com.charles.meshtalk.app.crypto.Identity]
 * for consistency, rather than a JVM-standard Ed25519 API that may not exist pre-API 33.
 */
object EntitlementTokenVerifier {
    private val publicKeyBytes: ByteArray by lazy {
        hexToBytes(BuildConfig.ENTITLEMENT_VERIFY_PUBLIC_KEY_HEX)
    }

    /** Returns the verified payload, or null if the token is malformed or the signature is invalid.
     * Does not check `exp` — callers decide how to treat expiry (e.g. offline grace period). */
    fun verify(token: String): EntitlementPayload? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        val (headerB64, payloadB64, sigB64) = parts

        return try {
            val signingInput = "$headerB64.$payloadB64".toByteArray(Charsets.US_ASCII)
            val signature = base64UrlDecode(sigB64)

            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKeyBytes, 0))
            verifier.update(signingInput, 0, signingInput.size)
            if (!verifier.verifySignature(signature)) return null

            val payload = JSONObject(String(base64UrlDecode(payloadB64), Charsets.UTF_8))
            EntitlementPayload(
                deviceId = payload.getString("deviceId"),
                entitlement = payload.getString("entitlement"),
                subscriptionStatus = if (payload.isNull("subscriptionStatus")) null else payload.optString("subscriptionStatus"),
                iatEpochSeconds = payload.getLong("iat"),
                expEpochSeconds = payload.getLong("exp")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun base64UrlDecode(s: String): ByteArray =
        Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte() }
}
