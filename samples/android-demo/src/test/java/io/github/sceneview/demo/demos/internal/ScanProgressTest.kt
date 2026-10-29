package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #4717: the live capture's progress never looks like it goes backwards or stops. */
class ScanProgressTest {

    @Test
    fun `the shown count only grows, whatever the raw count does`() {
        val count = MonotonicCount()
        assertEquals(100, count.update(100))
        // A compaction halves the map: nothing moves on screen.
        assertEquals(100, count.update(50))
        // What comes after adds on from there.
        assertEquals(130, count.update(80))
        assertEquals(130, count.update(-5))
        assertEquals(140, count.update(10))
        count.reset()
        assertEquals(0, count.shown)
    }

    @Test
    fun `nothing passing for a second relaxes the threshold in steps, never under the floor`() {
        val c = AdaptiveConfidence.dense()
        assertEquals(AdaptiveConfidence.DENSE_BASE.toFloat(), c.threshold)
        var t = 0L
        c.record(t, passed = 0)
        // Under a second: still waiting, the threshold holds.
        t += FRAME
        while (t < AdaptiveConfidence.STARVE_NANOS) {
            c.record(t, passed = 0)
            t += FRAME
        }
        assertEquals(AdaptiveConfidence.DENSE_BASE.toFloat(), c.threshold)
        // One second with nothing: the first step, and "move closer".
        c.record(AdaptiveConfidence.STARVE_NANOS, passed = 0)
        assertEquals((AdaptiveConfidence.DENSE_BASE - AdaptiveConfidence.DENSE_STEP).toFloat(), c.threshold)
        assertTrue(c.lowTexture)
        // Then one step every half second, down to the floor and no further.
        t = AdaptiveConfidence.STARVE_NANOS
        repeat(STEPS_PAST_FLOOR) {
            t += AdaptiveConfidence.STEP_NANOS
            c.record(t, passed = 0)
        }
        assertEquals(AdaptiveConfidence.DENSE_FLOOR.toFloat(), c.threshold)
        assertTrue(c.lowTexture)
    }

    @Test
    fun `a textured surface snaps the threshold back at once`() {
        val c = starvedToFloor(AdaptiveConfidence.dense())
        assertTrue(c.relaxed)
        val next = c.record(LATER, passed = AdaptiveConfidence.DENSE_MIN_PASSED, passedAtBase = AdaptiveConfidence.DENSE_MIN_PASSED)
        assertEquals(AdaptiveConfidence.DENSE_BASE.toFloat(), next)
        assertFalse(c.relaxed)
        assertFalse(c.lowTexture)
    }

    @Test
    fun `getting by on relaxed samples holds the threshold and keeps the hint`() {
        val c = starvedToFloor(AdaptiveConfidence.dense())
        // Plenty pass the relaxed threshold, none the base one: a plain wall, read close.
        repeat(STEPS_PAST_FLOOR) { i ->
            c.record(LATER + i * FRAME, passed = AdaptiveConfidence.DENSE_MIN_PASSED * 2, passedAtBase = 0)
        }
        assertEquals(AdaptiveConfidence.DENSE_FLOOR.toFloat(), c.threshold)
        assertTrue(c.lowTexture)
    }

    @Test
    fun `a gap between frames - a pause - starts the wait again`() {
        val c = AdaptiveConfidence.dense()
        c.record(0L, passed = 0)
        // Resumed two seconds later: not starving yet, the threshold holds.
        val resumed = 2 * AdaptiveConfidence.STARVE_NANOS
        c.record(resumed, passed = 0)
        c.record(resumed + FRAME, passed = 0)
        assertEquals(AdaptiveConfidence.DENSE_BASE.toFloat(), c.threshold)
        assertFalse(c.lowTexture)
    }

    @Test
    fun `the sparse threshold relaxes from ARCore's 0,2 to its floor and back`() {
        val c = starvedToFloor(AdaptiveConfidence.sparse())
        assertEquals(AdaptiveConfidence.SPARSE_FLOOR, c.threshold, EPS)
        c.record(LATER, passed = AdaptiveConfidence.SPARSE_MIN_PASSED)
        assertEquals(ArDebugTrace.MIN_POINT_CONFIDENCE, c.threshold, EPS)
    }

    @Test
    fun `the relaxed floors stay where noise is still filtered`() {
        // Raw depth confidence is 0..255: the floor keeps samples the sensor rates a quarter sure,
        // two views per voxel and the 1/d² weight do the rest; the reach is cut to 2.5 m meanwhile.
        assertEquals(64, AdaptiveConfidence.DENSE_FLOOR)
        assertTrue(AdaptiveConfidence.RELAXED_FAR_M < DepthBackProjection.FAR_M)
        assertEquals(0.1f, AdaptiveConfidence.SPARSE_FLOOR, EPS)
    }

    @Test
    fun `the covered area reads in m², a decimal under 10`() {
        assertEquals("0.0 m²", ScanAreaCopy.area(0f))
        assertEquals("0.4 m²", ScanAreaCopy.area(0.375f))
        assertEquals("7.5 m²", ScanAreaCopy.area(7.5f))
        assertEquals("10 m²", ScanAreaCopy.area(9.97f))
        assertEquals("38 m²", ScanAreaCopy.area(38.2f))
        assertEquals("0.0 m²", ScanAreaCopy.area(Float.NaN))
    }

    @Test
    fun `"move closer" is said after what the user cannot act on, before the rest`() {
        fun line(lost: Boolean = false, photos: Boolean = false, low: Boolean = false, detail: ScanDetail = ScanDetail.Fine) =
            ScanCopy.coaching(ScanPhase.Scanning, lost, photos, detail, coach = ScanCoach.Line.Slower, lowTexture = low)
        assertEquals(ScanCopy.TRACKING_LOST, line(lost = true, low = true))
        assertEquals(ScanCopy.FULL, line(photos = true, low = true))
        assertEquals(ScanCopy.COACH_LOW_TEXTURE, line(low = true, detail = ScanDetail.Adjusting))
        assertEquals(ScanCopy.COACH_SLOWER, line(detail = ScanDetail.Adjusting))
        assertEquals(ScanCopy.COACH_PAUSED, ScanCopy.coaching(ScanPhase.Paused, false, false, lowTexture = true))
    }

    @Test
    fun `a pose turned about the vertical keeps its place and its tilt`() {
        val pose = DebugPose(1f, 1.4f, -2f)
        val turned = pose.turnedBy(90f)
        assertEquals(pose.x, turned.x, EPS)
        assertEquals(pose.z, turned.z, EPS)
        // The forward axis (-z) now points along -x.
        val forward = turned.rotate(0f, 0f, -1f)
        assertEquals(-1f, forward.x, EPS)
        assertEquals(0f, forward.y, EPS)
        assertEquals(0f, forward.z, EPS)
    }

    private fun starvedToFloor(c: AdaptiveConfidence): AdaptiveConfidence {
        var t = 0L
        while (t <= STARVED) {
            c.record(t, passed = 0)
            t += FRAME
        }
        return c
    }

    private companion object {
        const val FRAME = 100_000_000L
        const val STEPS_PAST_FLOOR = 10
        const val STARVED = 8_000_000_000L
        const val LATER = STARVED + FRAME
        const val EPS = 1e-4f
    }
}
