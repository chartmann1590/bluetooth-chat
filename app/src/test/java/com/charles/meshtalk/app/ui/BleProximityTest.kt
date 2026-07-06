package com.charles.meshtalk.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleProximityTest {

    @Test
    fun `strong signal estimates a short distance`() {
        // -59 dBm is the calibrated 1-meter reference point for this model.
        val meters = estimatedDistanceMeters(-59)
        assertTrue("expected ~1m, got $meters", meters in 0.9..1.1)
    }

    @Test
    fun `distance grows as signal weakens`() {
        val close = estimatedDistanceMeters(-50)
        val far = estimatedDistanceMeters(-80)
        assertTrue("weaker signal should estimate further away", far > close)
    }

    @Test
    fun `proximityFor labels a very strong signal as very close`() {
        val (label, bars) = proximityFor(-40)
        assertEquals("Very close", label)
        assertEquals(5, bars)
    }

    @Test
    fun `proximityFor labels a very weak signal as very far`() {
        val (label, bars) = proximityFor(-95)
        assertEquals("Very far", label)
        assertEquals(1, bars)
    }

    @Test
    fun `proximityRadiusFraction is clamped between 0 and 1`() {
        assertEquals(0f, proximityRadiusFraction(-20), 0.001f)
        assertEquals(1f, proximityRadiusFraction(-120), 0.001f)
    }

    @Test
    fun `lerpAngleDegrees moves toward the target by the given fraction`() {
        val result = lerpAngleDegrees(from = 0f, to = 100f, t = 0.5f)
        assertEquals(50f, result, 0.01f)
    }

    @Test
    fun `lerpAngleDegrees takes the shortest path across the 0-360 wraparound`() {
        // From 350 degrees to 10 degrees is a 20-degree step forward (through 0), not backward.
        val result = lerpAngleDegrees(from = 350f, to = 10f, t = 1f)
        assertEquals(10f, result, 0.01f)
    }

    @Test
    fun `lerpAngleDegrees with t=0 stays at the starting angle`() {
        val result = lerpAngleDegrees(from = 45f, to = 200f, t = 0f)
        assertEquals(45f, result, 0.01f)
    }
}
