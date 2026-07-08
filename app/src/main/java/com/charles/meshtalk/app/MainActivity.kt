package com.charles.meshtalk.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.charles.meshtalk.app.ai.GemmaEngineManager
import com.charles.meshtalk.app.crypto.Identity
import com.charles.meshtalk.app.repository.MeshRepository
import com.charles.meshtalk.app.ui.MeshTalkApp
import com.charles.meshtalk.app.ui.OnboardingScreen
import com.charles.meshtalk.app.ui.theme.MeshTalkTheme
import kotlinx.coroutines.launch

const val EXTRA_OPEN_DM_PEER_KEY = "open_dm_peer_key"
/** Value is [com.charles.meshtalk.app.notifications.VoiceNotifier.TARGET_PUBLIC] or a peer's
 * signing-pubkey hex — set when a voice-message notification is tapped. */
const val EXTRA_OPEN_WALKIE_TALKIE_TARGET = "open_walkie_talkie_target"
private const val PREFS_NAME = "meshtalk_prefs"
private const val PREF_BATTERY_PROMPT_SEEN = "battery_prompt_seen"
private const val PREF_AI_PROMPT_SEEN = "ai_download_prompt_seen"

fun requiredBluetoothPermissions(): Array<String> {
    val perms = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        perms += Manifest.permission.BLUETOOTH_SCAN
        perms += Manifest.permission.BLUETOOTH_ADVERTISE
        perms += Manifest.permission.BLUETOOTH_CONNECT
    } else {
        perms += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms += Manifest.permission.POST_NOTIFICATIONS
    }
    return perms.toTypedArray()
}

fun hasAllPermissions(context: Context, perms: Array<String>): Boolean =
    perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java)
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

class MainActivity : ComponentActivity() {
    private val pendingDmPeerKeyState: MutableState<String?> = mutableStateOf(null)
    private val pendingWalkieTalkieTargetState: MutableState<String?> = mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseHelper.get(applicationContext).init()
        val repository = MeshRepository.get(applicationContext)
        pendingDmPeerKeyState.value = intent?.getStringExtra(EXTRA_OPEN_DM_PEER_KEY)
        pendingWalkieTalkieTargetState.value = intent?.getStringExtra(EXTRA_OPEN_WALKIE_TALKIE_TARGET)
        handleBillingDeepLink(intent)

        setContent {
            val requiredPerms = remember { requiredBluetoothPermissions() }
            var permissionsGranted by remember { mutableStateOf(hasAllPermissions(applicationContext, requiredPerms)) }
            var onboarded by remember { mutableStateOf(Identity.exists(applicationContext)) }
            val prefs = remember { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
            var showBatteryPrompt by remember {
                mutableStateOf(!isIgnoringBatteryOptimizations(applicationContext) && !prefs.getBoolean(PREF_BATTERY_PROMPT_SEEN, false))
            }
            var showAiPrompt by remember {
                mutableStateOf(!prefs.getBoolean(PREF_AI_PROMPT_SEEN, false))
            }
            val batteryLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) {
                prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SEEN, true).apply()
                showBatteryPrompt = false
            }
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results -> permissionsGranted = results.values.all { it } }

            MeshTalkTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!permissionsGranted) {
                        PermissionGate(onRequest = { launcher.launch(requiredPerms) })
                    } else if (!onboarded) {
                        OnboardingScreen(onNicknameChosen = { nickname ->
                            repository.start(nickname)
                            onboarded = true
                        })
                    } else if (showAiPrompt) {
                        AiDownloadPromptScreen(
                            onDownload = {
                                GemmaEngineManager.downloadInBackground(applicationContext)
                                prefs.edit().putBoolean(PREF_AI_PROMPT_SEEN, true).apply()
                                showAiPrompt = false
                            },
                            onSkip = {
                                prefs.edit().putBoolean(PREF_AI_PROMPT_SEEN, true).apply()
                                showAiPrompt = false
                            }
                        )
                    } else if (showBatteryPrompt) {
                        BatteryOptimizationScreen(
                            onEnable = {
                                val intent = Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:$packageName")
                                )
                                batteryLauncher.launch(intent)
                            },
                            onSkip = {
                                prefs.edit().putBoolean(PREF_BATTERY_PROMPT_SEEN, true).apply()
                                showBatteryPrompt = false
                            }
                        )
                    } else {
                        LaunchedEffect(Unit) { repository.start("") }
                        val pendingDmPeerKey by pendingDmPeerKeyState
                        val pendingWalkieTalkieTarget by pendingWalkieTalkieTargetState
                        MeshTalkApp(
                            repository, pendingDmPeerKey, pendingWalkieTalkieTarget,
                            onDeepLinkConsumed = { pendingDmPeerKeyState.value = null },
                            onWalkieTalkieDeepLinkConsumed = { pendingWalkieTalkieTargetState.value = null }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_DM_PEER_KEY)?.let { peerKey ->
            pendingDmPeerKeyState.value = peerKey
        }
        intent.getStringExtra(EXTRA_OPEN_WALKIE_TALKIE_TARGET)?.let { target ->
            pendingWalkieTalkieTargetState.value = target
        }
        handleBillingDeepLink(intent)
    }

    /** Fires on the `meshtalk://billing/success` redirect after Stripe Checkout completes
     * (github/Stripe flavor only — the intent-filter registering this scheme only exists in that
     * flavor's manifest overlay, so this is unreachable on the play flavor). */
    private fun handleBillingDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "meshtalk" || uri.host != "billing") return
        val billing = com.charles.meshtalk.app.billing.BillingRepositoryProvider.create(applicationContext)
        lifecycleScope.launch { billing.onExternalCheckoutReturned() }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.isForeground = true
    }

    override fun onStop() {
        AppVisibility.isForeground = false
        super.onStop()
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("MeshTalk needs Bluetooth (and nearby-device) permission to talk to other phones directly over BLE.")
        androidx.compose.foundation.layout.Spacer(Modifier.padding(8.dp))
        Button(onClick = onRequest) { Text("Grant permissions") }
    }
}

@Composable
private fun AiDownloadPromptScreen(onDownload: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Chat with an on-device AI, offline",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            "MeshTalk can run Google's Gemma 4 model entirely on this phone using LiteRT-LM — " +
                "no internet needed once it's downloaded, and nothing you type into it ever goes " +
                "over the mesh or the internet. It's a one-time ~2.6GB download over Wi-Fi and " +
                "works best on devices with 8GB+ RAM; you can always download it later from the " +
                "AI tab instead.",
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
        )
        Button(onClick = onDownload) { Text("Download now (2.6GB)") }
        OutlinedButton(onClick = onSkip, modifier = Modifier.padding(top = 8.dp)) { Text("Not now") }
    }
}

@Composable
private fun BatteryOptimizationScreen(onEnable: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Keep the mesh running in the background",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            "Android aggressively limits background Bluetooth scanning to save battery. " +
                "Exempting MeshTalk from battery optimization keeps you reachable for messages " +
                "even when the app isn't open.",
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp)
        )
        Button(onClick = onEnable) { Text("Enable background mesh") }
        OutlinedButton(onClick = onSkip, modifier = Modifier.padding(top = 8.dp)) { Text("Skip for now") }
    }
}
