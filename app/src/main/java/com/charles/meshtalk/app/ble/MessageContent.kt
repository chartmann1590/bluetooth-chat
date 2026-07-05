package com.charles.meshtalk.app.ble

import java.nio.ByteBuffer

/** What a PUBLIC/DM packet's payload actually carries, once decoded from its envelope. */
sealed class MessageContent {
    data class Text(val body: String) : MessageContent()
    data class Image(val bytes: ByteArray, val mime: String = "image/jpeg") : MessageContent()
    data class File(val bytes: ByteArray, val mime: String, val filename: String) : MessageContent()
    data class Location(
        val latitude: Double,
        val longitude: Double,
        val mapImageBytes: ByteArray? = null,
        val mapImageMime: String? = null
    ) : MessageContent()
}

/**
 * Wraps a [MessageContent] into the bytes actually placed in a packet's payload
 * (and, for DMs, encrypted as a whole). Format:
 *   kind(1): 0=TEXT 1=IMAGE 2=FILE 3=LOCATION
 *   TEXT:     [utf8 body bytes]
 *   IMAGE:    [mimeLen(1)][mime utf8][image bytes]
 *   FILE:     [mimeLen(1)][mime utf8][filenameLen(1)][filename utf8][file bytes]
 *   LOCATION: [latitude(8 double)][longitude(8 double)][hasImage(1)]
 *             if hasImage: [mimeLen(1)][mime utf8][map image bytes]
 */
object MessageContentCodec {
    private const val KIND_TEXT: Byte = 0
    private const val KIND_IMAGE: Byte = 1
    private const val KIND_FILE: Byte = 2
    private const val KIND_LOCATION: Byte = 3

    fun encode(content: MessageContent): ByteArray = when (content) {
        is MessageContent.Text -> {
            val body = content.body.toByteArray(Charsets.UTF_8)
            ByteBuffer.allocate(1 + body.size).apply {
                put(KIND_TEXT)
                put(body)
            }.array()
        }
        is MessageContent.Image -> {
            val mime = content.mime.toByteArray(Charsets.UTF_8)
            ByteBuffer.allocate(1 + 1 + mime.size + content.bytes.size).apply {
                put(KIND_IMAGE)
                put(mime.size.toByte())
                put(mime)
                put(content.bytes)
            }.array()
        }
        is MessageContent.File -> {
            val mime = content.mime.toByteArray(Charsets.UTF_8)
            val filename = content.filename.toByteArray(Charsets.UTF_8)
            ByteBuffer.allocate(1 + 1 + mime.size + 1 + filename.size + content.bytes.size).apply {
                put(KIND_FILE)
                put(mime.size.toByte())
                put(mime)
                put(filename.size.toByte())
                put(filename)
                put(content.bytes)
            }.array()
        }
        is MessageContent.Location -> {
            val mapBytes = content.mapImageBytes
            val mime = content.mapImageMime?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            val size = 1 + 8 + 8 + 1 + (if (mapBytes != null) 1 + mime.size + mapBytes.size else 0)
            ByteBuffer.allocate(size).apply {
                put(KIND_LOCATION)
                putDouble(content.latitude)
                putDouble(content.longitude)
                if (mapBytes != null) {
                    put(1)
                    put(mime.size.toByte())
                    put(mime)
                    put(mapBytes)
                } else {
                    put(0)
                }
            }.array()
        }
    }

    fun decode(bytes: ByteArray): MessageContent? {
        if (bytes.isEmpty()) return null
        val buffer = ByteBuffer.wrap(bytes)
        return try {
            when (buffer.get()) {
                KIND_TEXT -> {
                    val body = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    MessageContent.Text(String(body, Charsets.UTF_8))
                }
                KIND_IMAGE -> {
                    val mimeLen = buffer.get().toInt() and 0xFF
                    val mime = ByteArray(mimeLen).also { buffer.get(it) }
                    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    MessageContent.Image(data, String(mime, Charsets.UTF_8))
                }
                KIND_FILE -> {
                    val mimeLen = buffer.get().toInt() and 0xFF
                    val mime = ByteArray(mimeLen).also { buffer.get(it) }
                    val nameLen = buffer.get().toInt() and 0xFF
                    val name = ByteArray(nameLen).also { buffer.get(it) }
                    val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    MessageContent.File(data, String(mime, Charsets.UTF_8), String(name, Charsets.UTF_8))
                }
                KIND_LOCATION -> {
                    val lat = buffer.double
                    val lng = buffer.double
                    val hasImage = buffer.get().toInt() != 0
                    if (hasImage) {
                        val mimeLen = buffer.get().toInt() and 0xFF
                        val mime = ByteArray(mimeLen).also { buffer.get(it) }
                        val data = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        MessageContent.Location(lat, lng, data, String(mime, Charsets.UTF_8))
                    } else {
                        MessageContent.Location(lat, lng, null, null)
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
