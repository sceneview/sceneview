package io.github.sceneview.demo.common.placement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the tap-to-place interaction core
 * ([#3326](https://github.com/sceneview/sceneview/issues/3326)).
 *
 * The AR emulator produces no ARCore tracking, so none of this behaviour can be exercised
 * on CI through the UI. Everything that *can* be decided without a camera therefore lives
 * in [PlacementScale] / [PlacementEntrance] / [placementCoaching] as pure functions, and
 * this is where the contract is pinned.
 */
class PlacementInteractionTest {

    // ── PlacementScale: the 100 % detent ────────────────────────────────────

    @Test
    fun `range is anchored to the model's own base scale`() {
        // The regression this replaces: a fixed 0.1f..10f band on the raw node scale, which
        // for any model whose fitted scale is below 0.1 rejected the first pinch event.
        val base = 0.004f // a glTF authored large — fitted scale far below the old floor
        val range = PlacementScale.rangeFor(base)
        assertEquals(base * 0.25f, range.start, 1e-9f)
        assertEquals(base * 4f, range.endInclusive, 1e-9f)

        // A pinch out from rest must actually move, at any base scale.
        val next = PlacementScale.next(current = base, base = base, rawFactor = 1.4f)
        assertNotEquals(base, next)
        assertTrue("a pinch out must grow the model", next > base)
    }

    @Test
    fun `a pinch that lands near real-world size snaps to exactly it`() {
        val base = 0.5f
        // Aim for ~103 % — inside the 6 % detent band.
        val next = PlacementScale.next(current = base * 1.06f, base = base, rawFactor = 1.0f)
        assertEquals(base, next, 1e-9f)
        assertTrue(PlacementScale.isRealWorldSize(next, base))
        assertEquals(100, PlacementScale.percent(next, base))
    }

    @Test
    fun `a deliberate resize outside the detent band is not fought`() {
        val base = 0.5f
        val outside = base * 1.5f
        assertEquals(outside, PlacementScale.snap(outside, base), 1e-9f)
        assertFalse(PlacementScale.isRealWorldSize(outside, base))
        assertEquals(150, PlacementScale.percent(outside, base))
    }

    @Test
    fun `scale is clamped to the quarter-to-four band`() {
        val base = 1f
        // Repeatedly pinching in must stop at 25 %, not run to zero.
        var scale = base
        repeat(200) { scale = PlacementScale.next(scale, base, rawFactor = 0.5f) }
        assertEquals(base * PlacementScale.MIN_FACTOR, scale, 1e-6f)

        // …and out must stop at 400 %.
        scale = base
        repeat(200) { scale = PlacementScale.next(scale, base, rawFactor = 2f) }
        assertEquals(base * PlacementScale.MAX_FACTOR, scale, 1e-6f)
    }

    @Test
    fun `sensitivity damps the per-event delta the same way the SDK does`() {
        val base = 1f
        // 1 + (1.4 - 1) * 0.5 = 1.2 — the NodeGestureDelegate formula, so the feel matches
        // the rest of the SDK instead of inventing a second curve.
        assertEquals(1.2f, PlacementScale.next(1f, base, rawFactor = 1.4f, sensitivity = 0.5f), 1e-6f)
        assertEquals(1.4f, PlacementScale.next(1f, base, rawFactor = 1.4f, sensitivity = 1.0f), 1e-6f)
    }

    @Test
    fun `a zero base scale can never divide by zero or move the model`() {
        // A model whose bounding box is degenerate leaves scaleToUnits a no-op.
        assertEquals(2f, PlacementScale.next(current = 2f, base = 0f, rawFactor = 1.5f), 1e-9f)
        assertEquals(100, PlacementScale.percent(scale = 2f, base = 0f))
        assertFalse(PlacementScale.isRealWorldSize(2f, 0f))
    }

    // ── PlacementScale: the detent haptic ───────────────────────────────────

    @Test
    fun `the detent haptic fires once on entry and never while resting inside it`() {
        assertTrue(PlacementScale.shouldTickHaptic(wasRealWorldSize = false, isRealWorldSize = true))
        assertFalse(PlacementScale.shouldTickHaptic(wasRealWorldSize = true, isRealWorldSize = true))
        assertFalse(PlacementScale.shouldTickHaptic(wasRealWorldSize = true, isRealWorldSize = false))
        assertFalse(PlacementScale.shouldTickHaptic(wasRealWorldSize = false, isRealWorldSize = false))
    }

    // ── PlacementEntrance ───────────────────────────────────────────────────

    @Test
    fun `the arrival animation starts small, ends at exactly full size, and never overshoots`() {
        assertEquals(PlacementEntrance.START_FRACTION, PlacementEntrance.scaleFraction(0f), 1e-6f)
        assertEquals(1f, PlacementEntrance.scaleFraction(1f), 1e-6f)

        var previous = -1f
        var t = 0f
        while (t <= 1f) {
            val f = PlacementEntrance.scaleFraction(t)
            assertTrue("must be monotonic — a model that shrinks mid-arrival reads as a glitch", f >= previous)
            assertTrue("must never overshoot: a physical-scale object bouncing reads as wrong size", f <= 1f)
            previous = f
            t += 0.05f
        }
    }

    @Test
    fun `the arrival animation clamps out-of-range progress instead of extrapolating`() {
        assertEquals(PlacementEntrance.START_FRACTION, PlacementEntrance.scaleFraction(-1f), 1e-6f)
        assertEquals(1f, PlacementEntrance.scaleFraction(5f), 1e-6f)
    }

    @Test
    fun `the arrival animation eases out`() {
        // Past the halfway point in time, it must be past the halfway point in scale —
        // that is what "fast out of the gate, settling" means, and it is the difference
        // between an arrival and a linear ramp.
        val half = PlacementEntrance.scaleFraction(0.5f)
        val midpoint = PlacementEntrance.START_FRACTION + (1f - PlacementEntrance.START_FRACTION) / 2f
        assertTrue(half > midpoint)
    }

    // ── Coaching: one line at a time ────────────────────────────────────────

    @Test
    fun `the plane discovery guide owns every pre-surface phase`() {
        // The three states where the guide is on screen must say nothing here, or the user
        // reads two pills making the same request in different words.
        listOf(
            TapToPlaceUxState.INITIALIZING,
            TapToPlaceUxState.TRACKING_LOST,
            TapToPlaceUxState.SCANNING,
        ).forEach { uxState ->
            assertNull(
                "$uxState belongs to PlaneDiscoveryGuide",
                placementCoaching(uxState, placedCount = 0, gestureHintVisible = false),
            )
        }
    }

    @Test
    fun `aiming asks the user to point, ready invites the tap`() {
        assertEquals(
            PlacementCoachingMessage.POINT_AT_SURFACE,
            placementCoaching(TapToPlaceUxState.AIMING, placedCount = 0, gestureHintVisible = false),
        )
        assertEquals(
            PlacementCoachingMessage.TAP_TO_PLACE,
            placementCoaching(TapToPlaceUxState.READY, placedCount = 0, gestureHintVisible = false),
        )
    }

    @Test
    fun `the screen goes quiet once something is placed and the hint has expired`() {
        assertNull(
            placementCoaching(TapToPlaceUxState.READY, placedCount = 1, gestureHintVisible = false),
        )
        assertNull(
            placementCoaching(TapToPlaceUxState.AIMING, placedCount = 3, gestureHintVisible = false),
        )
    }

    @Test
    fun `the gesture hint outranks the placement prompts while its window is open`() {
        assertEquals(
            PlacementCoachingMessage.GESTURE_HINT,
            placementCoaching(TapToPlaceUxState.READY, placedCount = 1, gestureHintVisible = true),
        )
        assertEquals(
            PlacementCoachingMessage.GESTURE_HINT,
            placementCoaching(TapToPlaceUxState.AIMING, placedCount = 1, gestureHintVisible = true),
        )
    }

    @Test
    fun `an open hint window cannot resurrect coaching over a lost camera`() {
        // The guide is showing "move your phone" over a black or drifting frame; a stale
        // hint window must not stack a second pill on top of it.
        assertNull(
            placementCoaching(
                TapToPlaceUxState.TRACKING_LOST,
                placedCount = 1,
                gestureHintVisible = true,
            ),
        )
    }
}
