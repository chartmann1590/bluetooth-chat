package com.charles.meshtalk.app.repository

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.charles.meshtalk.app.ble.BleMeshService
import com.charles.meshtalk.app.ble.MeshEvent
import com.charles.meshtalk.app.crypto.toHex
import com.charles.meshtalk.app.data.AppDatabase
import com.charles.meshtalk.app.data.ContactEntity
import com.charles.meshtalk.app.data.MessageEntity
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

    private var service: BleMeshService? = null
    private var pendingNickname: String? = null
    private var started = false

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

    fun sendPublicMessage(text: String) {
        val svc = service ?: return
        val myKey = svc.myPublicKeyHex() ?: return
        val myNick = svc.myNickname() ?: return
        val id = svc.sendPublicMessage(text) ?: return
        scope.launch {
            db.messageDao().insert(
                MessageEntity(
                    id = id, type = "PUBLIC", senderPubKeyHex = myKey, senderNickname = myNick,
                    peerPubKeyHex = null, body = text, timestamp = System.currentTimeMillis(),
                    isMine = true, delivered = true
                )
            )
        }
    }

    /** Returns false if the recipient hasn't been seen on the mesh yet (no known agreement key). */
    fun sendDirectMessage(recipientPubKeyHex: String, text: String): Boolean {
        val svc = service ?: return false
        val myKey = svc.myPublicKeyHex() ?: return false
        val id = svc.sendDirectMessage(recipientPubKeyHex, text) ?: return false
        scope.launch {
            db.messageDao().insert(
                MessageEntity(
                    id = id, type = "DM", senderPubKeyHex = myKey, senderNickname = svc.myNickname() ?: myKey.take(8),
                    peerPubKeyHex = recipientPubKeyHex, body = text, timestamp = System.currentTimeMillis(),
                    isMine = true, delivered = true
                )
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
                    MessageEntity(
                        id = event.messageIdHex, type = "PUBLIC", senderPubKeyHex = event.senderPubKeyHex,
                        senderNickname = event.senderNickname, peerPubKeyHex = null, body = event.body,
                        timestamp = event.timestamp, isMine = false, delivered = true
                    )
                )
            }
            is MeshEvent.DirectMessageReceived -> scope.launch {
                val nickname = db.contactDao().getByKey(event.senderPubKeyHex)?.nickname
                    ?: event.senderPubKeyHex.take(8)
                db.messageDao().insert(
                    MessageEntity(
                        id = event.messageIdHex, type = "DM", senderPubKeyHex = event.senderPubKeyHex,
                        senderNickname = nickname, peerPubKeyHex = event.senderPubKeyHex, body = event.body,
                        timestamp = event.timestamp, isMine = false, delivered = true
                    )
                )
            }
        }
    }

    companion object {
        @Volatile private var instance: MeshRepository? = null

        fun get(context: Context): MeshRepository =
            instance ?: synchronized(this) {
                instance ?: MeshRepository(context.applicationContext).also { instance = it }
            }
    }
}
