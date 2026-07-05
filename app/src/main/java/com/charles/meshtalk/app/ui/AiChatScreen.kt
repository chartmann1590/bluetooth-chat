package com.charles.meshtalk.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.charles.meshtalk.app.ai.GemmaEngineManager
import com.charles.meshtalk.app.ai.ModelState
import com.charles.meshtalk.app.ui.theme.SignalGreen
import kotlinx.coroutines.launch

private data class ChatLine(val fromUser: Boolean, val text: String)

/** A private, fully-offline chat with an on-device Gemma 4 model — separate from the mesh, nothing
 * here ever goes over Bluetooth. Model download needs internet once; inference after that doesn't. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiChatScreen() {
    val context = LocalContext.current
    val state by GemmaEngineManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val history = remember { mutableStateListOf<ChatLine>() }
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { GemmaEngineManager.refreshState(context) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("AI Chat") })
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
                    items(history) { line -> ChatBubble(line) }
                    if (sending) item { Text("Gemma is thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask Gemma anything…") },
                        enabled = !sending
                    )
                    Button(
                        enabled = !sending && draft.isNotBlank(),
                        onClick = {
                            val question = draft.trim()
                            draft = ""
                            history.add(ChatLine(fromUser = true, text = question))
                            sending = true
                            scope.launch {
                                val answer = GemmaEngineManager.sendMessage(question)
                                history.add(ChatLine(fromUser = false, text = answer))
                                sending = false
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp)
                    ) { Text("Send") }
                }
            }
        }
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
                "over the mesh. The model is a ~2.6GB download and needs a reasonably capable " +
                "device (8GB+ RAM recommended) to run well.",
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)
        )
        Button(onClick = onDownload) { Text("Download Gemma 4 (2.6GB)") }
    }
}

@Composable
private fun ChatBubble(line: ChatLine) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            if (line.fromUser) "you" else "gemma",
            color = if (line.fromUser) MaterialTheme.colorScheme.onSurfaceVariant else SignalGreen,
            style = MaterialTheme.typography.labelMedium
        )
        Text(line.text, modifier = Modifier.padding(top = 2.dp))
    }
}
