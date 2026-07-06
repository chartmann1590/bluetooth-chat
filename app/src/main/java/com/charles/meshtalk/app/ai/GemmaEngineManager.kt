package com.charles.meshtalk.app.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed class ModelState {
    data object NotDownloaded : ModelState()
    data class Downloading(val progress: Float) : ModelState()
    data object Downloaded : ModelState() // file present, engine not loaded into memory yet
    data object Initializing : ModelState()
    data object Ready : ModelState()
    data class Error(val message: String) : ModelState()
}

/**
 * Manages the on-device Gemma 4 model: download (one-time, needs internet), load into LiteRT-LM,
 * and a simple single-turn-at-a-time chat conversation. Fully offline once the model file exists
 * and the engine has been initialized — no mesh, no network, just local inference.
 */
object GemmaEngineManager {
    // Generic (non-hardware-specific) build so it runs on any arm64/x86_64 device, not just one
    // particular chipset's optimized variant.
    private const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
    private const val MODEL_URL =
        "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
    // Approximate; used only as a progress-bar fallback if the server doesn't send Content-Length.
    private const val APPROX_MODEL_BYTES = 2_780_000_000L

    private val _state = MutableStateFlow<ModelState>(ModelState.NotDownloaded)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    // Outlives any single screen — used so the onboarding opt-in can kick off the download and
    // move on immediately, with the download continuing even if the user navigates away.
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Starts the download in the background if it hasn't already started or finished; safe to
     * call more than once (e.g. if onboarding re-runs). Progress is still observable via [state]. */
    fun downloadInBackground(context: Context) {
        if (_state.value is ModelState.Downloading || modelFile(context).exists()) return
        managerScope.launch { downloadModel(context) }
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    fun modelFile(context: Context): File = File(File(context.filesDir, "models"), MODEL_FILENAME)

    /** Call when the AI chat screen appears, to reflect on-disk/in-memory state accurately. */
    fun refreshState(context: Context) {
        if (_state.value is ModelState.Downloading || _state.value is ModelState.Initializing) return
        _state.value = when {
            conversation != null -> ModelState.Ready
            modelFile(context).exists() -> ModelState.Downloaded
            else -> ModelState.NotDownloaded
        }
    }

    suspend fun downloadModel(context: Context) {
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.filesDir, "models").apply { mkdirs() }
                val target = File(dir, MODEL_FILENAME)
                val tmp = File(dir, "$MODEL_FILENAME.part")
                _state.value = ModelState.Downloading(0f)

                val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: APPROX_MODEL_BYTES

                connection.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var readTotal = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            _state.value = ModelState.Downloading((readTotal.toFloat() / totalBytes).coerceIn(0f, 0.99f))
                        }
                    }
                }
                tmp.renameTo(target)
                _state.value = ModelState.Downloaded
            } catch (e: Exception) {
                _state.value = ModelState.Error("Download failed: ${e.message}")
            }
        }
    }

    suspend fun initializeIfNeeded(context: Context): Boolean {
        if (conversation != null) {
            _state.value = ModelState.Ready
            return true
        }
        val file = modelFile(context)
        if (!file.exists()) {
            _state.value = ModelState.NotDownloaded
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                _state.value = ModelState.Initializing
                val newEngine = try {
                    buildEngine(context, file, Backend.GPU())
                } catch (gpuFailure: Exception) {
                    // Not every device's driver supports the GPU delegate; fall back to CPU rather
                    // than failing the whole chat feature.
                    buildEngine(context, file, Backend.CPU())
                }
                engine = newEngine
                conversation = newEngine.createConversation(ConversationConfig())
                _state.value = ModelState.Ready
                true
            } catch (e: Exception) {
                _state.value = ModelState.Error("Couldn't load model: ${e.message}")
                false
            }
        }
    }

    private fun buildEngine(context: Context, file: File, backend: Backend): Engine {
        val engine = Engine(
            EngineConfig(
                modelPath = file.absolutePath,
                backend = backend,
                // Gemma 4 is a multimodal model; letting it see images uses the same backend as
                // text since we don't need a separate accelerator for the (small, pre-compressed)
                // photos this app sends it.
                visionBackend = backend,
                audioBackend = null,
                maxNumTokens = null,
                maxNumImages = 1,
                cacheDir = context.cacheDir.absolutePath
            )
        )
        engine.initialize()
        return engine
    }

    suspend fun sendMessage(text: String, imageBytes: ByteArray? = null): String = withContext(Dispatchers.IO) {
        val conv = conversation ?: return@withContext "(AI not ready yet)"
        try {
            val response = if (imageBytes == null) {
                conv.sendMessage(text, emptyMap())
            } else {
                val contents = Contents.of(Content.ImageBytes(imageBytes), Content.Text(text))
                conv.sendMessage(contents, emptyMap())
            }
            response.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                .ifBlank { "(no response)" }
        } catch (e: Exception) {
            "Error generating response: ${e.message}"
        }
    }

    fun deleteModel(context: Context) {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
        modelFile(context).delete()
        _state.value = ModelState.NotDownloaded
    }
}
