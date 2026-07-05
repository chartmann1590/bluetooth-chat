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
import com.charles.meshtalk.app.crypto.Identity
import com.charles.meshtalk.app.repository.MeshRepository
import com.charles.meshtalk.app.ui.MeshTalkApp
import com.charles.meshtalk.app.ui.OnboardingScreen
import com.charles.meshtalk.app.ui.theme.MeshTalkTheme

const val EXTRA_OPEN_DM_PEER_KEY = "open_dm_peer_key"
private const val PREFS_NAME = "meshtalk_prefs"
private const val PREF_BATTERY_PROMPT_SEEN = "battery_prompt_seen"

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = MeshRepository.get(applicationContext)
        pendingDmPeerKeyState.value = intent?.getStringExtra(EXTRA_OPEN_DM_PEER_KEY)

        setContent {
            val requiredPerms = remember { requiredBluetoothPermissions() }
            var permissionsGranted by remember { mutableStateOf(hasAllPermissions(applicationContext, requiredPerms)) }
            var onboarded by remember { mutableStateOf(Identity.exists(applicationContext)) }
            val prefs = remember { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
            var showBatteryPrompt by remember {
                mutableStateOf(!isIgnoringBatteryOptimizations(applicationContext) && !prefs.getBoolean(PREF_BATTERY_PROMPT_SEEN, false))
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
                        MeshTalkApp(
                            repository, pendingDmPeerKey,
                            onDeepLinkConsumed = { pendingDmPeerKeyState.value = null }
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
