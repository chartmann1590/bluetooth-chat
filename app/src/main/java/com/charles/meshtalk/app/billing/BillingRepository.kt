package com.charles.meshtalk.app.billing

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/** Whether the walkie-talkie feature (the only paid part of the app) is unlocked right now. */
sealed class EntitlementState {
    /** Not yet checked — treat as locked, but don't show "not entitled" messaging until resolved. */
    object Unknown : EntitlementState()
    object NotEntitled : EntitlementState()
    data class TrialActive(val trialEndsAtEpochMs: Long) : EntitlementState()
    data class Subscribed(val renewsOrExpiresAtEpochMs: Long) : EntitlementState()
    object LifetimePurchased : EntitlementState()
    /** Github flavor only: entitlement token has expired but we're within the offline grace
     * window and cannot currently reach the Worker to re-verify. */
    data class GracePeriod(val until: Long, val underlying: EntitlementState) : EntitlementState()
}

fun EntitlementState.isUnlocked(): Boolean = when (this) {
    EntitlementState.Unknown, EntitlementState.NotEntitled -> false
    is EntitlementState.TrialActive, is EntitlementState.Subscribed,
    EntitlementState.LifetimePurchased, is EntitlementState.GracePeriod -> true
}

/**
 * Shared abstraction over the two flavors' billing implementations — `play` (Google Play Billing,
 * `src/play/.../billing/`) and `github` (Cloudflare Worker + Stripe Checkout, `src/github/.../billing/`).
 * Only one implementation is ever on the classpath for a given build, resolved via
 * [BillingRepositoryProvider] in each flavor's own source set (this codebase has no DI framework
 * to hook into instead, so this mirrors how Gradle flavors conventionally resolve "same interface,
 * different impl per flavor").
 */
interface BillingRepository {
    val entitlementState: StateFlow<EntitlementState>

    /** Re-checks entitlement against the flavor's source of truth (Play's purchase cache, or the
     * Worker's `/entitlement` endpoint) — called on app foreground and after a purchase completes. */
    suspend fun refreshEntitlement()

    suspend fun startOneTimePurchase(activity: Activity)
    suspend fun startSubscriptionPurchase(activity: Activity)

    /** Github/Stripe flavor only: called when the `meshtalk://billing/success` deep link fires
     * after returning from Stripe Checkout, to poll the Worker for the now-completed purchase.
     * No-op default since the play flavor's Play Billing purchase flow completes in-process via
     * its own PurchasesUpdatedListener and never needs an external deep-link callback. */
    suspend fun onExternalCheckoutReturned() {}

    fun isFeatureUnlocked(): Boolean = entitlementState.value.isUnlocked()
}
