package com.charles.meshtalk.app.ui

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
