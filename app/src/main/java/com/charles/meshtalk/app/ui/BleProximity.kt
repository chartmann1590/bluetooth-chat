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
import kotlin.math.pow
import kotlin.math.sin

// Standard log-distance path-loss model used for BLE proximity estimation (the same technique
// iBeacon-style proximity APIs use): distance = 10 ^ ((measuredPowerAt1m - rssi) / (10 * N)).
// TX_POWER_AT_1M is the typical RSSI a phone's BLE radio reads at 1 meter; N is the path-loss
// exponent (2.0 = free space). Neither is calibrated per-device, so this is an estimate, not a
// precise measurement — but it tracks real-world distance far better than treating dBm linearly,
// which badly overstated distance for anything within a few feet.
private const val TX_POWER_AT_1M = -59
private const val PATH_LOSS_EXPONENT = 2.0
private const val MAX_DISPLAY_METERS = 15.0

/** Estimated distance in meters from a raw BLE RSSI reading (dBm), via the log-distance path-loss
 * model. Best-effort: walls, orientation, and antenna differences all shift this by several dB. */
fun estimatedDistanceMeters(rssi: Int): Double {
    val ratio = (TX_POWER_AT_1M - rssi) / (10.0 * PATH_LOSS_EXPONENT)
    return 10.0.pow(ratio)
}

/** Coarse "how close" label + signal-bar count from a raw BLE RSSI reading (dBm), based on
 * estimated distance rather than raw dBm thresholds. Shared by the single-contact Find screen and
 * the multi-peer Bluetooth radar screen. */
fun proximityFor(rssi: Int): Pair<String, Int> {
    val meters = estimatedDistanceMeters(rssi)
    return when {
        meters < 1.0 -> "Very close" to 5
        meters < 3.0 -> "Close" to 4
        meters < 8.0 -> "Nearby" to 3
        meters < 15.0 -> "Far" to 2
        else -> "Very far" to 1
    }
}

/** Maps RSSI to a 0f (right on top of you) .. 1f (at the edge of range) radius fraction, for
 * placing a peer on the radar, using the same distance estimate as [proximityFor] so someone a
 * few feet away actually lands near the center instead of partway to the edge. */
fun proximityRadiusFraction(rssi: Int): Float {
    val meters = estimatedDistanceMeters(rssi)
    return (meters / MAX_DISPLAY_METERS).coerceIn(0.0, 1.0).toFloat()
}

/** Eases an angle (degrees) toward a target by fraction [t] of the shortest angular distance
 * between them, wrapping correctly across the 0/360 boundary. Used to damp bearing estimates so a
 * peer marker settles into a fixed spot instead of chasing every noisy signal-strength sample. */
fun lerpAngleDegrees(from: Float, to: Float, t: Float): Float {
    val diff = ((to - from + 540f) % 360f) - 180f
    return (from + diff * t + 360f) % 360f
}

private data class HeadingSample(val heading: Float, val rssi: Int, val time: Long)

/**
 * A rough bearing (degrees, 0 = north) to a single BLE peer: the circular mean of recent compass
 * headings weighted by how strong the signal was at each heading, so it drifts toward "whichever
 * way I was facing when the signal was strongest" as you turn or walk around. The result is
 * heavily damped (eased a small step toward the raw estimate each tick, not snapped to it), so the
 * displayed bearing settles into a fixed spot and stays there — noisy RSSI blips no longer make it
 * chase your rotation. Returns null until the peer is detected and at least one sample has been
 * taken. Same technique as the multi-peer radar screen, factored out here for the single-contact
 * Find screen.
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
            val instantEstimate = ((Math.toDegrees(atan2(sumSin, sumCos)) + 360) % 360).toFloat()
            bearing = bearing?.let { lerpAngleDegrees(it, instantEstimate, 0.06f) } ?: instantEstimate
        }
    }
    return bearing
}
