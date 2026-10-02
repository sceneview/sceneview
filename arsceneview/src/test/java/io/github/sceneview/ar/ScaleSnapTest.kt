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

    @Test
    fun pinch_isOneToOneWithTheFingers_relativeToTheStart() {
        assertEquals(2f, ScaleSnap.pinchRaw(startScale = 1f, startSpan = 200f, currentSpan = 400f), 1e-6f)
        assertEquals(0.75f, ScaleSnap.pinchRaw(startScale = 1.5f, startSpan = 300f, currentSpan = 150f), 1e-6f)
    }

    @Test
    fun pinch_isPathIndependent_noCompounding() {
        // A jittery pinch that ends where it started lands exactly on the start size — the
        // old per-event multiplication of a cumulative factor compounded here.
        var last = 0f
        for (span in listOf(200f, 230f, 190f, 260f, 240f, 205f, 200f)) {
            last = ScaleSnap.pinchRaw(startScale = 1.3f, startSpan = 200f, currentSpan = span)
        }
        assertEquals(1.3f, last, 1e-6f)
    }

    @Test
    fun pinch_degenerateSpans_keepTheStartScale() {
        val spans = listOf(0f to 100f, 100f to 0f, -1f to 100f, Float.NaN to 100f, 100f to Float.POSITIVE_INFINITY)
        for ((a, b) in spans) {
            assertEquals("$a→$b", 1.2f, ScaleSnap.pinchRaw(1.2f, a, b))
        }
    }

    @Test
    fun doubleTap_resetsAResizedObject_thenTogglesBack() {
        assertEquals(1f, ScaleSnap.doubleTapTarget(current = 2.4f, remembered = null))
        assertEquals(1f, ScaleSnap.doubleTapTarget(current = 0.5f, remembered = 2.4f))
        assertEquals(2.4f, ScaleSnap.doubleTapTarget(current = 1f, remembered = 2.4f))
        assertEquals("clamped", ScaleSnap.MAX, ScaleSnap.doubleTapTarget(current = 1f, remembered = 9f))
        assertEquals("never resized: acknowledge only", null, ScaleSnap.doubleTapTarget(1f, remembered = null))
        assertEquals(null, ScaleSnap.doubleTapTarget(current = 1f, remembered = 1f))
    }

    @Test
    fun doubleTap_animation_easesOutAndLandsExactly() {
        assertEquals(1f, ScaleSnap.doubleTapProgress(1f, 2f, 0f), 1e-6f)
        val half = ScaleSnap.doubleTapProgress(1f, 2f, ScaleSnap.DOUBLE_TAP_MS / 2f)
        assertTrue("ease-out: past halfway at half time, was $half", half > 1.5f && half < 2f)
        assertEquals(2f, ScaleSnap.doubleTapProgress(1f, 2f, ScaleSnap.DOUBLE_TAP_MS.toFloat()))
        var previous = 1f
        var t = 0f
        while (t <= ScaleSnap.DOUBLE_TAP_MS) {
            val v = ScaleSnap.doubleTapProgress(1f, 2f, t)
            assertTrue("monotonic at $t", v >= previous)
            previous = v
            t += 5f
        }
    }

    @Test
    fun selectionRing_enclosesTheFootprintWithAMargin() {
        val corners = listOf(
            io.github.sceneview.math.Position(-0.3f, 0f, -0.1f),
            io.github.sceneview.math.Position(0.5f, 0f, -0.1f),
            io.github.sceneview.math.Position(-0.3f, 0.8f, 0.5f),
            io.github.sceneview.math.Position(0.5f, 0.8f, 0.5f),
        )
        val ring = SelectionRing.footprint(corners)
        assertEquals(0.1f, ring.centerX, 1e-6f)
        assertEquals(0.2f, ring.centerZ, 1e-6f)
        assertEquals(0.5f * (1f + SelectionRing.MARGIN), ring.radius, 1e-5f)
        assertEquals(0f, SelectionRing.footprint(emptyList()).radius)
        assertEquals(0.0018f, SelectionRing.tubeRadius(0.05f), 1e-7f)
        assertEquals(0.006f, SelectionRing.tubeRadius(3f), 1e-7f)
    }
}
