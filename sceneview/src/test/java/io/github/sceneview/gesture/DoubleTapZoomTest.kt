package io.github.sceneview.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins for the double-tap / two-finger-tap zoom step (#3608).
 *
 * The gesture is the one photo viewers and maps trained everyone on: a double-tap halves the
 * camera-to-subject distance, a two-finger tap doubles it back, and neither can push the eye onto
 * (or through) the orbit pivot — the failure mode #3403 cost the camera before the clamps existed.
 */
class DoubleTapZoomTest {

    private val factor =
        CameraGestureDetector.DefaultCameraManipulator.DEFAULT_DOUBLE_TAP_ZOOM_FACTOR
    private val minFactor =
        CameraGestureDetector.DefaultCameraManipulator.DEFAULT_MIN_ZOOM_DISTANCE_FACTOR
    private val maxFactor =
        CameraGestureDetector.DefaultCameraManipulator.DEFAULT_MAX_ZOOM_DISTANCE_FACTOR

    private fun tap(
        distance: Float,
        home: Float = 4f,
        zoomIn: Boolean = true,
        step: Float = factor,
    ) = doubleTapZoomedDistance(
        distance = distance,
        homeDistance = home,
        zoomIn = zoomIn,
        factor = step,
        minDistanceFactor = minFactor,
        maxDistanceFactor = maxFactor,
    )

    @Test
    fun `double tap halves the distance`() {
        assertEquals(2f, tap(distance = 4f), 1e-4f)
    }

    @Test
    fun `two finger tap doubles it back`() {
        assertEquals(4f, tap(distance = 2f, zoomIn = false), 1e-4f)
    }

    @Test
    fun `zoom in then out returns to the starting framing`() {
        val start = 3f
        assertEquals(start, tap(tap(start), zoomIn = false), 1e-4f)
    }

    /** The step is a ratio, so the gesture feels identical on a bee and on a landscape. */
    @Test
    fun `the step is scale invariant`() {
        assertEquals(0.5f, tap(0.05f, home = 0.1f) / 0.05f, 1e-4f)
        assertEquals(0.5f, tap(80f, home = 160f) / 80f, 1e-4f)
    }

    @Test
    fun `zooming in is clamped to the min distance`() {
        val home = 4f
        val min = home * minFactor
        var distance = home
        // Deliberately short of the dead-end escape: each tap halves, and the clamp catches it
        // before the "already at the minimum" branch can fire.
        repeat(3) { distance = tap(distance, home = home) }
        assertTrue("never crosses the pivot", distance >= min - 1e-5f)
        assertEquals(min, tap(min * 1.5f, home = home), 1e-4f)
    }

    @Test
    fun `zooming out is clamped to the max distance`() {
        val home = 4f
        var distance = home
        repeat(10) { distance = tap(distance, home = home, zoomIn = false) }
        assertEquals(home * maxFactor, distance, 1e-3f)
    }

    /** The dead end Google Photos does not have: a tap on the closest framing goes back home. */
    @Test
    fun `double tapping at the min distance cycles back to the home framing`() {
        val home = 4f
        assertEquals(home, tap(home * minFactor, home = home), 1e-4f)
    }

    @Test
    fun `a non positive or non finite distance falls back to the min clamp`() {
        val home = 4f
        val min = home * minFactor
        assertEquals(min, tap(0f, home = home), 1e-4f)
        assertEquals(min, tap(-1f, home = home), 1e-4f)
        assertEquals(min, tap(Float.NaN, home = home), 1e-4f)
    }

    @Test
    fun `an illegal factor falls back to the default`() {
        assertEquals(tap(4f), tap(4f, step = 1f), 1e-4f)
        assertEquals(tap(4f), tap(4f, step = 0f), 1e-4f)
        assertEquals(tap(4f), tap(4f, step = Float.NaN), 1e-4f)
    }

    @Test
    fun `an unusable home distance falls back to the current one`() {
        // No crash, no zero: the clamps are then relative to where the camera actually is.
        assertTrue(tap(4f, home = 0f) > 0f)
        assertTrue(tap(4f, home = Float.NaN) > 0f)
    }

    // ── Animation curve ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `the easing starts and ends where it should`() {
        assertEquals(0f, easeInOutCubic(0f), 1e-6f)
        assertEquals(0.5f, easeInOutCubic(0.5f), 1e-6f)
        assertEquals(1f, easeInOutCubic(1f), 1e-6f)
    }

    @Test
    fun `the easing is clamped and monotonic`() {
        assertEquals(0f, easeInOutCubic(-1f), 1e-6f)
        assertEquals(1f, easeInOutCubic(2f), 1e-6f)
        var previous = -1f
        for (i in 0..20) {
            val value = easeInOutCubic(i / 20f)
            assertTrue("monotonic at $i", value >= previous)
            previous = value
        }
    }

    @Test
    fun `the animation lands exactly on its end points`() {
        assertEquals(4f, animatedZoomDistance(4f, 2f, 0f), 1e-4f)
        assertEquals(2f, animatedZoomDistance(4f, 2f, 1f), 1e-4f)
    }

    /** Geometric, not linear: halfway through, the distance is the *geometric* mean. */
    @Test
    fun `the animation interpolates the ratio, not the difference`() {
        val mid = animatedZoomDistance(4f, 1f, 0.5f)
        assertEquals(2f, mid, 1e-3f)
        assertTrue("below the arithmetic mean", mid < 2.5f)
    }

    @Test
    fun `the animation never leaves the interval it was given`() {
        var previous = 4f
        for (i in 1..20) {
            val value = animatedZoomDistance(4f, 2f, i / 20f)
            assertTrue("inside [2, 4] at $i", value in 2f..4f)
            assertTrue("monotonic at $i", value <= previous + 1e-5f)
            previous = value
        }
    }

    @Test
    fun `degenerate animation endpoints degrade instead of producing NaN`() {
        assertEquals(2f, animatedZoomDistance(0f, 2f, 0.5f), 1e-4f)
        assertEquals(4f, animatedZoomDistance(4f, 0f, 0.5f), 1e-4f)
        assertEquals(4f, animatedZoomDistance(4f, Float.NaN, 0.5f), 1e-4f)
        assertTrue(animatedZoomDistance(4f, 2f, Float.NaN).isFinite())
    }
}
