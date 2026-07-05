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
        private const val CACHE_CAPACITY = 500
    }

    // Known contacts' agreement (X25519) public keys, learned from ANNOUNCE packets,
    // needed to encrypt DMs to them.
    private val agreementKeys = Collections.synchronizedMap(HashMap<String, ByteArray>())
    private val nicknames = Collections.synchronizedMap(HashMap<String, String>())

    // Bounded LRU of recently seen/sent raw packet bytes, keyed by messageId hex.
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean =
            size > CACHE_CAPACITY
    })

    fun rememberContact(signingPubKeyHex: String, agreementPubKey: ByteArray, nickname: String) {
        agreementKeys[signingPubKeyHex] = agreementPubKey
        nicknames[signingPubKeyHex] = nickname
    }

    fun agreementKeyFor(signingPubKeyHex: String): ByteArray? = agreementKeys[signingPubKeyHex]

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

    /** Result of processing an inbound raw (reassembled) packet. */
    data class Result(val event: MeshEvent?, val relayBytes: ByteArray?)

    fun handleIncoming(rawBytes: ByteArray): Result {
        val packet = PacketCodec.deserialize(rawBytes) ?: return Result(null, null)
        if (cache.containsKey(packet.messageIdHex)) return Result(null, null) // already seen, drop
        if (!PacketCodec.verify(packet)) return Result(null, null) // bad signature, drop

        remember(packet.messageIdHex, rawBytes)

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
