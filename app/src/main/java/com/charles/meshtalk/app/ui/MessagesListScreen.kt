package com.charles.meshtalk.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
fun MessagesListScreen(repository: MeshRepository, onOpenThread: (String) -> Unit) {
    val peerKeys by repository.dmPeerKeys.collectAsState(initial = emptyList())
    val contacts by repository.contacts.collectAsState(initial = emptyList())
    val nicknameByKey = contacts.associate { it.signingPubKeyHex to it.nickname }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Messages") })
        if (peerKeys.isEmpty()) {
            Text(
                "No direct messages yet. Start one from the Nearby tab.",
                modifier = Modifier.padding(24.dp)
            )
        } else {
            LazyColumn {
                items(peerKeys) { peerKey ->
                    val nickname = nicknameByKey[peerKey] ?: peerKey.take(10)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenThread(peerKey) }
                            .padding(16.dp)
                    ) {
                        Text(nickname, fontFamily = FontFamily.Monospace)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
