package com.charles.meshtalk.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.charles.meshtalk.app.data.MessageEntity
import com.charles.meshtalk.app.repository.MeshRepository
import com.charles.meshtalk.app.ui.theme.SignalGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PublicFeedScreen(repository: MeshRepository) {
    val messages by repository.publicFeed.collectAsState(initial = emptyList())
    val peerCount by repository.connectedPeerCount.collectAsState()
    var draft by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("PUBLIC") },
            actions = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
                    Icon(Icons.Filled.Sensors, contentDescription = null, tint = SignalGreen)
                    Text(" $peerCount PEERS", color = SignalGreen, fontFamily = FontFamily.Monospace)
                }
            }
        )
        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(messages, key = { it.id }) { message -> PublicMessageRow(message) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AttachButtons(
                onImagePicked = { bytes, mime -> repository.sendPublicImage(bytes, mime) },
                onFilePicked = { bytes, mime, filename -> repository.sendPublicFile(bytes, mime, filename) }
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Broadcast...") }
            )
            Button(
                onClick = {
                    if (draft.isNotBlank()) {
                        repository.sendPublicMessage(draft.trim())
                        draft = ""
                    }
                },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Send") }
        }
    }
}

@Composable
private fun PublicMessageRow(message: MessageEntity) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                message.senderNickname,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = SignalGreen
            )
            Text(
                relativeTime(message.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        MessageContentBody(message)
    }
}

fun relativeTime(timestampMs: Long): String {
    val diffSeconds = (System.currentTimeMillis() - timestampMs) / 1000
    return when {
        diffSeconds < 60 -> "${diffSeconds}s ago"
        diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
        diffSeconds < 86400 -> "${diffSeconds / 3600}h ago"
        else -> "${diffSeconds / 86400}d ago"
    }
}
