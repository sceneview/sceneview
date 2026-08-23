package io.github.sceneview.demo.demos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [clampedPanelScale] — the perspective-compensated scale behind
 * Point & Ask's anchored answer card (#3276). Pins that the card grows as the camera
 * moves away (staying legible across a room) and never shrinks below/balloons above its
 * clamped band.
 */
class PanelScaleTest {

    @Test
    fun `at the reference distance the scale is exactly the base panel scale`() {
        // PANEL_REFERENCE_DISTANCE = 1.0f, PANEL_SCALE = 0.15f — see PointAndAskDemo.kt.
        assertEquals(0.15f, clampedPanelScale(1.0f), 1e-4f)
    }

    @Test
    fun `farther away scales the card up so it stays legible`() {
        val near = clampedPanelScale(1.0f)
        val far = clampedPanelScale(2.0f)
        assertTrue("a farther card must render larger, not smaller", far > near)
        // Linear ramp: double the distance must double the scale, before any clamping.
        assertEquals(near * 2f, far, 1e-4f)
    }

    @Test
    fun `distance below the near clamp does not shrink the card further`() {
        val atClamp = clampedPanelScale(0.6f) // PANEL_MIN_READABLE_DISTANCE
        val closer = clampedPanelScale(0.1f)
        assertEquals(
            "below the near clamp the scale must floor out, not keep shrinking",
            atClamp,
            closer,
            1e-4f,
        )
    }

    @Test
    fun `distance beyond the far clamp does not balloon the card further`() {
        val atClamp = clampedPanelScale(3.5f) // PANEL_MAX_READABLE_DISTANCE
        val farther = clampedPanelScale(50f)
        assertEquals(
            "beyond the far clamp the scale must ceiling out, not keep growing",
            atClamp,
            farther,
            1e-4f,
        )
    }

    @Test
    fun `scale is always positive for any non-negative distance`() {
        assertTrue(clampedPanelScale(0f) > 0f)
        assertTrue(clampedPanelScale(1_000f) > 0f)
    }
}
