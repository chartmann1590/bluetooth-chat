package com.charles.meshtalk.app.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.charles.meshtalk.app.AppVisibility
import com.charles.meshtalk.app.ble.BleMeshService
import com.charles.meshtalk.app.ble.MeshEvent
import com.charles.meshtalk.app.ble.MessageContent
import com.charles.meshtalk.app.crypto.toHex
import com.charles.meshtalk.app.data.AppDatabase
import com.charles.meshtalk.app.data.ContactEntity
import com.charles.meshtalk.app.data.MessageEntity
import com.charles.meshtalk.app.notifications.DmNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Bridges [BleMeshService] (BLE transport + relay) and Room persistence to the UI layer. */
class MeshRepository private constructor(private val appContext: Context) {
    private val db = AppDatabase.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = appContext.getSharedPreferences("meshtalk_prefs", Context.MODE_PRIVATE)

    private var service: BleMeshService? = null
    private var pendingNickname: String? = null
    private var started = false

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

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as BleMeshService.LocalBinder).getService()
            service = svc
            pendingNickname?.let { svc.startMesh(it) }
            _myPublicKeyHex.value = svc.myPublicKeyHex()
            _myNickname.value = svc.myNickname()

            scope.launch {
                svc.connectedPeerCount.collect { _connectedPeerCount.value = it }
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
            is MessageContent.Text -> content
        }
    }

    private fun previewFor(content: MessageContent): String = when (content) {
        is MessageContent.Text -> content.body
        is MessageContent.Image -> "📷 Photo"
        is MessageContent.File -> "📎 ${content.filename}"
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
    }

    companion object {
        private const val PREF_RECEIVE_ATTACHMENTS = "receive_attachments"

        @Volatile private var instance: MeshRepository? = null

        fun get(context: Context): MeshRepository =
            instance ?: synchronized(this) {
                instance ?: MeshRepository(context.applicationContext).also { instance = it }
            }
    }
}
