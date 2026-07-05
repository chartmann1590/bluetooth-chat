package com.charles.meshtalk.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.charles.meshtalk.app.repository.MeshRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: MeshRepository) {
    val nickname by repository.myNickname.collectAsState()
    val pubKeyHex by repository.myPublicKeyHex.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Nickname", style = MaterialTheme.typography.labelMedium)
            Text(nickname ?: "…", modifier = Modifier.padding(bottom = 16.dp))
            Text("Identity public key", style = MaterialTheme.typography.labelMedium)
            Text(
                pubKeyHex ?: "…",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
