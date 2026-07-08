package com.charles.meshtalk.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.charles.meshtalk.app.ble.AudioCodecId
import com.charles.meshtalk.app.data.MessageEntity
import com.charles.meshtalk.app.media.AudioCodec
import com.charles.meshtalk.app.media.AudioPlayer
import com.charles.meshtalk.app.media.AudioRecorder
import com.charles.meshtalk.app.repository.MeshRepository

/** null target = public broadcast; a target's [MessageEntity] rows are its VOICE-typed clips. */
private sealed class VoiceTarget {
    object Public : VoiceTarget()
    data class Direct(val peerKeyHex: String, val nickname: String) : VoiceTarget()
}

/** [initialPeerKeyHex] preselects that contact's tab (e.g. when opened from a voice-message
 * notification) once their nickname is known from [MeshRepository.contacts]; null selects Public. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkieTalkieScreen(repository: MeshRepository, initialPeerKeyHex: String? = null) {
    val context = LocalContext.current
    val contacts by repository.contacts.collectAsState(initial = emptyList())
    var target by remember { mutableStateOf<VoiceTarget>(VoiceTarget.Public) }

    LaunchedEffect(initialPeerKeyHex, contacts) {
        if (initialPeerKeyHex != null) {
            contacts.find { it.signingPubKeyHex == initialPeerKeyHex }?.let {
                target = VoiceTarget.Direct(it.signingPubKeyHex, it.nickname)
            }
        }
    }

    val publicFeed by repository.publicFeed.collectAsState(initial = emptyList())
    val dmThread by (target as? VoiceTarget.Direct)?.let { repository.dmThread(it.peerKeyHex) }
        ?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList()) }

    // Only the most recent transmission matters for a walkie-talkie, not a scrollback — for
    // Public that's the latest clip per sender (so with 3 senders you get 3 rows, one each);
    // for a DM thread it's a single row, whichever direction it went most recently.
    val voiceClips = if (target is VoiceTarget.Public) {
        publicFeed.asSequence()
            .filter { it.contentType == "VOICE" && !it.deleted }
            .groupBy { it.senderPubKeyHex }
            .map { (_, messages) -> messages.maxBy { it.timestamp } }
            .sortedByDescending { it.timestamp }
    } else {
        dmThread.asSequence()
            .filter { it.contentType == "VOICE" && !it.deleted }
            .maxByOrNull { it.timestamp }
            ?.let { listOf(it) }
            ?: emptyList()
    }

    var hasMicPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    val recorder = remember { AudioRecorder() }
    var isRecording by remember { mutableStateOf(false) }
    val amplitude by recorder.amplitude.collectAsState()
    val elapsedMs by recorder.elapsedMs.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Walkie-Talkie") })

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = target is VoiceTarget.Public,
                onClick = { target = VoiceTarget.Public },
                label = { Text("Public") }
            )
            contacts.forEach { contact ->
                FilterChip(
                    selected = (target as? VoiceTarget.Direct)?.peerKeyHex == contact.signingPubKeyHex,
                    onClick = { target = VoiceTarget.Direct(contact.signingPubKeyHex, contact.nickname) },
                    label = { Text(contact.nickname) }
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (voiceClips.isEmpty()) {
                Text(
                    "No voice messages yet. Hold the mic button below to record one.",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    items(voiceClips, key = { it.id }) { message ->
                        VoiceMessageRow(message)
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isRecording) {
                Text("Recording… ${elapsedMs / 1000}s / ${AudioRecorder.MAX_DURATION_MS / 1000}s")
                LinearProgressIndicator(
                    progress = { amplitude },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
            }
            Surface(
                shape = CircleShape,
                color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(80.dp)
                    .pointerInput(hasMicPermission, target) {
                        detectTapGestures(
                            onPress = {
                                if (!hasMicPermission) {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    return@detectTapGestures
                                }
                                isRecording = true
                                recorder.start { clip ->
                                    isRecording = false
                                    if (clip != null && clip.bytes.isNotEmpty()) {
                                        when (val t = target) {
                                            is VoiceTarget.Public -> repository.sendPublicVoiceMessage(
                                                clip.bytes, clip.codec.id, clip.codec.sampleRateHz, clip.durationMs
                                            )
                                            is VoiceTarget.Direct -> repository.sendDirectVoiceMessage(
                                                t.peerKeyHex, clip.bytes, clip.codec.id, clip.codec.sampleRateHz, clip.durationMs
                                            )
                                        }
                                    }
                                }
                                tryAwaitRelease()
                                recorder.stop()
                            }
                        )
                    },
                tonalElevation = 4.dp
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Filled.Mic, contentDescription = "Hold to record", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Text(
                "Hold to record, release to send",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun VoiceMessageRow(message: MessageEntity) {
    val player = remember(message.id) { AudioPlayer() }
    var isPlaying by remember { mutableStateOf(false) }
    val progress by player.progress.collectAsState()

    DisposableEffect(message.id) {
        onDispose { player.stop() }
    }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (isPlaying) {
                    player.stop()
                    isPlaying = false
                } else if (message.mediaBytes != null) {
                    val codecId = if (message.mediaCodec == AudioCodecId.AMR_NB.name) AudioCodecId.AMR_NB else AudioCodecId.OPUS
                    isPlaying = true
                    player.play(message.mediaBytes, AudioCodec.forId(codecId)) { isPlaying = false }
                }
            }) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play"
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(message.senderNickname, style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(
                    progress = { if (isPlaying) progress else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                val durationSec = (message.mediaDurationMs ?: 0) / 1000
                Text(
                    "${durationSec}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
