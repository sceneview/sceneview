package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live model's guidance names an empty area only when it is worth going back for: wide
 * enough, and surrounded by floor already scanned (2026-11-14, the first hand-held scan: the
 * line named notches of the model's outline for 55 s on end).
 */
class ScanLiveGapsTest {

    private val cell = 0.25f

    /** A [side] × [side] square of cells, all scanned but [missing], whose hull is the whole square. */
    private fun ground(side: Int, missing: Set<Pair<Int, Int>>): ScanGround {
        val keys = ArrayList<Long>()
        for (cx in 0 until side) for (cz in 0 until side) if ((cx to cz) !in missing) keys += CoverageGrid.key(cx, cz)
        val far = side * cell
        return LiveCoverageGround.of(keys.toLongArray(), cell, listOf(0f to 0f, far to 0f, far to far, 0f to far), 0f)!!
    }

    private fun block(columns: IntRange, rows: IntRange) = columns.flatMap { c -> rows.map { r -> c to r } }.toSet()

    @Test
    fun aHoleInTheMiddleOfScannedFloorIsNamed() {
        // 3 × 3 cells, 0.56 m², with scanned floor all round.
        val gaps = ScanLiveGaps.of(ground(9, block(3..5, 3..5)))
        assertEquals(1, gaps.size)
        assertEquals(9 * cell * cell, gaps[0].area, 1e-4f)
        assertEquals(listOf(0.75f, 0.75f, 1.5f, 1.5f), gaps[0].bounds.toList())
        // Aimed at its own middle cell.
        assertEquals(1.125f, gaps[0].aim.first, 1e-4f)
        assertEquals(1.125f, gaps[0].aim.second, 1e-4f)
    }

    @Test
    fun aSmallHoleIsNotWorthALine() {
        // 2 × 2 cells, 0.25 m²: filled in passing.
        assertTrue(ScanLiveGaps.of(ground(9, block(3..4, 3..4))).isEmpty())
    }

    @Test
    fun aSliverAlongTheOutlineIsNotNamed() {
        // Two cells deep along a whole side: 1.1 m², but half its edge is the model's outline.
        assertTrue(ScanLiveGaps.of(ground(9, block(0..8, 0..1))).isEmpty())
        // A shallow notch of the outline, wider than deep.
        assertTrue(ScanLiveGaps.of(ground(9, block(2..7, 0..1))).isEmpty())
    }

    @Test
    fun aBayTheScanSurroundsOnThreeSidesIsNamed() {
        // Three cells wide, four deep, open on the outline by its narrow end.
        assertEquals(1, ScanLiveGaps.of(ground(9, block(3..5, 0..3))).size)
    }

    @Test
    fun eachPieceIsJudgedOnItsOwn() {
        // A notch of the outline and a real hole in the same floor: the hole alone is named.
        val gaps = ScanLiveGaps.of(ground(12, block(2..7, 0..1) + block(5..7, 6..8)))
        assertEquals(1, gaps.size)
        assertEquals(listOf(1.25f, 1.5f, 2f, 2.25f), gaps[0].bounds.toList())
    }

    @Test
    fun theAimLiesOnTheGapItselfEvenWhenItIsLShaped() {
        // An L of missing cells: (1..6, 1) and (1, 2..6); its bounds' middle is scanned floor.
        val missing = block(1..6, 1..1) + block(1..1, 2..6)
        val gap = ScanLiveGaps.of(ground(9, missing)).single()
        val at = (gap.aim.first / cell).toInt() to (gap.aim.second / cell).toInt()
        assertTrue(at in missing)
    }
}
