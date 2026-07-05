package com.charles.meshtalk.app.ble

import com.charles.meshtalk.app.crypto.Identity
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

/**
 * Binary wire format for a [Packet]:
 * version(1) type(1) ttl(1) messageId(16) timestamp(8) sender(32) recipient(32)
 * payloadLen(4) payload(N) signature(64)
 *
 * payloadLen is 4 bytes (not 2) to accommodate compressed-image/file payloads up
 * to ~200KB, well beyond a 2-byte length's ~64KB ceiling.
 *
 * The signature covers everything before it.
 */
object PacketCodec {
    private const val SIGNATURE_BYTES = 64
    const val HEADER_BYTES = 1 + 1 + 1 + 16 + 8 + 32 + 32 + 4

    fun buildAndSign(
        identity: Identity,
        type: PacketType,
        ttl: Int,
        recipientSigningPubKey: ByteArray,
        payload: ByteArray,
        messageId: ByteArray = randomMessageId()
    ): Packet {
        val timestamp = System.currentTimeMillis()
        val signable = encodeSignablePart(
            type, ttl, messageId, timestamp,
            identity.signingPublicKey, recipientSigningPubKey, payload
        )
        val signature = identity.sign(signable)
        return Packet(
            type = type,
            ttl = ttl,
            messageId = messageId,
            timestamp = timestamp,
            senderSigningPubKey = identity.signingPublicKey,
            recipientSigningPubKey = recipientSigningPubKey,
            payload = payload,
            signature = signature
        )
    }

    fun randomMessageId(): ByteArray {
        val id = UUID.randomUUID()
        val buffer = ByteBuffer.allocate(16)
        buffer.putLong(id.mostSignificantBits)
        buffer.putLong(id.leastSignificantBits)
        return buffer.array()
    }

    private fun encodeSignablePart(
        type: PacketType,
        ttl: Int,
        messageId: ByteArray,
        timestamp: Long,
        sender: ByteArray,
        recipient: ByteArray,
        payload: ByteArray
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_BYTES + payload.size)
        buffer.put(PACKET_VERSION)
        buffer.put(type.code)
        buffer.put(ttl.toByte())
        buffer.put(messageId)
        buffer.putLong(timestamp)
        buffer.put(sender)
        buffer.put(recipient)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun serialize(packet: Packet): ByteArray {
        val signable = encodeSignablePart(
            packet.type, packet.ttl, packet.messageId, packet.timestamp,
            packet.senderSigningPubKey, packet.recipientSigningPubKey, packet.payload
        )
        val buffer = ByteBuffer.allocate(signable.size + SIGNATURE_BYTES)
        buffer.put(signable)
        buffer.put(packet.signature)
        return buffer.array()
    }

    fun deserialize(bytes: ByteArray): Packet? {
        if (bytes.size < HEADER_BYTES + SIGNATURE_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes)
        val version = buffer.get()
        if (version != PACKET_VERSION) return null
        val type = PacketType.fromCode(buffer.get())
        val ttl = buffer.get().toInt()
        val messageId = ByteArray(16).also { buffer.get(it) }
        val timestamp = buffer.long
        val sender = ByteArray(32).also { buffer.get(it) }
        val recipient = ByteArray(32).also { buffer.get(it) }
        val payloadLen = buffer.int
        if (payloadLen < 0 || buffer.remaining() < payloadLen + SIGNATURE_BYTES) return null
        val payload = ByteArray(payloadLen).also { buffer.get(it) }
        val signature = ByteArray(SIGNATURE_BYTES).also { buffer.get(it) }

        return Packet(version, type, ttl, messageId, timestamp, sender, recipient, payload, signature)
    }

    fun verify(packet: Packet): Boolean {
        val signable = encodeSignablePart(
            packet.type, packet.ttl, packet.messageId, packet.timestamp,
            packet.senderSigningPubKey, packet.recipientSigningPubKey, packet.payload
        )
        return Identity.verify(packet.senderSigningPubKey, signable, packet.signature)
    }
}

/**
 * BLE MTU (often ~20 bytes without negotiation, up to ~512 negotiated) is
 * smaller than a full serialized packet, so packets are split into chunks
 * with a small header for reassembly on the other side.
 */
object Fragmenter {
    // chunkIndex(2) + totalChunks(2) + packetId(16)
    const val CHUNK_HEADER_BYTES = 2 + 2 + 16

    fun fragment(packetId: ByteArray, data: ByteArray, mtuPayloadSize: Int): List<ByteArray> {
        val chunkDataSize = (mtuPayloadSize - CHUNK_HEADER_BYTES).coerceAtLeast(1)
        val totalChunks = ((data.size + chunkDataSize - 1) / chunkDataSize).coerceAtLeast(1)
        val chunks = mutableListOf<ByteArray>()
        for (i in 0 until totalChunks) {
            val start = i * chunkDataSize
            val end = minOf(start + chunkDataSize, data.size)
            val slice = data.copyOfRange(start, end)
            val buffer = ByteBuffer.allocate(CHUNK_HEADER_BYTES + slice.size)
            buffer.putShort(i.toShort())
            buffer.putShort(totalChunks.toShort())
            buffer.put(packetId)
            buffer.put(slice)
            chunks.add(buffer.array())
        }
        return chunks
    }
}

/** Reassembles fragmented packets received from potentially many concurrent senders. */
class Reassembler {
    private data class Pending(val total: Int, val parts: Array<ByteArray?>)

    private val pending = HashMap<String, Pending>()

    /** Returns the fully-reassembled packet bytes once all chunks for a packetId arrive, else null. */
    @Synchronized
    fun addChunk(chunk: ByteArray): ByteArray? {
        if (chunk.size < Fragmenter.CHUNK_HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(chunk)
        val index = buffer.short.toInt() and 0xFFFF
        val total = buffer.short.toInt() and 0xFFFF
        val packetId = ByteArray(16).also { buffer.get(it) }
        val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val key = packetId.joinToString("") { "%02x".format(it) }

        val entry = pending.getOrPut(key) { Pending(total, arrayOfNulls(total)) }
        if (index >= entry.parts.size) return null
        entry.parts[index] = data

        if (entry.parts.all { it != null }) {
            pending.remove(key)
            val out = ByteArray(entry.parts.sumOf { it!!.size })
            var offset = 0
            for (part in entry.parts) {
                part!!.copyInto(out, offset)
                offset += part.size
            }
            return out
        }
        return null
    }
}
