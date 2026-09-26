package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The placement entrance curve shared by `AutoPlacementModel` and `AutoPlacementNode`. */
class PlacementEntranceTest {

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

    @Test
    fun `the exit is the motion-fade duration, the entrance a quarter second`() {
        assertEquals(260, PlacementEntrance.DURATION_MS)
        assertEquals(300, PlacementEntrance.EXIT_MS)
    }
}
