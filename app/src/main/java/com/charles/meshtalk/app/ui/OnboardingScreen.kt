package com.charles.meshtalk.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.charles.meshtalk.app.ui.theme.SignalGreen

@Composable
fun OnboardingScreen(onNicknameChosen: (String) -> Unit) {
    var nickname by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "MeshTalk",
            style = MaterialTheme.typography.headlineMedium,
            color = SignalGreen,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Chat over Bluetooth, no internet needed.",
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it },
            label = { Text("Choose a nickname") },
            singleLine = true,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Button(
            onClick = { if (nickname.isNotBlank()) onNicknameChosen(nickname.trim()) }
        ) {
            Text("Get Started")
        }
        Text(
            "A secure identity keypair is generated automatically on-device.",
            modifier = Modifier.padding(top = 24.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
