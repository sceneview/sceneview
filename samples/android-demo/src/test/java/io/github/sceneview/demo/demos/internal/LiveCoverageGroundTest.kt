package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live 3D map's floor: what is covered, and the rest of the room's floor still to scan. */
class LiveCoverageGroundTest {
    private val cell = 0.25f

    /** A 2 × 2 m square floor from the origin, counter-clockwise. */
    private val square = listOf(0f to 0f, 2f to 0f, 2f to 2f, 0f to 2f)

    @Test
    fun nothingCoveredNorFoundIsNoGround() {
        assertNull(LiveCoverageGround.of(LongArray(0), cell, emptyList(), 0f))
    }

    @Test
    fun theRoomNotCoveredIsAllMissing() {
        val ground = requireNotNull(LiveCoverageGround.of(LongArray(0), cell, square, -1.2f))
        assertEquals(8, ground.columns)
        assertEquals(8, ground.rows)
        assertEquals(64, ground.missing)
        assertEquals(0, ground.seen)
        assertEquals(-1.2f, ground.floorY, 0f)
    }

    @Test
    fun coveredCellsAreSeenAndTheRestOfTheRoomMissing() {
        // The first row of the room covered, eight cells along X.
        val covered = LongArray(8) { CoverageGrid.key(it, 0) }
        val ground = requireNotNull(LiveCoverageGround.of(covered, cell, square, 0f))
        assertEquals(8, ground.seen)
        assertEquals(56, ground.missing)
        assertEquals(ScanGround.SEEN, ground.at(3, 0))
        assertEquals(ScanGround.MISSING, ground.at(3, 1))
        assertEquals(0f, ground.originX, 1e-6f)
        assertEquals(0f, ground.originZ, 1e-6f)
    }

    @Test
    fun aCellCoveredOutsideTheRoomIsSeenAndWidensTheGround() {
        val covered = longArrayOf(CoverageGrid.key(-2, 0))
        val ground = requireNotNull(LiveCoverageGround.of(covered, cell, square, 0f))
        assertEquals(10, ground.columns)
        assertEquals(ScanGround.SEEN, ground.at(0, 0))
        // The cell between it and the room is neither covered nor in the room.
        assertEquals(ScanGround.OUTSIDE, ground.at(1, 0))
    }

    @Test
    fun aRunawayExtentIsCut() {
        val huge = listOf(0f to 0f, 500f to 0f, 500f to 1f, 0f to 1f)
        val ground = requireNotNull(LiveCoverageGround.of(LongArray(0), cell, huge, 0f))
        assertEquals(LiveCoverageGround.MAX_CELLS_PER_SIDE, ground.columns)
    }

    @Test
    fun containsFollowsTheOutline() {
        val triangle = listOf(0f to 0f, 2f to 0f, 0f to 2f)
        assertTrue(LiveCoverageGround.contains(triangle, 0.5f, 0.5f))
        assertFalse(LiveCoverageGround.contains(triangle, 1.5f, 1.5f))
    }

    @Test
    fun onlyTheLiveMapHatchesTheFloorStillToScan() {
        // Half the room covered: the result draws its slab whole, the live map hatches the rest.
        val covered = LongArray(32) { CoverageGrid.key(it % 8, it / 8) }
        val ground = requireNotNull(LiveCoverageGround.of(covered, cell, square, 0f))
        val result = ScanGroundMeshes.of(ground)
        val live = ScanGroundMeshes.of(ground, hatchMissing = true)
        assertEquals(0, result.missingFill.triangleCount)
        assertEquals(0, result.hatch.triangleCount)
        // One inset tile and one diagonal ribbon, two triangles each, per missing cell.
        assertEquals(2 * 32, live.missingFill.triangleCount)
        assertEquals(2 * 32, live.hatch.triangleCount)
        assertEquals(result.slab.triangleCount, live.slab.triangleCount)
    }

