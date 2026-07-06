package com.charles.meshtalk.app.ble

import com.charles.meshtalk.app.crypto.Aead
import com.charles.meshtalk.app.crypto.Identity
import com.charles.meshtalk.app.crypto.hexToBytes
import com.charles.meshtalk.app.crypto.toHex
import java.nio.ByteBuffer
import java.util.Collections
import java.util.LinkedHashMap

sealed class MeshEvent {
    data class PeerAnnounced(
        val signingPubKeyHex: String,
        val agreementPubKey: ByteArray,
        val nickname: String
    ) : MeshEvent()

    data class PublicMessageReceived(
        val senderPubKeyHex: String,
        val senderNickname: String,
        val messageIdHex: String,
        val content: MessageContent,
        val timestamp: Long
    ) : MeshEvent()

    data class DirectMessageReceived(
        val senderPubKeyHex: String,
        val messageIdHex: String,
        val content: MessageContent,
        val timestamp: Long
    ) : MeshEvent()

    data class ReadReceiptReceived(
        val originalMessageIdHex: String,
        val readerPubKeyHex: String,
        val readerNickname: String,
        val timestamp: Long
    ) : MeshEvent()

    data class TypingReceived(
        val senderPubKeyHex: String,
        val senderNickname: String,
        val isPublic: Boolean
    ) : MeshEvent()

    data class MessageEdited(
        val originalMessageIdHex: String,
        val editorPubKeyHex: String,
        val newText: String,
        val timestamp: Long
    ) : MeshEvent()

    data class MessageDeleted(
        val originalMessageIdHex: String,
        val deleterPubKeyHex: String,
        val timestamp: Long
    ) : MeshEvent()

    data class ReactionReceived(
        val originalMessageIdHex: String,
        val reactorPubKeyHex: String,
        val reactorNickname: String,
        val emoji: String,
        val added: Boolean,
        val timestamp: Long
    ) : MeshEvent()
}

/**
 * Pure (non-Android) mesh relay logic: dedup, TTL decrement, and a bounded
 * store-and-forward cache replayed to newly connected peers. BLE transport
 * (BleMeshService) feeds raw bytes in and relays the returned bytes back out
 * over any live connections.
 */
class MeshEngine(private val identity: Identity) {

    companion object {
        const val DEFAULT_TTL = 6
        // Typing indicators only need to reach immediate/near neighbors quickly; a short TTL
        // bounds how far a duplicate can loop before dying out (see ephemeralDedup below).
        const val TYPING_TTL = 2
        private const val CACHE_CAPACITY = 500
        private const val EPHEMERAL_DEDUP_CAPACITY = 200
    }

    // Known contacts' agreement (X25519) public keys, learned from ANNOUNCE packets,
    // needed to encrypt DMs to them.
    private val agreementKeys = Collections.synchronizedMap(HashMap<String, ByteArray>())
    private val nicknames = Collections.synchronizedMap(HashMap<String, String>())

