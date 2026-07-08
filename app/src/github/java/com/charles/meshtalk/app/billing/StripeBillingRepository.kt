package com.charles.meshtalk.app.billing

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.charles.meshtalk.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Talks to the Cloudflare Worker (`cloudflare-worker/`) which fronts Stripe — this flavor cannot
 * use Play Billing at all, and Stripe's Checkout Session creation + webhook verification must
 * happen server-side (no secret keys can live in the app). Entitlement is proven by a cached,
 * locally-verifiable Ed25519 token (see [EntitlementTokenVerifier]), so the unlock check itself
 * works fully offline once a token has been fetched at least once — matching this app's
 * offline-first nature. `deviceId` is a random UUID generated on first use, the only "account"
 * concept this flavor has (no login/email).
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

    init {
        // Resolves from any cached token immediately so the UI has an answer before the first
        // network round-trip completes (or even if one never succeeds).
        applyCachedTokenIfValid()
    }

    override suspend fun refreshEntitlement() {
        if (!fetchAndCacheToken()) {
            // Network unreachable, or nothing changed server-side — fall back to whatever the
            // cached token (plus its offline grace period) still supports, rather than immediately
            // declaring NotEntitled just because this one refresh attempt failed.
            applyCachedTokenIfValid()
        }
    }

    override suspend fun startOneTimePurchase(activity: Activity) = startCheckout(activity, "one_time")
    override suspend fun startSubscriptionPurchase(activity: Activity) = startCheckout(activity, "subscription")

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
                _entitlementState.value = EntitlementState.NotEntitled
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
            _entitlementState.value = stateFor(payload)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun applyCachedTokenIfValid() {
        val token = prefs.getString(PREF_CACHED_TOKEN, null)
        if (token == null) {
            _entitlementState.value = EntitlementState.NotEntitled
            return
        }
        val payload = EntitlementTokenVerifier.verify(token)
        if (payload == null) {
            _entitlementState.value = EntitlementState.NotEntitled
            return
        }
        val nowSeconds = System.currentTimeMillis() / 1000
        _entitlementState.value = when {
            nowSeconds <= payload.expEpochSeconds -> stateFor(payload)
            nowSeconds <= payload.expEpochSeconds + OFFLINE_GRACE_SECONDS ->
                EntitlementState.GracePeriod(
                    until = (payload.expEpochSeconds + OFFLINE_GRACE_SECONDS) * 1000,
                    underlying = stateFor(payload)
                )
            else -> EntitlementState.NotEntitled
        }
    }

    private fun stateFor(payload: EntitlementPayload): EntitlementState = when (payload.entitlement) {
        "lifetime" -> EntitlementState.LifetimePurchased
        "subscription" -> EntitlementState.Subscribed(renewsOrExpiresAtEpochMs = payload.expEpochSeconds * 1000)
        else -> EntitlementState.NotEntitled
    }
}
