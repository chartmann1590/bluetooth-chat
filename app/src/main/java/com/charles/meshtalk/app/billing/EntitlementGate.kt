package com.charles.meshtalk.app.billing

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.charles.meshtalk.app.ui.PaywallScreen

/** Shows [content] (the walkie-talkie screen) once entitled, or [PaywallScreen] otherwise.
 *
 * Re-checks entitlement every time this gate is composed (e.g. each time the user opens the
 * Walkie-Talkie tab) rather than only trusting whatever was cached from the last check — for the
 * github/Stripe flavor in particular, nothing else ever calls [BillingRepository.refreshEntitlement]
 * outside of right after a checkout, so without this a cancelled subscription wouldn't be noticed
 * until its cached token's offline-grace window ran out (up to ~10 days). This call is still
 * best-effort/non-blocking: [BillingRepository.entitlementState] already reflects the cached token
 * immediately, and this only updates it if a live check completes. */
@Composable
fun EntitlementGate(billing: BillingRepository, content: @Composable () -> Unit) {
    val state by billing.entitlementState.collectAsState()
    LaunchedEffect(Unit) { billing.refreshEntitlement() }
    if (state.isUnlocked()) {
        content()
    } else {
        PaywallScreen(billing)
    }
}

/** Compose's `LocalContext` is often a wrapper (e.g. around a Compose-in-Fragment/View host), so
 * Activity-scoped APIs like launching a Play Billing flow or a Custom Tab need to unwrap it. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
