package com.charles.meshtalk.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

fun hasAllPermissions(context: android.content.Context, perms: Array<String>): Boolean =
    perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = MeshRepository.get(applicationContext)

        setContent {
            val requiredPerms = remember { requiredBluetoothPermissions() }
            var permissionsGranted by remember { mutableStateOf(hasAllPermissions(applicationContext, requiredPerms)) }
            var onboarded by remember { mutableStateOf(Identity.exists(applicationContext)) }
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
                    } else {
                        androidx.compose.runtime.LaunchedEffect(Unit) { repository.start("") }
                        MeshTalkApp(repository)
                    }
                }
            }
        }
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
