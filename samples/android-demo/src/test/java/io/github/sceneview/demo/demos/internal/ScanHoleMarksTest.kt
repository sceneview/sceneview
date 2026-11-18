package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The live model's floor mark: the gap the guidance names alone is hatched (2026-11-13, the
 * review of u25 and intention.md: one indication of where to go).
 */
class ScanHoleMarksTest {

    private val cell = 0.25f

    /** A 6 × 6 ring of covered cells round a 2 × 2 gap at cells 2..3. */
    private fun ringGround(): ScanGround {
        val keys = ArrayList<Long>()
        for (cx in 0 until 6) for (cz in 0 until 6) {
            if (cx in 2..3 && cz in 2..3) continue
            keys += CoverageGrid.key(cx, cz)
        }
        val hull = listOf(0.125f to 0.125f, 1.375f to 0.125f, 1.375f to 1.375f, 0.125f to 1.375f)
        return LiveCoverageGround.of(keys.toLongArray(), cell, hull, 0f)!!
    }

    @Test
    fun nothingIsMarkedWithoutATarget() {
        assertEquals(0, ScanHoleMarks.of(ringGround(), null).triangleCount)
    }

    @Test
    fun theNamedGapAloneIsHatched() {
        assertEquals(4 * 2, ScanHoleMarks.of(ringGround(), floatArrayOf(0.5f, 0.5f, 1f, 1f)).triangleCount)
        // A target away from the gap marks nothing.
        assertEquals(0, ScanHoleMarks.of(ringGround(), floatArrayOf(0f, 0f, 0.2f, 0.2f)).triangleCount)
    }
}