    /** [perCell] dense points at the centre of each (column, row) cell of [cell] metres. */
    private fun points(cells: List<Pair<Int, Int>>, perCell: (Int, Int) -> Int): FloatArray {
        val out = ArrayList<Float>()
        for ((c, r) in cells) repeat(perCell(c, r)) {
            out += (c + 0.5f) * cell
            out += 0f
            out += (r + 0.5f) * cell
        }
        return out.toFloatArray()
    }

    private val threeByThree = (0 until 9).map { it % 3 to it / 3 }

    @Test
    fun aCoveredCellWithFewPointsIsToRedo() {
        val covered = threeByThree.map { (c, r) -> CoverageGrid.key(c, r) }.toLongArray()
        val cloud = points(threeByThree) { c, r -> if (c == 1 && r == 1) 2 else 20 }
        val weak = LiveCoverageGround.weakCells(cloud, covered, cell)
        assertEquals(listOf(CoverageGrid.key(1, 1)), weak.toList())
    }

    @Test
    fun anEvenScanHasNothingToRedo() {
        val covered = threeByThree.map { (c, r) -> CoverageGrid.key(c, r) }.toLongArray()
        val cloud = points(threeByThree) { _, _ -> 12 }
        assertEquals(0, LiveCoverageGround.weakCells(cloud, covered, cell).size)
    }

    @Test
    fun tooLittleScannedToJudge() {
        // Two cells, or a sparse cloud: nothing is called weak on so little.
        val two = listOf(0 to 0, 1 to 0)
        val covered = two.map { (c, r) -> CoverageGrid.key(c, r) }.toLongArray()
        assertEquals(0, LiveCoverageGround.weakCells(points(two) { c, _ -> if (c == 0) 1 else 30 }, covered, cell).size)
        val all = threeByThree.map { (c, r) -> CoverageGrid.key(c, r) }.toLongArray()
        val sparse = points(threeByThree) { c, _ -> if (c == 0) 0 else 2 }
        assertEquals(0, LiveCoverageGround.weakCells(sparse, all, cell).size)
    }

    @Test
    fun aWeakCellIsCoveredButToRedo() {
        val covered = LongArray(8) { CoverageGrid.key(it, 0) }
        val ground = requireNotNull(
            LiveCoverageGround.of(covered, cell, square, 0f, weak = longArrayOf(CoverageGrid.key(2, 0))),
        )
        assertEquals(ScanGround.WEAK, ground.at(2, 0))
        assertEquals(ScanGround.SEEN, ground.at(3, 0))
        // Still covered: it counts toward the share scanned.
        assertEquals(8, ground.seen)
        assertEquals(1, ground.weak)
        // A weak cell outside the covered ones is not drawn as one.
        val stray = requireNotNull(LiveCoverageGround.of(covered, cell, square, 0f, weak = longArrayOf(CoverageGrid.key(4, 4))))
        assertEquals(ScanGround.MISSING, stray.at(4, 4))
    }

    @Test
    fun theLiveMapTintsWhatIsDoneAndWhatIsToRedo() {
        val covered = LongArray(8) { CoverageGrid.key(it, 0) }
        val ground = requireNotNull(
            LiveCoverageGround.of(covered, cell, square, 0f, weak = longArrayOf(CoverageGrid.key(2, 0))),
        )
        val live = ScanGroundMeshes.of(ground, hatchMissing = true)
        assertEquals(2 * 7, live.seenFill.triangleCount)
        assertEquals(2 * 1, live.weakFill.triangleCount)
        // No hatch on a cell to redo: the hatch is for the floor not scanned at all.
        assertEquals(2 * 56, live.hatch.triangleCount)
        // The result keeps its slab plain.
        val result = ScanGroundMeshes.of(ground)
        assertEquals(0, result.seenFill.triangleCount)
        assertEquals(0, result.weakFill.triangleCount)
    }
}
