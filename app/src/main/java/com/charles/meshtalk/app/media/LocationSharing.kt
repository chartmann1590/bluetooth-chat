package com.charles.meshtalk.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Gets a single current location fix; calls back on the main thread with null on failure/timeout. */
object LocationFetcher {
    fun getCurrentLocation(context: Context, onResult: (Location?) -> Unit) {
        val lm = context.getSystemService(LocationManager::class.java) ?: return onResult(null)
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return onResult(null)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, null, context.mainExecutor) { location -> onResult(location) }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, object : LocationListener {
                    override fun onLocationChanged(location: Location) = onResult(location)
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = onResult(null)
                }, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            onResult(null)
        }
    }
}

/**
 * Fetches a small static map thumbnail (OpenStreetMap tile render, no API key needed) centered on
 * a pin at the given coordinates, re-compressed to a predictable small size — same treatment as a
 * regular photo attachment, so once it's on the mesh the recipient needs no internet to see it.
 * Returns null if there's no connectivity or the fetch/decode fails; callers should fall back to
 * coordinates-only in that case.
 */
object StaticMapFetcher {
    private const val MAX_MAP_BYTES = 50_000

    fun fetchJpeg(latitude: Double, longitude: Double): ByteArray? {
        return try {
            val url = "https://staticmap.openstreetmap.de/staticmap.php" +
                "?center=$latitude,$longitude&zoom=16&size=400x300&maptype=mapnik" +
                "&markers=$latitude,$longitude,lightblue1"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            val rawBytes = connection.inputStream.use { it.readBytes() }
            compressToJpeg(rawBytes)
        } catch (e: Exception) {
            null
        }
    }

    private fun compressToJpeg(rawBytes: ByteArray): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
        var quality = 85
        var out = encode(bitmap, quality)
        while (out.size > MAX_MAP_BYTES && quality > 30) {
            quality -= 15
            out = encode(bitmap, quality)
        }
        bitmap.recycle()
        return out
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }
}
