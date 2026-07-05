package com.charles.meshtalk.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.charles.meshtalk.app.repository.MeshRepository
import com.charles.meshtalk.app.ui.theme.SignalGreen

/**
 * Live BLE-signal-strength "warmer/colder" proximity finder — useful when GPS/internet aren't
 * available. Distance can't be measured precisely from RSSI (it's affected by walls, orientation,
 * interference), so this deliberately shows a coarse strength gauge and label, not a fake distance.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindScreen(repository: MeshRepository, peerKeyHex: String) {
    val contacts by repository.contacts.collectAsState(initial = emptyList())
    val rssiMap by repository.peerRssi.collectAsState()
    val nickname = contacts.firstOrNull { it.signingPubKeyHex == peerKeyHex }?.nickname ?: peerKeyHex.take(10)
    val rssi = rssiMap[peerKeyHex]

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Find $nickname", fontFamily = FontFamily.Monospace) })
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (rssi == null) {
                Text(
                    "Not detected nearby right now",
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Text(
                    "Ask them to turn on their Tracking beacon in Settings for more frequent updates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
                val (label, filledBars) = proximityFor(rssi)
                Text(label, style = MaterialTheme.typography.headlineSmall, color = SignalGreen, fontWeight = FontWeight.Bold)
                Text(
                    "$rssi dBm",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
                )
                SignalBars(filledBars = filledBars)
                Text(
                    "Signal strength is a rough guide, not exact distance — it's affected by walls, " +
                        "orientation, and interference. Move around and watch it change.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

private fun proximityFor(rssi: Int): Pair<String, Int> = when {
    rssi >= -55 -> "Very close" to 5
    rssi >= -65 -> "Close" to 4
    rssi >= -75 -> "Nearby" to 3
    rssi >= -85 -> "Far" to 2
    else -> "Very far" to 1
}

@Composable
private fun SignalBars(filledBars: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        for (i in 1..5) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .width(28.dp)
                    .height((16 + i * 14).dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (i <= filledBars) SignalGreen else MaterialTheme.colorScheme.surfaceVariant)
            )
        }
    }
}
