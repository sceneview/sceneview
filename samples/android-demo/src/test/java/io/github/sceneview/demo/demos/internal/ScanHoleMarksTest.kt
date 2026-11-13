package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The live model's floor marks: every gap hatched quietly, only the gap the guidance names
 * hatched brightly (2026-11-13, the review of u25).
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
    fun everyGapIsQuietWithoutATarget() {
        val marks = ScanHoleMarks.of(ringGround(), null)
        assertEquals(4 * 2, marks.faint.triangleCount)
        assertEquals(0, marks.strong.triangleCount)
    }

    @Test
    fun theNamedGapIsBright() {
        val marks = ScanHoleMarks.of(ringGround(), floatArrayOf(0.5f, 0.5f, 1f, 1f))
        assertEquals(0, marks.faint.triangleCount)
        assertEquals(4 * 2, marks.strong.triangleCount)
    }
}
