package com.charles.meshtalk.app.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.charles.meshtalk.app.AppVisibility
import com.charles.meshtalk.app.FirebaseHelper
import com.charles.meshtalk.app.ble.BleMeshService
import com.charles.meshtalk.app.ble.AudioCodecId
import com.charles.meshtalk.app.ble.MeshEvent
import com.charles.meshtalk.app.ble.MessageContent
import com.charles.meshtalk.app.crypto.toHex
import com.charles.meshtalk.app.data.AppDatabase
import com.charles.meshtalk.app.data.ContactEntity
import com.charles.meshtalk.app.data.MessageEntity
import com.charles.meshtalk.app.data.ReactionEntity
import com.charles.meshtalk.app.data.ReadReceiptEntity
import com.charles.meshtalk.app.notifications.DmNotifier
import com.charles.meshtalk.app.notifications.VoiceNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class TypingInfo(val nickname: String, val lastTypingAt: Long)

/** Bridges [BleMeshService] (BLE transport + relay) and Room persistence to the UI layer. */
class MeshRepository private constructor(private val appContext: Context) {
    private val db = AppDatabase.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences("meshtalk_prefs", Context.MODE_PRIVATE)

    private var service: BleMeshService? = null
    private var pendingNickname: String? = null
    private var started = false

    // Ephemeral (not persisted) — who's currently typing, expired a few seconds after their last signal.
    private val _publicTypers = MutableStateFlow<Map<String, TypingInfo>>(emptyMap())
    val publicTypers: StateFlow<Map<String, TypingInfo>> = _publicTypers.asStateFlow()
    private val _dmTypers = MutableStateFlow<Map<String, TypingInfo>>(emptyMap())
    val dmTypers: StateFlow<Map<String, TypingInfo>> = _dmTypers.asStateFlow()

    init {
        scope.launch {
            while (true) {
                delay(2000)
                val cutoff = System.currentTimeMillis() - 4000
                _publicTypers.value = _publicTypers.value.filterValues { it.lastTypingAt >= cutoff }
                _dmTypers.value = _dmTypers.value.filterValues { it.lastTypingAt >= cutoff }
            }
        }
    }

    // How many times we've retried each not-yet-read sent message, in-memory only — resets on
    // process restart, which is fine since retries are meant to smooth over short-lived gaps, not
    // guarantee eventual delivery forever (the mesh's store-and-forward cache already handles the
    // "peer reconnects much later" case separately).
    private val retryAttempts = ConcurrentHashMap<String, Int>()

    init {
        scope.launch {
            while (true) {
                delay(RETRY_INTERVAL_MS)
                retryPendingSends()
            }
        }
    }

    /** Re-broadcasts our own sent-but-not-yet-read messages a bounded number of times while we
     * have at least one live mesh link, to smooth over a dropped packet or a recipient who was
     * briefly out of range when it was first sent — on top of (not instead of) the replay cache's
     * existing "catch up a newly-connected peer" behavior. */
    private suspend fun retryPendingSends() {
        val svc = service ?: return
        if (svc.connectedPeerCount.value == 0) return
        val now = System.currentTimeMillis()
        val candidates = db.messageDao().unreadSentMessages()
        for (message in candidates) {
            val age = now - message.timestamp
            if (age < RETRY_MIN_AGE_MS || age > RETRY_MAX_AGE_MS) continue
            val attempts = retryAttempts.getOrDefault(message.id, 0)
            if (attempts >= RETRY_MAX_ATTEMPTS) continue
            if (svc.resendMessage(message.id)) {
                retryAttempts[message.id] = attempts + 1
            }
        }
        retryAttempts.keys.retainAll(candidates.map { it.id }.toSet())
    }

    fun sendTypingIndicator(recipientPubKeyHex: String?) {
        service?.sendTypingIndicator(recipientPubKeyHex)
    }

    // Per-peer live RSSI (dBm) for the Find screen; more negative = further away.
    private val _peerRssi = MutableStateFlow<Map<String, Int>>(emptyMap())
    val peerRssi: StateFlow<Map<String, Int>> = _peerRssi.asStateFlow()

    private val _trackingBeaconEnabled = MutableStateFlow(prefs.getBoolean(PREF_TRACKING_BEACON, false))
    val trackingBeaconEnabled: StateFlow<Boolean> = _trackingBeaconEnabled.asStateFlow()

