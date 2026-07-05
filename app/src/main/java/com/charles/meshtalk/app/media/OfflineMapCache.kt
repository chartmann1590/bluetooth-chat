package com.charles.meshtalk.app.media

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Disk cache for static map images, keyed by a coarse lat/lon/zoom grid cell (~0.001 degrees,
 * roughly 110m) so a location viewed before — a shared-location message reopened later, or the
 * Bluetooth radar's map backdrop — doesn't need network access again. Persisted under the app's
 * private files dir, so it survives app restarts and works fully offline once populated.
 */
object OfflineMapCache {
    private fun cacheDir(context: Context): File =
        File(context.filesDir, "maps").apply { mkdirs() }

    private fun keyFor(latitude: Double, longitude: Double, zoom: Int): String {
        val lat = String.format(Locale.US, "%.3f", latitude)
        val lon = String.format(Locale.US, "%.3f", longitude)
        return "map_${zoom}_${lat}_$lon.jpg"
    }

    fun getCached(context: Context, latitude: Double, longitude: Double, zoom: Int): File? {
        val file = File(cacheDir(context), keyFor(latitude, longitude, zoom))
        return if (file.exists()) file else null
    }

    /**
     * Returns a local file with the map image, fetching and caching it first if needed. Blocking —
     * call from a background dispatcher. Returns a previously-cached copy even with no
     * connectivity right now; only returns null if there's neither a cached copy nor a successful
     * fetch.
     */
    fun getOrFetch(
        context: Context,
        latitude: Double,
        longitude: Double,
        zoom: Int = 16,
        withMarker: Boolean = true
    ): File? {
        getCached(context, latitude, longitude, zoom)?.let { return it }
        val bytes = StaticMapFetcher.fetchJpeg(latitude, longitude, zoom, withMarker) ?: return null
        val file = File(cacheDir(context), keyFor(latitude, longitude, zoom))
        file.writeBytes(bytes)
        return file
    }
}
