package com.charles.meshtalk.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.charles.meshtalk.app.billing.BillingRepository
import com.charles.meshtalk.app.billing.findActivity
import kotlinx.coroutines.launch

/** Shown by [com.charles.meshtalk.app.billing.EntitlementGate] in place of the walkie-talkie
 * screen when the feature isn't unlocked yet. */
@Composable
fun PaywallScreen(billing: BillingRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Walkie-Talkie", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Push-to-talk voice messages over the same offline Bluetooth mesh as chat — " +
                "no internet needed, ever. Unlock with a one-time purchase or a monthly " +
                "subscription (3-day free trial included).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = {
                val activity = context.findActivity() ?: return@Button
                scope.launch { billing.startOneTimePurchase(activity) }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Buy once, own it forever")
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = {
                val activity = context.findActivity() ?: return@OutlinedButton
                scope.launch { billing.startSubscriptionPurchase(activity) }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start monthly subscription (3-day free trial)")
        }
    }
}
