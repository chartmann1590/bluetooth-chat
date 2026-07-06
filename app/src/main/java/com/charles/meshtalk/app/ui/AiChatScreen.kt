package com.charles.meshtalk.app.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.charles.meshtalk.app.ai.AiChatRepository
import com.charles.meshtalk.app.ai.GemmaEngineManager
import com.charles.meshtalk.app.ai.ModelState
import com.charles.meshtalk.app.data.AiMessageEntity
import com.charles.meshtalk.app.media.ImageCompressor
import com.charles.meshtalk.app.media.MediaPrepResult
import com.charles.meshtalk.app.ui.theme.SignalGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A private, fully-offline chat with an on-device Gemma 4 model — separate from the mesh, nothing
 * here ever goes over Bluetooth. Conversations are saved locally (Room) so you can start a new
 * chat and come back to old ones later. Model download needs internet once; inference after that
 * doesn't. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen() {
    val context = LocalContext.current
    val repository = remember { AiChatRepository.get(context) }
    val scope = rememberCoroutineScope()
    val state by GemmaEngineManager.state.collectAsState()
    val sessions by repository.sessions.collectAsState(initial = emptyList())
    val pendingSessions by repository.pendingSessions.collectAsState()

    // rememberSaveable so switching bottom-nav tabs and coming back keeps the same conversation
    // open instead of losing your place.
    var currentSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val messages by (currentSessionId?.let { repository.messagesFor(it) } ?: kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsState(initial = emptyList())
    val sending = currentSessionId != null && pendingSessions.contains(currentSessionId)

    var draft by remember { mutableStateOf("") }
    var stagedImage by remember { mutableStateOf<ByteArray?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { GemmaEngineManager.refreshState(context) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) { ImageCompressor.compress(context.contentResolver, uri) }
            if (result is MediaPrepResult.Image) stagedImage = result.bytes
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("AI Chat") },
            actions = {
                if (state is ModelState.Ready) {
                    IconButton(onClick = {
                        currentSessionId = null
                        draft = ""
                        stagedImage = null
                    }) { Icon(Icons.Filled.Add, contentDescription = "New chat") }
                    IconButton(onClick = { showHistory = true }) {
                        Icon(Icons.Filled.History, contentDescription = "Chat history")
                    }
                }
            }
        )
        when (val s = state) {
            is ModelState.NotDownloaded -> DownloadPrompt(
                onDownload = { scope.launch { GemmaEngineManager.downloadModel(context) } }
            )
            is ModelState.Downloading -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text("Downloading Gemma 4 (~2.6GB)…", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { s.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                )
                Text(
                    "${(s.progress * 100).toInt()}%",
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is ModelState.Downloaded -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Model downloaded. Load it into memory to start chatting (~10s).")
                Button(
                    onClick = { scope.launch { GemmaEngineManager.initializeIfNeeded(context) } },
                    modifier = Modifier.padding(top = 16.dp)
                ) { Text("Load model") }
            }
            is ModelState.Initializing -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Text("Loading model…", modifier = Modifier.padding(top = 16.dp))
            }
            is ModelState.Error -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = { GemmaEngineManager.refreshState(context) },
                    modifier = Modifier.padding(top = 16.dp)
                ) { Text("Back") }
            }
            is ModelState.Ready -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                ) {
                    items(messages, key = { it.id }) { message -> ChatBubble(message) }
                    if (sending) item { Text("Gemma is thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                stagedImage?.let { bytes ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val bmp = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Attached photo",
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                            )
                        }
                        IconButton(onClick = { stagedImage = null }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove photo")
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { imagePicker.launch("image/*") }, enabled = !sending) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = "Attach photo")
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask Gemma anything…") },
                        enabled = !sending
                    )
                    Button(
                        enabled = !sending && (draft.isNotBlank() || stagedImage != null),
                        onClick = {
                            val question = draft.trim().ifBlank { "What's in this photo?" }
                            val image = stagedImage
                            draft = ""
                            stagedImage = null
                            if (currentSessionId != null) {
                                repository.sendMessage(currentSessionId!!, question, image)
                            } else {
                                scope.launch {
                                    val sessionId = repository.createSession()
                                    currentSessionId = sessionId
                                    repository.sendMessage(sessionId, question, image)
                                }
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text("Send") }
                }
            }
        }
    }

    if (showHistory) {
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("Chat history") },
            text = {
                if (sessions.isEmpty()) {
                    Text("No saved chats yet.")
                } else {
                    Column {
                        sessions.forEach { session ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentSessionId = session.id
                                        showHistory = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(session.title, fontWeight = FontWeight.Bold)
                                    Text(
                                        relativeTime(session.updatedAt),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = {
                                    repository.deleteSession(session.id)
                                    if (currentSessionId == session.id) currentSessionId = null
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete chat")
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistory = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun DownloadPrompt(onDownload: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Chat with an on-device AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "MeshTalk can run Google's Gemma 4 model entirely on this device using LiteRT-LM — " +
                "no internet needed once it's downloaded, and nothing you type here ever goes " +
                "over the mesh. You can attach photos for Gemma to look at, too. The model is a " +
                "~2.6GB download and needs a reasonably capable device (8GB+ RAM recommended) to " +
                "run well.",
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)
        )
        Button(onClick = onDownload) { Text("Download Gemma 4 (2.6GB)") }
    }
}

@Composable
private fun ChatBubble(message: AiMessageEntity) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            if (message.fromUser) "you" else "gemma",
            color = if (message.fromUser) MaterialTheme.colorScheme.onSurfaceVariant else SignalGreen,
            style = MaterialTheme.typography.labelMedium
        )
        message.imageBytes?.let { bytes ->
            val bmp = remember(message.id) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Attached photo",
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            }
        }
        Text(message.text, modifier = Modifier.padding(top = 2.dp))
    }
}