    fun setTrackingBeaconEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_TRACKING_BEACON, enabled).apply()
        _trackingBeaconEnabled.value = enabled
        service?.setTrackingBeaconEnabled(enabled)
    }

    // Adds the "Find" radar tab (map + all-peers Bluetooth proximity view) to the bottom nav.
    private val _findFeatureEnabled = MutableStateFlow(prefs.getBoolean(PREF_FIND_FEATURE, false))
    val findFeatureEnabled: StateFlow<Boolean> = _findFeatureEnabled.asStateFlow()

    fun setFindFeatureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_FIND_FEATURE, enabled).apply()
        _findFeatureEnabled.value = enabled
    }

    // Off by default: voice clips are far larger than text/action packets, so relaying them through
    // the mesh costs proportionally more bandwidth/battery per hop than what this app normally does.
    private val _voiceRelayEnabled = MutableStateFlow(prefs.getBoolean(PREF_VOICE_RELAY, false))
    val voiceRelayEnabled: StateFlow<Boolean> = _voiceRelayEnabled.asStateFlow()

    fun setVoiceRelayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_VOICE_RELAY, enabled).apply()
        _voiceRelayEnabled.value = enabled
        service?.setVoiceRelayEnabled(enabled)
    }

    // Off by default: DM voice messages always notify (like text DMs), but the public feed can
    // have many senders, so notifying on every public voice clip is opt-in.
    private val _publicVoiceNotificationsEnabled = MutableStateFlow(prefs.getBoolean(PREF_PUBLIC_VOICE_NOTIFICATIONS, false))
    val publicVoiceNotificationsEnabled: StateFlow<Boolean> = _publicVoiceNotificationsEnabled.asStateFlow()

    fun setPublicVoiceNotificationsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_PUBLIC_VOICE_NOTIFICATIONS, enabled).apply()
        _publicVoiceNotificationsEnabled.value = enabled
    }

    private val _analyticsCrashlyticsEnabled = MutableStateFlow(
        prefs.getBoolean(PREF_ANALYTICS_CRASHLYTICS_ENABLED, true)
    )
    val analyticsCrashlyticsEnabled: StateFlow<Boolean> = _analyticsCrashlyticsEnabled.asStateFlow()

    fun setAnalyticsCrashlyticsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_ANALYTICS_CRASHLYTICS_ENABLED, enabled).apply()
        _analyticsCrashlyticsEnabled.value = enabled
        FirebaseHelper.get(appContext).setEnabled(enabled)
    }

    // Local-only policy: still relayed to other mesh peers regardless of this setting (that's
    // just forwarding bytes), but this device won't store/display incoming photos or files.
    private val _receiveAttachments = MutableStateFlow(prefs.getBoolean(PREF_RECEIVE_ATTACHMENTS, true))
    val receiveAttachments: StateFlow<Boolean> = _receiveAttachments.asStateFlow()

    fun setReceiveAttachments(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_RECEIVE_ATTACHMENTS, enabled).apply()
        _receiveAttachments.value = enabled
    }

    private val _connectedPeerCount = MutableStateFlow(0)
    val connectedPeerCount: StateFlow<Int> = _connectedPeerCount.asStateFlow()

    private val _myPublicKeyHex = MutableStateFlow<String?>(null)
    val myPublicKeyHex: StateFlow<String?> = _myPublicKeyHex.asStateFlow()

    private val _myNickname = MutableStateFlow<String?>(null)
    val myNickname: StateFlow<String?> = _myNickname.asStateFlow()

    val publicFeed: Flow<List<MessageEntity>> = db.messageDao().observePublicFeed()
    val contacts: Flow<List<ContactEntity>> = db.contactDao().observeAll()
    val dmPeerKeys: Flow<List<String>> = db.messageDao().observeDmPeerKeys()

    fun dmThread(peerKeyHex: String): Flow<List<MessageEntity>> = db.messageDao().observeDmThread(peerKeyHex)

    fun readersFor(messageId: String): Flow<List<ReadReceiptEntity>> = db.readReceiptDao().observeReadersFor(messageId)

    // All reactions, grouped by message id, refreshed whenever any reaction changes.
    private val _reactionsByMessage = MutableStateFlow<Map<String, List<ReactionEntity>>>(emptyMap())
    val reactionsByMessage: StateFlow<Map<String, List<ReactionEntity>>> = _reactionsByMessage.asStateFlow()

    init {
        scope.launch {
            db.reactionDao().observeAll().collect { all ->
                _reactionsByMessage.value = all.groupBy { it.messageId }
            }
        }
    }

    /** Copies the message text to the system clipboard. */
    fun copyMessageText(message: MessageEntity): String = message.body

    /** Only the original sender can edit; no-op otherwise. Updates locally and, if we're connected,
     * broadcasts the edit to the mesh so peers (and any future reconnects) see the same text. */
    fun editMessage(message: MessageEntity, newText: String) {
        if (!message.isMine || message.deleted) return
        scope.launch {
            db.messageDao().editText(message.id, newText)
            service?.sendEdit(message.id, newText, if (message.type == "PUBLIC") null else message.peerPubKeyHex)
        }
    }

    /** Only the original sender can delete; no-op otherwise. Soft-deletes locally (keeps the row so
     * "message deleted" can still be shown) and broadcasts the delete to the mesh. */
    fun deleteMessage(message: MessageEntity) {
        if (!message.isMine) return
        scope.launch {
            db.messageDao().markDeleted(message.id)
            service?.sendDelete(message.id, if (message.type == "PUBLIC") null else message.peerPubKeyHex)
        }
    }

    /** Toggle: reacting with the same emoji again removes it. Anyone (not just the sender) can
     * react. Recipient for the mesh packet is the DM peer, or broadcast for a public message. */
    fun reactToMessage(message: MessageEntity, emoji: String) {
        val myKey = myPublicKeyHex.value ?: return
        val myNick = myNickname.value ?: myKey.take(8)
        val recipientKeyHex = if (message.type == "PUBLIC") null else message.peerPubKeyHex
        scope.launch {
            val existing = db.reactionDao().forMessageOnce(message.id).firstOrNull { it.reactorPubKeyHex == myKey }
            val removing = existing != null && existing.emoji == emoji
            if (removing) {
                db.reactionDao().remove(message.id, myKey)
            } else {
                db.reactionDao().upsert(ReactionEntity(message.id, myKey, myNick, emoji, System.currentTimeMillis()))
            }
            service?.sendReaction(message.id, emoji, added = !removing, recipientKeyHex)
        }
    }

    /** Marks a received message as read by us: sends a receipt (public = everyone learns; DM =
     * only the original sender) and records our own read locally so we don't resend it. No-op for
     * our own messages or ones we've already acked. */
    fun markAsRead(message: MessageEntity) {
        if (message.isMine) return
        val myKey = myPublicKeyHex.value ?: return
        scope.launch {
            if (db.readReceiptDao().hasRead(message.id, myKey)) return@launch
            val recipientKeyHex = if (message.type == "PUBLIC") null else message.senderPubKeyHex
            service?.sendReadReceipt(message.id, recipientKeyHex)
            db.readReceiptDao().insert(
                ReadReceiptEntity(message.id, myKey, myNickname.value ?: myKey.take(8), System.currentTimeMillis())
            )
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as BleMeshService.LocalBinder).getService()
            service = svc
            pendingNickname?.let { svc.startMesh(it) }
            svc.setTrackingBeaconEnabled(_trackingBeaconEnabled.value)
            svc.setVoiceRelayEnabled(_voiceRelayEnabled.value)
            _myPublicKeyHex.value = svc.myPublicKeyHex()
            _myNickname.value = svc.myNickname()

            scope.launch {
                svc.connectedPeerCount.collect { _connectedPeerCount.value = it }
            }
            scope.launch {
                svc.peerRssi.collect { _peerRssi.value = it }
            }
            scope.launch {
                svc.events.collect { event -> handleEvent(event) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** Starts (or reattaches to) the mesh service. Safe to call multiple times. */
    fun start(nickname: String) {
        if (started) return
        started = true
        pendingNickname = nickname
        val intent = Intent(appContext, BleMeshService::class.java)
        ContextCompat.startForegroundService(appContext, intent)
        appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun sendPublicMessage(text: String) = sendPublicContent(MessageContent.Text(text))
    fun sendPublicImage(bytes: ByteArray, mime: String = "image/jpeg") = sendPublicContent(MessageContent.Image(bytes, mime))
    fun sendPublicFile(bytes: ByteArray, mime: String, filename: String) =
        sendPublicContent(MessageContent.File(bytes, mime, filename))
    fun sendPublicLocation(latitude: Double, longitude: Double, mapBytes: ByteArray?, mapMime: String?) =
        sendPublicContent(MessageContent.Location(latitude, longitude, mapBytes, mapMime))

    private fun sendPublicContent(content: MessageContent) {
        val svc = service ?: return
        val myKey = svc.myPublicKeyHex() ?: return
        val myNick = svc.myNickname() ?: return
        val id = svc.sendPublicContent(content) ?: return
        scope.launch {
            db.messageDao().insert(
                entityFor(id, "PUBLIC", myKey, myNick, null, content, System.currentTimeMillis(), isMine = true)
            )
        }
    }

    /** Returns false if the recipient hasn't been seen on the mesh yet (no known agreement key). */
    fun sendDirectMessage(recipientPubKeyHex: String, text: String): Boolean =
        sendDirectContent(recipientPubKeyHex, MessageContent.Text(text))

    fun sendDirectImage(recipientPubKeyHex: String, bytes: ByteArray, mime: String = "image/jpeg"): Boolean =
        sendDirectContent(recipientPubKeyHex, MessageContent.Image(bytes, mime))

    fun sendDirectFile(recipientPubKeyHex: String, bytes: ByteArray, mime: String, filename: String): Boolean =
        sendDirectContent(recipientPubKeyHex, MessageContent.File(bytes, mime, filename))

    fun sendDirectLocation(
        recipientPubKeyHex: String, latitude: Double, longitude: Double, mapBytes: ByteArray?, mapMime: String?
    ): Boolean = sendDirectContent(recipientPubKeyHex, MessageContent.Location(latitude, longitude, mapBytes, mapMime))

    private fun sendDirectContent(recipientPubKeyHex: String, content: MessageContent): Boolean {
        val svc = service ?: return false
        val myKey = svc.myPublicKeyHex() ?: return false
        val myNick = svc.myNickname() ?: myKey.take(8)
        val id = svc.sendDirectContent(recipientPubKeyHex, content) ?: return false
        scope.launch {
            db.messageDao().insert(
                entityFor(id, "DM", myKey, myNick, recipientPubKeyHex, content, System.currentTimeMillis(), isMine = true)
            )
        }
        return true
    }

    /** Sends a recorded PTT clip to the public feed. Relay reach (direct-only vs. mesh-wide) follows
     * the current [voiceRelayEnabled] setting. */
    fun sendPublicVoiceMessage(bytes: ByteArray, codec: AudioCodecId, sampleRateHz: Int, durationMs: Int) {
        val svc = service ?: return
        val myKey = svc.myPublicKeyHex() ?: return
        val myNick = svc.myNickname() ?: return
        val content = MessageContent.Audio(bytes, codec, sampleRateHz, durationMs)
        val id = svc.sendPublicVoice(content, allowRelay = _voiceRelayEnabled.value) ?: return
        scope.launch {
            db.messageDao().insert(
                entityFor(id, "PUBLIC", myKey, myNick, null, content, System.currentTimeMillis(), isMine = true)
            )
        }
    }

    /** Returns false if the recipient hasn't been seen on the mesh yet (no known agreement key). */
    fun sendDirectVoiceMessage(recipientPubKeyHex: String, bytes: ByteArray, codec: AudioCodecId, sampleRateHz: Int, durationMs: Int): Boolean {
        val svc = service ?: return false
        val myKey = svc.myPublicKeyHex() ?: return false
        val myNick = svc.myNickname() ?: myKey.take(8)
        val content = MessageContent.Audio(bytes, codec, sampleRateHz, durationMs)
        val id = svc.sendDirectVoice(recipientPubKeyHex, content, allowRelay = _voiceRelayEnabled.value) ?: return false
        scope.launch {
            db.messageDao().insert(
                entityFor(id, "DM", myKey, myNick, recipientPubKeyHex, content, System.currentTimeMillis(), isMine = true)
            )
        }
        return true
    }

    private fun handleEvent(event: MeshEvent) {
        when (event) {
            is MeshEvent.PeerAnnounced -> scope.launch {
                db.contactDao().upsert(
                    ContactEntity(
                        signingPubKeyHex = event.signingPubKeyHex,
                        agreementPubKeyHex = event.agreementPubKey.toHex(),
                        nickname = event.nickname,
                        lastSeen = System.currentTimeMillis()
                    )
                )
            }
            is MeshEvent.PublicMessageReceived -> scope.launch {
                db.messageDao().insert(
                    entityFor(
                        event.messageIdHex, "PUBLIC", event.senderPubKeyHex, event.senderNickname,
                        null, applyAttachmentPolicy(event.content), event.timestamp, isMine = false
                    )
                )
            }
            is MeshEvent.DirectMessageReceived -> scope.launch {
                val nickname = db.contactDao().getByKey(event.senderPubKeyHex)?.nickname
                    ?: event.senderPubKeyHex.take(8)
                db.messageDao().insert(
                    entityFor(
                        event.messageIdHex, "DM", event.senderPubKeyHex, nickname,
                        event.senderPubKeyHex, applyAttachmentPolicy(event.content), event.timestamp, isMine = false
                    )
                )
                if (!AppVisibility.isViewingDmThread(event.senderPubKeyHex)) {
                    DmNotifier.notifyNewDm(appContext, event.senderPubKeyHex, nickname, previewFor(event.content))
                }
            }
            is MeshEvent.ReadReceiptReceived -> scope.launch {
                db.readReceiptDao().insert(
                    ReadReceiptEntity(
                        event.originalMessageIdHex, event.readerPubKeyHex, event.readerNickname, event.timestamp
                    )
                )
            }
            is MeshEvent.TypingReceived -> {
                val info = TypingInfo(event.senderNickname, System.currentTimeMillis())
                if (event.isPublic) {
                    _publicTypers.value = _publicTypers.value + (event.senderPubKeyHex to info)
                } else {
                    _dmTypers.value = _dmTypers.value + (event.senderPubKeyHex to info)
                }
            }
            is MeshEvent.MessageEdited -> scope.launch {
                // Only the original sender may edit their own message — verified against what we
                // actually stored, not just trusting the claim in the packet.
                if (db.messageDao().senderOf(event.originalMessageIdHex) == event.editorPubKeyHex) {
                    db.messageDao().editText(event.originalMessageIdHex, event.newText)
                }
            }
            is MeshEvent.MessageDeleted -> scope.launch {
                if (db.messageDao().senderOf(event.originalMessageIdHex) == event.deleterPubKeyHex) {
                    db.messageDao().markDeleted(event.originalMessageIdHex)
                }
            }
            is MeshEvent.ReactionReceived -> scope.launch {
                if (event.added) {
                    db.reactionDao().upsert(
                        ReactionEntity(
                            event.originalMessageIdHex, event.reactorPubKeyHex, event.reactorNickname,
                            event.emoji, event.timestamp
                        )
                    )
                } else {
                    db.reactionDao().remove(event.originalMessageIdHex, event.reactorPubKeyHex)
                }
            }
            is MeshEvent.VoiceMessageReceived -> scope.launch {
                val nickname = event.senderNickname.ifBlank { event.senderPubKeyHex.take(8) }
                val type = if (event.isDirect) "DM" else "PUBLIC"
                val peerKey = if (event.isDirect) event.senderPubKeyHex else null
                db.messageDao().insert(
                    entityFor(
                        event.messageIdHex, type, event.senderPubKeyHex, nickname,
                        peerKey, applyAttachmentPolicy(event.content), event.timestamp, isMine = false
                    )
                )
                // Voice notifications always deep-link into the Walkie-Talkie tab, never the text
                // DM thread (which doesn't show voice clips at all). DMs always notify; the public
                // feed is opt-in since it can otherwise be noisy with many senders.
                if (event.isDirect) {
                    VoiceNotifier.notifyNewVoice(appContext, event.senderPubKeyHex, nickname)
                } else if (_publicVoiceNotificationsEnabled.value) {
                    VoiceNotifier.notifyNewVoice(appContext, VoiceNotifier.TARGET_PUBLIC, nickname)
                }
            }
        }
    }

    /** When attachments are disabled, incoming photos/files are replaced with a text placeholder
     * before being stored — this device simply doesn't keep the bytes, but the sender's message
     * is still relayed on to other mesh peers regardless (that happens at the BLE layer). */
    private fun applyAttachmentPolicy(content: MessageContent): MessageContent {
        if (_receiveAttachments.value) return content
        return when (content) {
            is MessageContent.Image -> MessageContent.Text("📷 Photo (not received — attachments disabled in Settings)")
            is MessageContent.File -> MessageContent.Text("📎 ${content.filename} (not received — attachments disabled in Settings)")
            // Coordinates are tiny (16 bytes) and the point of the feature, so only the bulky map
            // thumbnail image is dropped, not the location itself.
            is MessageContent.Location -> content.copy(mapImageBytes = null, mapImageMime = null)
            is MessageContent.Text -> content
            is MessageContent.Audio -> MessageContent.Text("🔊 Voice message (not received — attachments disabled in Settings)")
        }
    }

    private fun previewFor(content: MessageContent): String = when (content) {
        is MessageContent.Text -> content.body
        is MessageContent.Image -> "📷 Photo"
        is MessageContent.File -> "📎 ${content.filename}"
            is MessageContent.Location -> "📍 Location"
            is MessageContent.Audio -> "🔊 Voice message"
        }

    private fun entityFor(
        id: String,
        type: String,
        senderKeyHex: String,
        senderNickname: String,
        peerKeyHex: String?,
        content: MessageContent,
        timestamp: Long,
        isMine: Boolean
    ): MessageEntity = when (content) {
        is MessageContent.Text -> MessageEntity(
            id = id, type = type, senderPubKeyHex = senderKeyHex, senderNickname = senderNickname,
            peerPubKeyHex = peerKeyHex, contentType = "TEXT", body = content.body,
            timestamp = timestamp, isMine = isMine, delivered = true
        )
        is MessageContent.Image -> MessageEntity(
            id = id, type = type, senderPubKeyHex = senderKeyHex, senderNickname = senderNickname,
            peerPubKeyHex = peerKeyHex, contentType = "IMAGE", body = "Photo",
            mediaBytes = content.bytes, mediaMimeType = content.mime,
            timestamp = timestamp, isMine = isMine, delivered = true
        )
        is MessageContent.File -> MessageEntity(
            id = id, type = type, senderPubKeyHex = senderKeyHex, senderNickname = senderNickname,
            peerPubKeyHex = peerKeyHex, contentType = "FILE", body = content.filename,
            mediaBytes = content.bytes, mediaMimeType = content.mime, mediaFilename = content.filename,
            timestamp = timestamp, isMine = isMine, delivered = true
        )
            is MessageContent.Location -> MessageEntity(
            id = id, type = type, senderPubKeyHex = senderKeyHex, senderNickname = senderNickname,
            peerPubKeyHex = peerKeyHex, contentType = "LOCATION", body = "Location",
            mediaBytes = content.mapImageBytes, mediaMimeType = content.mapImageMime,
            latitude = content.latitude, longitude = content.longitude,
            timestamp = timestamp, isMine = isMine, delivered = true
        )
        is MessageContent.Audio -> MessageEntity(
            id = id, type = type, senderPubKeyHex = senderKeyHex, senderNickname = senderNickname,
            peerPubKeyHex = peerKeyHex, contentType = "VOICE", body = "Voice message",
            mediaBytes = content.bytes,
            mediaMimeType = if (content.codec == AudioCodecId.OPUS) "audio/opus" else "audio/amr",
            mediaDurationMs = content.durationMs, mediaCodec = content.codec.name,
            timestamp = timestamp, isMine = isMine, delivered = true
        )
    }

    companion object {
        private const val PREF_RECEIVE_ATTACHMENTS = "receive_attachments"
        private const val PREF_TRACKING_BEACON = "tracking_beacon"
        private const val PREF_FIND_FEATURE = "find_feature_enabled"
        private const val PREF_VOICE_RELAY = "voice_relay_enabled"
        private const val PREF_PUBLIC_VOICE_NOTIFICATIONS = "public_voice_notifications_enabled"
        private const val PREF_ANALYTICS_CRASHLYTICS_ENABLED = "analytics_crashlytics_enabled"

        // Retry tuning: check every 25s, only retry messages older than 20s (give the first send
        // a moment to succeed normally) and younger than 10 minutes, capped at 5 attempts each —
        // bounded so a permanently-unreachable recipient doesn't get retried forever.
        private const val RETRY_INTERVAL_MS = 25_000L
        private const val RETRY_MIN_AGE_MS = 20_000L
        private const val RETRY_MAX_AGE_MS = 10 * 60_000L
        private const val RETRY_MAX_ATTEMPTS = 5

        @Volatile private var instance: MeshRepository? = null

        fun get(context: Context): MeshRepository =
            instance ?: synchronized(this) {
                instance ?: MeshRepository(context.applicationContext).also { instance = it }
            }
    }
}
