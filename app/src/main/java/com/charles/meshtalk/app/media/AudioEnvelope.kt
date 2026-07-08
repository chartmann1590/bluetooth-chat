package com.charles.meshtalk.app.media

import java.nio.ByteBuffer

/**
 * Wraps an encoded clip's codec-config buffers (CSD — e.g. Opus's OpusHead/pre-skip/pre-roll
 * blobs) together with the actual encoded audio data into one byte array, so [AudioRecorder] and
 * [AudioPlayer] can round-trip them through the mesh as a single opaque blob. MediaCodec requires
 * CSD to be supplied on the decoder's MediaFormat before `configure()`, not fed as ordinary input —
 * without this, decoding throws IllegalStateException on the very first output buffer.
 *
 * Format: [csdCount(1)][for each: len(4)][bytes]... [remaining bytes = encoded audio data]
 */
object AudioEnvelope {
    data class Decoded(val csd: List<ByteArray>, val audioData: ByteArray)

    fun encode(csd: List<ByteArray>, audioData: ByteArray): ByteArray {
        val headerSize = 1 + csd.sumOf { 4 + it.size }
        val buffer = ByteBuffer.allocate(headerSize + audioData.size)
        buffer.put(csd.size.toByte())
        csd.forEach {
            buffer.putInt(it.size)
            buffer.put(it)
        }
        buffer.put(audioData)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): Decoded {
        val buffer = ByteBuffer.wrap(bytes)
        val count = buffer.get().toInt() and 0xFF
        val csd = (0 until count).map {
            val len = buffer.int
            ByteArray(len).also { b -> buffer.get(b) }
        }
        val audioData = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return Decoded(csd, audioData)
    }

    private val OPUS_MAGIC = byteArrayOf('A'.code.toByte(), 'O'.code.toByte(), 'P'.code.toByte(), 'U'.code.toByte(), 'S'.code.toByte())

    /**
     * Android's software Opus encoder hands back its three config buffers (OpusHead, pre-skip,
     * pre-roll) concatenated into a single MediaCodec output buffer, each wrapped as
     * `[8-byte ASCII tag]["AOPUSHDR"/"AOPUSDLY"/"AOPUSPRL"][8-byte little-endian length][data]`.
     * The decoder instead expects three *separate* csd-0/csd-1/csd-2 buffers containing only the
     * inner data — so this splits one combined blob back into its parts. Non-Opus (e.g. AMR-NB)
     * csd blobs don't use this wrapper and are returned as a single-element list unchanged.
     */
    fun splitOpusConfigBlob(blob: ByteArray): List<ByteArray> {
        if (blob.size < OPUS_MAGIC.size || !OPUS_MAGIC.indices.all { blob[it] == OPUS_MAGIC[it] }) {
            return listOf(blob)
        }
        val parts = mutableListOf<ByteArray>()
        val buffer = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= 16) {
            val tag = ByteArray(8).also { buffer.get(it) }
            val len = buffer.long.toInt()
            if (len < 0 || len > buffer.remaining()) break
            parts.add(ByteArray(len).also { buffer.get(it) })
        }
        return if (parts.isEmpty()) listOf(blob) else parts
    }
}
