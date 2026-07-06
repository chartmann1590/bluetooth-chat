package com.charles.meshtalk.app.ai

import android.content.Context
import com.charles.meshtalk.app.data.AiMessageEntity
import com.charles.meshtalk.app.data.AiSessionEntity
import com.charles.meshtalk.app.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Persists on-device AI chat conversations locally (Room) — multiple saved sessions, so you can
 * start a new chat without losing older ones. Entirely separate from the mesh: nothing here is
 * ever sent over Bluetooth.
 */
class AiChatRepository private constructor(private val appContext: Context) {
    private val db = AppDatabase.get(appContext)
    // Outlives any single screen, same reasoning as GemmaEngineManager's download scope: a reply
    // that's still generating shouldn't be cancelled just because the user switched tabs.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val sessions: Flow<List<AiSessionEntity>> = db.aiChatDao().observeSessions()

    private val _pendingSessions = MutableStateFlow<Set<String>>(emptySet())
    /** Session ids currently waiting on a reply — lets the UI show "thinking…" correctly even
     * after navigating away and back mid-response. */
    val pendingSessions: StateFlow<Set<String>> = _pendingSessions.asStateFlow()

    fun messagesFor(sessionId: String): Flow<List<AiMessageEntity>> = db.aiChatDao().observeMessages(sessionId)

    suspend fun createSession(): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.aiChatDao().upsertSession(AiSessionEntity(id, "New chat", now, now))
        return id
    }

    /** Records the user's message, runs inference, records the reply. Fire-and-forget on this
     * repository's own long-lived scope so the request survives the caller's screen navigating
     * away mid-response. */
    fun sendMessage(sessionId: String, text: String, imageBytes: ByteArray?) {
        scope.launch {
            _pendingSessions.value = _pendingSessions.value + sessionId
            try {
                val now = System.currentTimeMillis()
                val isFirstMessage = db.aiChatDao().messageCount(sessionId) == 0
                db.aiChatDao().insertMessage(
                    AiMessageEntity(UUID.randomUUID().toString(), sessionId, fromUser = true, text = text, imageBytes = imageBytes, timestamp = now)
                )
                if (isFirstMessage) db.aiChatDao().setSessionTitle(sessionId, text.take(48))
                db.aiChatDao().touchSession(sessionId, now)

                val answer = GemmaEngineManager.sendMessage(text, imageBytes)
                val answeredAt = System.currentTimeMillis()
                db.aiChatDao().insertMessage(
                    AiMessageEntity(UUID.randomUUID().toString(), sessionId, fromUser = false, text = answer, imageBytes = null, timestamp = answeredAt)
                )
                db.aiChatDao().touchSession(sessionId, answeredAt)
            } finally {
                _pendingSessions.value = _pendingSessions.value - sessionId
            }
        }
    }

    fun deleteSession(sessionId: String) {
        scope.launch {
            db.aiChatDao().deleteMessagesForSession(sessionId)
            db.aiChatDao().deleteSession(sessionId)
        }
    }

    companion object {
        @Volatile private var instance: AiChatRepository? = null

        fun get(context: Context): AiChatRepository =
            instance ?: synchronized(this) {
                instance ?: AiChatRepository(context.applicationContext).also { instance = it }
            }
    }
}
