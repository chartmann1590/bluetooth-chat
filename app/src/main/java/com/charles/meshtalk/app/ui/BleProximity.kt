package com.charles.meshtalk.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Coarse "how close" label + signal-bar count from a raw BLE RSSI reading (dBm). Shared by the
 * single-contact Find screen and the multi-peer Bluetooth radar screen. */
fun proximityFor(rssi: Int): Pair<String, Int> = when {
    rssi >= -55 -> "Very close" to 5
    rssi >= -65 -> "Close" to 4
    rssi >= -75 -> "Nearby" to 3
    rssi >= -85 -> "Far" to 2
    else -> "Very far" to 1
}

/** Maps RSSI to a 0f (right on top of you) .. 1f (at the edge of range) radius fraction, for
 * placing a peer on the radar. Not a real distance measurement — just a monotonic, clamped scale. */
fun proximityRadiusFraction(rssi: Int): Float {
    val near = -40f
    val far = -95f
    val clamped = rssi.toFloat().coerceIn(far, near)
    return (near - clamped) / (near - far)
}

private data class HeadingSample(val heading: Float, val rssi: Int, val time: Long)

/**
 * A rough bearing (degrees, 0 = north) to a single BLE peer: the circular mean of recent compass
 * headings weighted by how strong the signal was at each heading, so it drifts toward "whichever
 * way I was facing when the signal was strongest" as you turn or walk around. Returns null until
 * the peer is detected and at least one sample has been taken. Same technique as the multi-peer
 * radar screen, factored out here for the single-contact Find screen.
 */
@Composable
fun rememberSignalBearing(heading: Float, rssi: Int?): Float? {
    var bearing by remember { mutableStateOf<Float?>(null) }
    val history = remember { mutableListOf<HeadingSample>() }
    val currentHeading = rememberUpdatedState(heading)
    val currentRssi = rememberUpdatedState(rssi)

    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val r = currentRssi.value
            if (r == null) {
                history.clear()
                bearing = null
                continue
            }
            val now = System.currentTimeMillis()
            history.add(HeadingSample(currentHeading.value, r, now))
            history.removeAll { now - it.time > 12_000 }
            if (history.isEmpty()) continue
            val minRssi = history.minOf { it.rssi }
            var sumSin = 0.0
            var sumCos = 0.0
            for (s in history) {
                val weight = (s.rssi - minRssi + 1).toDouble()
                val rad = Math.toRadians(s.heading.toDouble())
                sumSin += sin(rad) * weight
                sumCos += cos(rad) * weight
            }
            bearing = ((Math.toDegrees(atan2(sumSin, sumCos)) + 360) % 360).toFloat()
        }
    }
    return bearing
}
