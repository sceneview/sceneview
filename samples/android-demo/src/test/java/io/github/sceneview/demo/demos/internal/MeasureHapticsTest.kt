package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The haptic of every Measure moment: the ones Measure always played, and the two openings add. */
class MeasureHapticsTest {

    @Test
    fun `every moment keeps its haptic`() {
        // The calls the screen made inline before the mapping was pulled out.
        assertEquals(MeasureHaptic.Selection, measureHaptic(MeasureHapticEvent.AimLocked))
        assertEquals(MeasureHaptic.Light, measureHaptic(MeasureHapticEvent.PointAdded))
        assertEquals(MeasureHaptic.Success, measureHaptic(MeasureHapticEvent.OutlineClosed))
        assertEquals(MeasureHaptic.Warning, measureHaptic(MeasureHapticEvent.Refused))
        assertEquals(MeasureHaptic.Success, measureHaptic(MeasureHapticEvent.HeightLocked))
        assertEquals(MeasureHaptic.Success, measureHaptic(MeasureHapticEvent.ChainEnded))
        assertEquals(MeasureHaptic.Selection, measureHaptic(MeasureHapticEvent.ModeChanged))
    }

    @Test
    fun `openings answer like points and closings`() {
        assertEquals(MeasureHaptic.Light, measureHaptic(MeasureHapticEvent.OpeningCorner))
        assertEquals(MeasureHaptic.Success, measureHaptic(MeasureHapticEvent.OpeningAdded))
    }

    @Test
    fun `the aim ticks on every lock and on the first point`() {
        for (kind in MeasureSnapKind.entries) {
            val expected = if (kind == MeasureSnapKind.None) null else MeasureHapticEvent.AimLocked
            assertEquals(kind.name, expected, measureAimHapticEvent(kind, closes = false))
            assertEquals(kind.name, MeasureHapticEvent.AimLocked, measureAimHapticEvent(kind, closes = true))
        }
        assertNull(measureAimHapticEvent(null, null))
        assertNull(measureAimHapticEvent(null, closes = false))
        assertEquals(MeasureHapticEvent.AimLocked, measureAimHapticEvent(null, closes = true))
    }

    @Test
    fun `the aim stays silent while the plan covers the camera`() {
        for (kind in MeasureSnapKind.entries) {
            assertNull(kind.name, measureAimHapticEvent(kind, closes = false, covered = true))
            assertNull(kind.name, measureAimHapticEvent(kind, closes = true, covered = true))
        }
        assertNull(measureAimHapticEvent(null, closes = true, covered = true))
    }

    @Test
    fun `a placed point is light, a closing one a success`() {
        assertEquals(MeasureHapticEvent.PointAdded, measurePointHapticEvent(closes = false))
        assertEquals(MeasureHapticEvent.OutlineClosed, measurePointHapticEvent(closes = true))
    }
}
