package com.charles.meshtalk.app.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Limits enforced before anything is sent over the mesh; BLE throughput is only ~1-3 KB/s,
 * and a large multi-chunk transfer sent as hundreds of sequential notifications is much more
 * likely to span a connection drop/renegotiation than a short one, with no resume on failure.
 * Kept small to keep transfers fast enough to reliably finish within one connection.
 */
object MediaLimits {
    const val MAX_IMAGE_BYTES = 60_000
    const val MAX_GENERIC_FILE_BYTES = 100_000
    const val MAX_IMAGE_DIMENSION = 800
}

sealed class MediaPrepResult {
    data class Image(val bytes: ByteArray) : MediaPrepResult()
    data class GenericFile(val bytes: ByteArray, val mime: String, val filename: String) : MediaPrepResult()
    data class Rejected(val reason: String) : MediaPrepResult()
}

/** Downscales + re-compresses a picked image down to [MediaLimits.MAX_IMAGE_BYTES] as JPEG. */
object ImageCompressor {

    fun compress(resolver: ContentResolver, uri: Uri): MediaPrepResult {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsStream = resolver.openInputStream(uri) ?: return MediaPrepResult.Rejected("Couldn't read that image")
        // inJustDecodeBounds=true makes decodeStream always return null; bounds.outWidth/outHeight
        // are the actual result, set as a side effect. A null return here is not a failure.
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return MediaPrepResult.Rejected("Couldn't read that image")
        }

        val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, MediaLimits.MAX_IMAGE_DIMENSION)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            ?: return MediaPrepResult.Rejected("Couldn't decode that image")

        val scaled = downscaleIfNeeded(bitmap, MediaLimits.MAX_IMAGE_DIMENSION)

        var quality = 90
        var bytes = encodeJpeg(scaled, quality)
        while (bytes.size > MediaLimits.MAX_IMAGE_BYTES && quality > 20) {
            quality -= 15
            bytes = encodeJpeg(scaled, quality)
        }
        if (scaled !== bitmap) bitmap.recycle()

        if (bytes.size > MediaLimits.MAX_IMAGE_BYTES) {
            scaled.recycle()
            return MediaPrepResult.Rejected("Image is too complex to compress under ${MediaLimits.MAX_IMAGE_BYTES / 1000}KB")
        }
        scaled.recycle()
        return MediaPrepResult.Image(bytes)
    }

    private fun computeSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= maxDimension || h / 2 >= maxDimension) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun downscaleIfNeeded(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / longest
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun encodeJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}

/** Reads+validates a picked generic file: size cap, and videos are explicitly rejected. */
object FilePrep {
    fun prepare(resolver: ContentResolver, uri: Uri, mimeType: String?, filename: String): MediaPrepResult {
        val mime = mimeType ?: "application/octet-stream"
        if (mime.startsWith("video/")) {
            return MediaPrepResult.Rejected("Video isn't supported over the mesh (too large/slow to transfer)")
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return MediaPrepResult.Rejected("Couldn't read that file")
        if (bytes.size > MediaLimits.MAX_GENERIC_FILE_BYTES) {
            return MediaPrepResult.Rejected("File is too large (max ${MediaLimits.MAX_GENERIC_FILE_BYTES / 1000}KB)")
        }
        return MediaPrepResult.GenericFile(bytes, mime, filename)
    }
}
