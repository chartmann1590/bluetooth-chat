package com.charles.meshtalk.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
fun NearbyScreen(repository: MeshRepository, onMessagePeer: (String) -> Unit) {
    val contacts by repository.contacts.collectAsState(initial = emptyList())

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Nearby") })
        if (contacts.isEmpty()) {
            Text(
                "No peers seen yet. Bring another MeshTalk phone within Bluetooth range.",
                modifier = Modifier.padding(24.dp)
            )
        } else {
            LazyColumn {
                items(contacts, key = { it.signingPubKeyHex }) { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onMessagePeer(contact.signingPubKeyHex) }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(contact.nickname, fontFamily = FontFamily.Monospace)
                        Text(
                            relativeTime(contact.lastSeen),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