    // Bounded LRU of recently seen/sent raw packet bytes, keyed by messageId hex. Replayed to
    // newly-connected peers via packetsForNewPeer(), so only durable content goes in here.
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > CACHE_CAPACITY
    })

    // Separate, smaller dedup-only record for ephemeral packets (currently just TYPING) that
    // must never be replayed as stale history to a newly-connected peer.
    private val ephemeralDedup = Collections.synchronizedMap(object : LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean =
            size > EPHEMERAL_DEDUP_CAPACITY
    })

    fun rememberContact(signingPubKeyHex: String, agreementPubKey: ByteArray, nickname: String) {
        agreementKeys[signingPubKeyHex] = agreementPubKey
        nicknames[signingPubKeyHex] = nickname
    }

    fun agreementKeyFor(signingPubKeyHex: String): ByteArray? = agreementKeys[signingPubKeyHex]

    /** Raw signed bytes for a previously sent/relayed message, if still in the replay cache —
     * lets a retry re-broadcast the exact original packet rather than building a new one. */
    fun rawBytesFor(messageIdHex: String): ByteArray? = cache[messageIdHex]

    /** Packets to send to a peer we just connected to, e.g. our own announce + recent history. */
    fun packetsForNewPeer(): List<ByteArray> {
        val announce = createAnnounce()
        return listOf(announce) + synchronized(cache) { cache.values.toList() }
    }

    fun createAnnounce(): ByteArray {
        val payload = ByteBuffer.allocate(32 + 1 + identity.nickname.toByteArray(Charsets.UTF_8).size)
        val nickBytes = identity.nickname.toByteArray(Charsets.UTF_8)
        payload.put(identity.agreementPublicKey)
        payload.put(nickBytes.size.toByte())
        payload.put(nickBytes)
        val packet = PacketCodec.buildAndSign(identity, PacketType.ANNOUNCE, DEFAULT_TTL, BROADCAST_KEY, payload.array())
        val bytes = PacketCodec.serialize(packet)
        remember(packet.messageIdHex, bytes)
        return bytes
    }

    fun createPublicMessage(content: MessageContent): ByteArray {
        val packet = PacketCodec.buildAndSign(
            identity, PacketType.PUBLIC, DEFAULT_TTL, BROADCAST_KEY, MessageContentCodec.encode(content)
        )
        val bytes = PacketCodec.serialize(packet)
        remember(packet.messageIdHex, bytes)
        return bytes
    }

    /** Returns null if we don't yet know the recipient's agreement key (no ANNOUNCE seen from them). */
    fun createDirectMessage(recipientSigningPubKeyHex: String, content: MessageContent): ByteArray? {
        val recipientAgreementKey = agreementKeys[recipientSigningPubKeyHex] ?: return null
        val recipientSigningKey = recipientSigningPubKeyHex.hexToBytes()
        val sharedSecret = identity.deriveSharedSecret(recipientAgreementKey)
        val aesKey = Aead.deriveKey(sharedSecret)
        val ciphertext = Aead.encrypt(aesKey, MessageContentCodec.encode(content))

        val payload = ByteBuffer.allocate(32 + ciphertext.size)
        payload.put(identity.agreementPublicKey)
        payload.put(ciphertext)

        val packet = PacketCodec.buildAndSign(identity, PacketType.DM, DEFAULT_TTL, recipientSigningKey, payload.array())
        val bytes = PacketCodec.serialize(packet)
        remember(packet.messageIdHex, bytes)
        return bytes
    }

    /**
     * `recipientSigningPubKey` should be [BROADCAST_KEY] for a receipt on a public message (every
     * peer records who's read it), or the original sender's key for a receipt on a DM (only that
     * peer needs to know/display it).
     */
    fun createReadReceipt(originalMessageIdHex: String, recipientSigningPubKey: ByteArray): ByteArray {
        val packet = PacketCodec.buildAndSign(
            identity, PacketType.READ_RECEIPT, DEFAULT_TTL, recipientSigningPubKey, originalMessageIdHex.hexToBytes()
        )
        val bytes = PacketCodec.serialize(packet)
        remember(packet.messageIdHex, bytes)
        return bytes
    }

    /** `recipientSigningPubKey` is [BROADCAST_KEY] for the public feed, or a specific peer's key
     * for a DM thread. Not remembered in the replay cache — a stale "was typing" signal from
     * minutes ago has no value to a newly-connected peer. */
    fun createTypingIndicator(recipientSigningPubKey: ByteArray): ByteArray {
        val packet = PacketCodec.buildAndSign(
            identity, PacketType.TYPING, TYPING_TTL, recipientSigningPubKey, ByteArray(0)
        )
        return PacketCodec.serialize(packet)
    }

    /** `recipientSigningPubKeyHex` null = public feed edit (plaintext, signed); non-null = a DM
     * edit, encrypted the same way the original DM was. Returns null if we don't know the DM
     * recipient's agreement key yet. */
    fun createEdit(targetMessageIdHex: String, newText: String, recipientSigningPubKeyHex: String?): ByteArray? {
        val textBytes = newText.toByteArray(Charsets.UTF_8)
        val plainPayload = ByteBuffer.allocate(16 + textBytes.size).apply {
            put(targetMessageIdHex.hexToBytes())
            put(textBytes)
        }.array()
        return buildActionPacket(PacketType.EDIT, plainPayload, recipientSigningPubKeyHex)
    }

    fun createDelete(targetMessageIdHex: String, recipientSigningPubKeyHex: String?): ByteArray? =
        buildActionPacket(PacketType.DELETE, targetMessageIdHex.hexToBytes(), recipientSigningPubKeyHex)

    fun createReaction(
        targetMessageIdHex: String, emoji: String, added: Boolean, recipientSigningPubKeyHex: String?
    ): ByteArray? {
        val emojiBytes = emoji.toByteArray(Charsets.UTF_8)
        val plainPayload = ByteBuffer.allocate(16 + 1 + emojiBytes.size).apply {
            put(targetMessageIdHex.hexToBytes())
            put(if (added) 1.toByte() else 0.toByte())
            put(emojiBytes)
        }.array()
        return buildActionPacket(PacketType.REACTION, plainPayload, recipientSigningPubKeyHex)
    }

    /** Builds a signed action packet (EDIT/DELETE/REACTION): plaintext+broadcast for the public
     * feed, or encrypted the same way a DM is for a specific recipient. Remembered in the replay
     * cache like any durable content (a newly-connected peer should still learn about edits/
     * deletes/reactions that happened while they were away). */
    private fun buildActionPacket(type: PacketType, plainPayload: ByteArray, recipientSigningPubKeyHex: String?): ByteArray? {
        val recipientKey: ByteArray
        val payload: ByteArray
        if (recipientSigningPubKeyHex == null) {
            recipientKey = BROADCAST_KEY
            payload = plainPayload
        } else {
            val recipientAgreementKey = agreementKeys[recipientSigningPubKeyHex] ?: return null
            recipientKey = recipientSigningPubKeyHex.hexToBytes()
            val sharedSecret = identity.deriveSharedSecret(recipientAgreementKey)
            val aesKey = Aead.deriveKey(sharedSecret)
            val ciphertext = Aead.encrypt(aesKey, plainPayload)
            payload = ByteBuffer.allocate(32 + ciphertext.size).apply {
                put(identity.agreementPublicKey)
                put(ciphertext)
            }.array()
        }
        val packet = PacketCodec.buildAndSign(identity, type, DEFAULT_TTL, recipientKey, payload)
        val bytes = PacketCodec.serialize(packet)
        remember(packet.messageIdHex, bytes)
        return bytes
    }

    private fun decryptDmPayload(payload: ByteArray): ByteArray? {
        val buf = ByteBuffer.wrap(payload)
        val senderAgreementKey = ByteArray(32).also { buf.get(it) }
        val ciphertext = ByteArray(buf.remaining()).also { buf.get(it) }
        val sharedSecret = identity.deriveSharedSecret(senderAgreementKey)
        val aesKey = Aead.deriveKey(sharedSecret)
        return try {
            Aead.decrypt(aesKey, ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    /** Result of processing an inbound raw (reassembled) packet. */
    data class Result(val event: MeshEvent?, val relayBytes: ByteArray?)

    fun handleIncoming(rawBytes: ByteArray): Result {
        val packet = PacketCodec.deserialize(rawBytes) ?: return Result(null, null)
        val alreadySeen = cache.containsKey(packet.messageIdHex) || ephemeralDedup.containsKey(packet.messageIdHex)
        if (alreadySeen) return Result(null, null) // already seen, drop
        if (!PacketCodec.verify(packet)) return Result(null, null) // bad signature, drop

        if (packet.type == PacketType.TYPING) {
            ephemeralDedup[packet.messageIdHex] = true
        } else {
            remember(packet.messageIdHex, rawBytes)
        }

        val event: MeshEvent? = when (packet.type) {
            PacketType.ANNOUNCE -> {
                val buf = ByteBuffer.wrap(packet.payload)
                val agreementKey = ByteArray(32).also { buf.get(it) }
                val nickLen = buf.get().toInt() and 0xFF
                val nickBytes = ByteArray(nickLen).also { buf.get(it) }
                val nickname = String(nickBytes, Charsets.UTF_8)
                val senderHex = packet.senderSigningPubKey.toHex()
                rememberContact(senderHex, agreementKey, nickname)
                MeshEvent.PeerAnnounced(senderHex, agreementKey, nickname)
            }
            PacketType.PUBLIC -> {
                val senderHex = packet.senderSigningPubKey.toHex()
                val content = MessageContentCodec.decode(packet.payload)
                if (content == null) null else MeshEvent.PublicMessageReceived(
                    senderHex,
                    nicknames[senderHex] ?: senderHex.take(8),
                    packet.messageIdHex,
                    content,
                    packet.timestamp
                )
            }
            PacketType.DM -> {
                if (packet.recipientSigningPubKey.contentEquals(identity.signingPublicKey)) {
                    val buf = ByteBuffer.wrap(packet.payload)
                    val senderAgreementKey = ByteArray(32).also { buf.get(it) }
                    val ciphertext = ByteArray(buf.remaining()).also { buf.get(it) }
                    val sharedSecret = identity.deriveSharedSecret(senderAgreementKey)
                    val aesKey = Aead.deriveKey(sharedSecret)
                    try {
                        val plaintext = Aead.decrypt(aesKey, ciphertext)
                        val content = MessageContentCodec.decode(plaintext)
                        if (content == null) null else MeshEvent.DirectMessageReceived(
                            packet.senderSigningPubKey.toHex(),
                            packet.messageIdHex,
                            content,
                            packet.timestamp
                        )
                    } catch (e: Exception) {
                        null // decryption failed, drop silently
                    }
                } else null // not for us; just relay
            }
            PacketType.READ_RECEIPT -> {
                val isPublicReceipt = packet.recipientSigningPubKey.contentEquals(BROADCAST_KEY)
                val isForMe = packet.recipientSigningPubKey.contentEquals(identity.signingPublicKey)
                if (isPublicReceipt || isForMe) {
                    val readerHex = packet.senderSigningPubKey.toHex()
                    val originalMessageIdHex = packet.payload.joinToString("") { "%02x".format(it) }
                    MeshEvent.ReadReceiptReceived(
                        originalMessageIdHex,
                        readerHex,
                        nicknames[readerHex] ?: readerHex.take(8),
                        packet.timestamp
                    )
                } else null // a DM receipt meant for someone else; just relay
            }
            PacketType.TYPING -> {
                val isPublic = packet.recipientSigningPubKey.contentEquals(BROADCAST_KEY)
                val isForMe = packet.recipientSigningPubKey.contentEquals(identity.signingPublicKey)
                if (isPublic || isForMe) {
                    val senderHex = packet.senderSigningPubKey.toHex()
                    MeshEvent.TypingReceived(senderHex, nicknames[senderHex] ?: senderHex.take(8), isPublic)
                } else null // a DM typing signal meant for someone else; just relay
            }
            PacketType.EDIT -> {
                val isPublic = packet.recipientSigningPubKey.contentEquals(BROADCAST_KEY)
                val isForMe = packet.recipientSigningPubKey.contentEquals(identity.signingPublicKey)
                if (isPublic || isForMe) {
                    val plain = if (isPublic) packet.payload else decryptDmPayload(packet.payload)
                    if (plain == null) null else {
                        val buf = ByteBuffer.wrap(plain)
                        val targetId = ByteArray(16).also { buf.get(it) }
                        val textBytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        MeshEvent.MessageEdited(
                            targetId.toHex(), packet.senderSigningPubKey.toHex(),
                            String(textBytes, Charsets.UTF_8), packet.timestamp
                        )
                    }
                } else null
            }
            PacketType.DELETE -> {
                val isPublic = packet.recipientSigningPubKey.contentEquals(BROADCAST_KEY)
                val isForMe = packet.recipientSigningPubKey.contentEquals(identity.signingPublicKey)
                if (isPublic || isForMe) {
                    val plain = if (isPublic) packet.payload else decryptDmPayload(packet.payload)
                    if (plain == null) null else MeshEvent.MessageDeleted(
                        plain.toHex(), packet.senderSigningPubKey.toHex(), packet.timestamp
                    )
                } else null
            }
            PacketType.REACTION -> {
                val isPublic = packet.recipientSigningPubKey.contentEquals(BROADCAST_KEY)
                val isForMe = packet.recipientSigningPubKey.contentEquals(identity.signingPublicKey)
                if (isPublic || isForMe) {
                    val plain = if (isPublic) packet.payload else decryptDmPayload(packet.payload)
                    if (plain == null) null else {
                        val buf = ByteBuffer.wrap(plain)
                        val targetId = ByteArray(16).also { buf.get(it) }
                        val added = buf.get() == 1.toByte()
                        val emojiBytes = ByteArray(buf.remaining()).also { buf.get(it) }
                        val senderHex = packet.senderSigningPubKey.toHex()
                        MeshEvent.ReactionReceived(
                            targetId.toHex(), senderHex, nicknames[senderHex] ?: senderHex.take(8),
                            String(emojiBytes, Charsets.UTF_8), added, packet.timestamp
                        )
                    }
                } else null
            }
        }

        val newTtl = packet.ttl - 1
        val relayBytes = if (newTtl > 0) {
            packet.ttl = newTtl
            PacketCodec.serialize(packet)
        } else null

        return Result(event, relayBytes)
    }

    private fun remember(messageIdHex: String, bytes: ByteArray) {
        cache[messageIdHex] = bytes
    }
}
