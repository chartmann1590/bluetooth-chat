package com.charles.meshtalk.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.charles.meshtalk.app.AppVisibility
import com.charles.meshtalk.app.data.MessageEntity
import com.charles.meshtalk.app.repository.MeshRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmThreadScreen(repository: MeshRepository, peerKeyHex: String) {
    val messages by repository.dmThread(peerKeyHex).collectAsState(initial = emptyList())
    val contacts by repository.contacts.collectAsState(initial = emptyList())
    val dmTypers by repository.dmTypers.collectAsState()
    val nickname = contacts.firstOrNull { it.signingPubKeyHex == peerKeyHex }?.nickname ?: peerKeyHex.take(10)
    var draft by remember { mutableStateOf("") }
    var sendFailed by remember { mutableStateOf(false) }
    var lastTypingSentAt by remember { mutableStateOf(0L) }
    val peerIsTyping = dmTypers.containsKey(peerKeyHex)

    // While this thread is on screen, suppress notifications for DMs from this peer.
    DisposableEffect(peerKeyHex) {
        AppVisibility.openDmPeerKeyHex = peerKeyHex
        onDispose { AppVisibility.openDmPeerKeyHex = null }
    }

    LaunchedEffect(messages) {
        messages.filter { !it.isMine }.forEach { repository.markAsRead(it) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(nickname, fontFamily = FontFamily.Monospace) })
        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            items(messages, key = { it.id }) { message -> DmMessageRow(repository, message) }
        }
        if (peerIsTyping) {
            Text(
                "$nickname is typing…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (sendFailed) {
            Text(
                "Can't send yet: haven't seen this peer on the mesh recently.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AttachButtons(
                onImagePicked = { bytes, mime ->
                    sendFailed = !repository.sendDirectImage(peerKeyHex, bytes, mime)
                },
                onFilePicked = { bytes, mime, filename ->
                    sendFailed = !repository.sendDirectFile(peerKeyHex, bytes, mime, filename)
                },
                onLocationPicked = { lat, lng, mapBytes, mapMime ->
                    sendFailed = !repository.sendDirectLocation(peerKeyHex, lat, lng, mapBytes, mapMime)
                }
            )
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    val now = System.currentTimeMillis()
                    if (it.isNotBlank() && now - lastTypingSentAt > 3000) {
                        lastTypingSentAt = now
                        repository.sendTypingIndicator(peerKeyHex)
                    }
                },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message...") }
            )
            Button(
                onClick = {
                    if (draft.isNotBlank()) {
                        val ok = repository.sendDirectMessage(peerKeyHex, draft.trim())
                        sendFailed = !ok
                        if (ok) draft = ""
                    }
                },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text("Send") }
        }
    }
}

@Composable
private fun DmMessageRow(repository: MeshRepository, message: MessageEntity) {
    // No per-message sender label — like a real SMS thread, side alone (right = you, left = them)
    // conveys who sent it, since it's always exactly one other person here.
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            MessageContentWithActions(repository, message)
            Row(
                modifier = Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    relativeTime(message.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ReadReceiptIndicator(repository, message)
            }
        }
    }
}
