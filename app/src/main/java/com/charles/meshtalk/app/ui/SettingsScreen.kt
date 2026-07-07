package com.charles.meshtalk.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.charles.meshtalk.app.data.feedback.BugReportRepo
import com.charles.meshtalk.app.repository.MeshRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: MeshRepository) {
    val nickname by repository.myNickname.collectAsState()
    val pubKeyHex by repository.myPublicKeyHex.collectAsState()
    val receiveAttachments by repository.receiveAttachments.collectAsState()
    val trackingBeacon by repository.trackingBeaconEnabled.collectAsState()
    val findFeatureEnabled by repository.findFeatureEnabled.collectAsState()
    val voiceRelayEnabled by repository.voiceRelayEnabled.collectAsState()
    val context = LocalContext.current
    val bugReportRepo = remember { BugReportRepo(context) }
    var feedbackKey by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(title = { Text("Settings") })
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Nickname", style = MaterialTheme.typography.labelMedium)
            Text(nickname ?: "…", modifier = Modifier.padding(bottom = 16.dp))
            Text("Identity public key", style = MaterialTheme.typography.labelMedium)
            Text(
                pubKeyHex ?: "…",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Receive photos & files", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "When off, incoming attachments aren't stored on this device " +
                            "(you'll still see a text placeholder). Your own messages and " +
                            "relaying other peers' traffic aren't affected.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = receiveAttachments,
                    onCheckedChange = { repository.setReceiveAttachments(it) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Tracking beacon", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Advertise more frequently so nearby peers can find you with the Find " +
                            "screen (Nearby tab) when GPS/internet aren't available — uses more " +
                            "battery. Note: anyone scanning nearby can already see your signal " +
                            "strength regardless of this setting; it only controls how often you " +
                            "advertise.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = trackingBeacon,
                    onCheckedChange = { repository.setTrackingBeaconEnabled(it) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bluetooth radar (Find tab)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Adds a \"Find\" tab with a map and a live radar of everyone nearby, each " +
                            "shown with an arrow and an approximate distance based on Bluetooth " +
                            "signal strength — works without GPS or internet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = findFeatureEnabled,
                    onCheckedChange = { repository.setFindFeatureEnabled(it) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Relay voice through mesh", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Voice messages are delivered directly between you and the recipient by " +
                            "default — if you're not directly connected (or within Bluetooth " +
                            "range), the message won't arrive. Turning this on lets voice messages " +
                            "hop through other nearby MeshTalk devices to reach the recipient, like " +
                            "text messages do — but voice clips are much larger than text, so " +
                            "relaying them uses significantly more of everyone's Bluetooth " +
                            "bandwidth and battery, and delivery will be slower over multiple hops.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = voiceRelayEnabled,
                    onCheckedChange = { repository.setVoiceRelayEnabled(it) }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            key(feedbackKey) {
                SupportFeedbackSection(
                    repo = bugReportRepo,
                    onDataChanged = { feedbackKey++ }
                )
            }
        }
    }
}
