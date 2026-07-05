package com.charles.meshtalk.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

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

    /** Last-known fix (may be stale/absent), returned immediately with no new GPS request — good
     * enough for a rough "which area am I in" map backdrop, where a fresh indoor fix could take
     * a long time or never arrive. */
    fun getLastKnownLocation(context: Context): Location? {
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { lm.isProviderEnabled(it) }
                .mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }
}

/**
 * Fetches a small static map thumbnail centered on the given coordinates, stitched together from
 * standard OpenStreetMap raster tiles (tile.openstreetmap.org — the real public tile server, no
 * API key needed) with an optional pin drawn on top, re-compressed to a predictable small size —
 * same treatment as a regular photo attachment, so once it's on the mesh the recipient needs no
 * internet to see it. Returns null if there's no connectivity or the fetch/decode fails; callers
 * should fall back to coordinates-only in that case.
 *
 * (An earlier version of this pointed at staticmap.openstreetmap.de, a third-party static-map
 * proxy that turned out to no longer resolve at all — not a connectivity issue, the domain itself
 * is gone. Tiling directly from the real OSM tile server avoids depending on a proxy's uptime.)
 */
object StaticMapFetcher {
    private const val MAX_MAP_BYTES = 50_000
    private const val TILE_SIZE = 256
    private const val GRID = 3 // 3x3 tiles stitched, then cropped to the final output size
    // OSM's tile usage policy requires a descriptive User-Agent identifying the app.
    private const val USER_AGENT = "MeshTalk-Android/1.0 (+https://github.com/chartmann1590/bluetooth-chat)"
    private const val OUTPUT_WIDTH = 400
    private const val OUTPUT_HEIGHT = 300

    fun fetchJpeg(latitude: Double, longitude: Double, zoom: Int = 16, withMarker: Boolean = true): ByteArray? {
        return try {
            val stitched = fetchStitchedTiles(latitude, longitude, zoom) ?: return null
            val cropped = cropCenteredOn(stitched, latitude, longitude, zoom)
            stitched.recycle()
            if (withMarker) drawPin(cropped)
            compressToJpeg(cropped)
        } catch (e: Exception) {
            null
        }
    }

    private fun lonToTileX(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * (1 shl zoom)

    private fun latToTileY(lat: Double, zoom: Int): Double {
        val latRad = Math.toRadians(lat)
        return (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * (1 shl zoom)
    }

    private fun cos(x: Double) = kotlin.math.cos(x)

    private fun fetchStitchedTiles(latitude: Double, longitude: Double, zoom: Int): Bitmap? {
        val centerTileX = floor(lonToTileX(longitude, zoom)).toInt()
        val centerTileY = floor(latToTileY(latitude, zoom)).toInt()
        val half = GRID / 2
        val stitched = Bitmap.createBitmap(GRID * TILE_SIZE, GRID * TILE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(stitched)
        var fetchedAny = false
        for (row in 0 until GRID) {
            for (col in 0 until GRID) {
                val tx = centerTileX - half + col
                val ty = centerTileY - half + row
                val tileBytes = fetchTile(tx, ty, zoom) ?: continue
                val tileBitmap = BitmapFactory.decodeByteArray(tileBytes, 0, tileBytes.size) ?: continue
                canvas.drawBitmap(tileBitmap, (col * TILE_SIZE).toFloat(), (row * TILE_SIZE).toFloat(), null)
                tileBitmap.recycle()
                fetchedAny = true
            }
        }
        if (!fetchedAny) {
            stitched.recycle()
            return null
        }
        return stitched
    }

    private fun fetchTile(x: Int, y: Int, zoom: Int): ByteArray? {
        val n = 1 shl zoom
        if (x < 0 || y < 0 || x >= n || y >= n) return null
        return try {
            val url = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    /** Crops the stitched tile grid to [OUTPUT_WIDTH]x[OUTPUT_HEIGHT], centered on the exact pixel
     * the coordinates fall at (not just the tile boundary), clamped to stay inside the bitmap. */
    private fun cropCenteredOn(stitched: Bitmap, latitude: Double, longitude: Double, zoom: Int): Bitmap {
        val half = GRID / 2
        val centerTileX = floor(lonToTileX(longitude, zoom))
        val centerTileY = floor(latToTileY(latitude, zoom))
        val exactX = lonToTileX(longitude, zoom)
        val exactY = latToTileY(latitude, zoom)
        val pixelX = ((half + (exactX - centerTileX)) * TILE_SIZE).toInt()
        val pixelY = ((half + (exactY - centerTileY)) * TILE_SIZE).toInt()

        val left = (pixelX - OUTPUT_WIDTH / 2).coerceIn(0, stitched.width - OUTPUT_WIDTH)
        val top = (pixelY - OUTPUT_HEIGHT / 2).coerceIn(0, stitched.height - OUTPUT_HEIGHT)
        return Bitmap.createBitmap(stitched, left, top, OUTPUT_WIDTH, OUTPUT_HEIGHT)
    }

    private fun drawPin(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val cx = bitmap.width / 2f
        val cy = bitmap.height / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(cx, cy, 11f, paint)
        paint.color = Color.rgb(0x33, 0xCC, 0x99) // matches the app's signal-green accent
        canvas.drawCircle(cx, cy, 8f, paint)
    }

    private fun compressToJpeg(bitmap: Bitmap): ByteArray? {
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
