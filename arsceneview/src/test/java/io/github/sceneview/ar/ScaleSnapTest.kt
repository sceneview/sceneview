package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScaleSnapTest {

    @Test
    fun insideTheWindow_snapsToExactly100Percent() {
        for (raw in listOf(0.965f, 0.99f, 1.0f, 1.03f, 1.039f)) {
            val step = ScaleSnap.step(previousDisplayed = 1.5f, raw = raw)
            assertEquals("$raw", 1f, step.displayed)
            assertTrue(step.snapped)
        }
    }

    @Test
    fun outsideTheWindow_followsThePinch() {
        val step = ScaleSnap.step(previousDisplayed = 1f, raw = 1.05f)
        assertEquals(1.05f, step.displayed)
        assertFalse(step.snapped)
        assertFalse(step.enteredSnap)
    }

    @Test
    fun enteringTheDetent_firesOnce() {
        assertTrue(ScaleSnap.step(previousDisplayed = 1.2f, raw = 1.02f).enteredSnap)
        assertTrue(ScaleSnap.step(previousDisplayed = 0.8f, raw = 0.97f).enteredSnap)
        assertFalse("staying in the detent", ScaleSnap.step(previousDisplayed = 1f, raw = 1.01f).enteredSnap)
    }

    @Test
    fun limits_clampAndFireOnEntryOnly() {
        val max = ScaleSnap.step(previousDisplayed = 3.5f, raw = 9f)
        assertEquals(4f, max.displayed)
        assertTrue(max.enteredLimit)
        assertFalse(ScaleSnap.step(previousDisplayed = 4f, raw = 5f).enteredLimit)
        val min = ScaleSnap.step(previousDisplayed = 0.3f, raw = 0.1f)
        assertEquals(0.25f, min.displayed)
        assertTrue(min.enteredLimit)
        assertFalse(ScaleSnap.step(previousDisplayed = 0.3f, raw = 0.28f).enteredLimit)
    }

    @Test
    fun rebound_startsAndEndsOnExactlyOne() {
        assertEquals(1f, ScaleSnap.rebound(0f, fromAbove = true))
        assertEquals(1f, ScaleSnap.rebound(ScaleSnap.REBOUND_MS.toFloat(), fromAbove = true))
        assertEquals(1f, ScaleSnap.rebound(10_000f, fromAbove = false))
    }

    @Test
    fun rebound_continuesThePinchDirection_thenSettles() {
        // A quarter period in: the overshoot peak.
        val quarter = ScaleSnap.REBOUND_PERIOD_MS / 4f
        assertTrue("shrinking into 100 % dips below", ScaleSnap.rebound(quarter, fromAbove = true) < 1f)
        assertTrue("growing into 100 % overshoots", ScaleSnap.rebound(quarter, fromAbove = false) > 1f)
        var peak = 0f
        var t = 0f
        while (t < ScaleSnap.REBOUND_MS) {
            peak = maxOf(peak, abs(ScaleSnap.rebound(t, fromAbove = false) - 1f))
            t += 1f
        }
        assertTrue("small: at most the amplitude, was $peak", peak <= ScaleSnap.REBOUND_AMPLITUDE)
        assertTrue("visible: at least 2 %, was $peak", peak >= 0.02f)
        val late = abs(ScaleSnap.rebound(ScaleSnap.REBOUND_MS - 1f, fromAbove = false) - 1f)
        assertTrue("settled before the cut, was $late", late < 0.003f)
    }
}
