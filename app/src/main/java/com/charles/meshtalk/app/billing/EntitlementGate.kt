package com.charles.meshtalk.app.billing

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.charles.meshtalk.app.ui.PaywallScreen

/** Shows [content] (the walkie-talkie screen) once entitled, or [PaywallScreen] otherwise. */
@Composable
fun EntitlementGate(billing: BillingRepository, content: @Composable () -> Unit) {
    val state by billing.entitlementState.collectAsState()
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
