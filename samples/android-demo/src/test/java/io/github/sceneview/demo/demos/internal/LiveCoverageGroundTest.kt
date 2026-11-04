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

    private val voxel = DenseFusion.VOXEL_M

    /** Surfels of a full floor cell at [voxel]: (0.25 / 0.02)². */
    private val fullCell = 156

    /**
     * A dense cloud over the 5 × 5 cells from the origin: [floorPerCell] floor surfels in each, at
     * [floorY], [level]-coarse, and [extra] (x, y, z, level) on top.
     */
    private fun cloud(
        floorY: Float = 0f,
        extra: List<FloatArray> = emptyList(),
        floorPerCell: (Int, Int) -> Pair<Int, Int> = { _, _ -> fullCell to 0 },
    ): DenseCloud {
        val points = ArrayList<FloatArray>()
        for (c in 0 until 5) for (r in 0 until 5) {
            val (n, level) = floorPerCell(c, r)
            repeat(n) { i -> points += floatArrayOf((c + (i % 12 + 0.5f) / 12f) * cell, floorY, (r + (i / 12 % 12 + 0.5f) / 12f) * cell, level.toFloat()) }
        }
        points += extra
        return DenseCloud(
            FloatArray(points.size * 3) { points[it / 3][it % 3] },
            IntArray(points.size),
            levels = ByteArray(points.size) { points[it][3].toInt().toByte() },
        )
    }

    private val fiveByFive = LongArray(25) { CoverageGrid.key(it % 5, it / 5) }

    private fun weak(cloud: DenseCloud, floorY: Float = 0f, covered: LongArray = fiveByFive) =
        LiveCoverageGround.weakCells(cloud, voxel, covered, cell, floorY).toList()

    @Test
    fun aCoveredCellWithLittleFloorIsToRedo() {
        val thin = cloud { c, r -> (if (c == 2 && r == 2) 30 else fullCell) to 0 }
        assertEquals(listOf(CoverageGrid.key(2, 2)), weak(thin))
    }

    @Test
    fun anEvenScanHasNothingToRedo() {
        assertEquals(emptyList<Long>(), weak(cloud()))
    }

    @Test
    fun aFarCellsMergedSurfelsCountForTheFloorTheyStandFor() {
        // 9 surfels 8 cm wide cover the cell as 156 of 2 cm do: a point count called it weak.
        val merged = List(9) { floatArrayOf((2 + 0.16f + 0.32f * (it % 3)) * cell, 0f, (2 + 0.16f + 0.32f * (it / 3)) * cell, 2f) }
        val far = cloud(extra = merged) { c, r -> (if (c == 2 && r == 2) 0 else fullCell) to 0 }
        assertEquals(emptyList<Long>(), weak(far))
        // One 16 cm surfel is 0.41 of a cell, whole or not: too coarse to judge.
        val coarse = cloud(extra = listOf(floatArrayOf(2.5f * cell, 0f, 2.5f * cell, 3f))) { c, r -> (if (c == 2 && r == 2) 0 else fullCell) to 0 }
        assertEquals(emptyList<Long>(), weak(coarse))
    }

    @Test
    fun theWallsAboveAThinFloorDoNotHideIt() {
        // A shelf's foot: what stands in a cell is not its floor, and the floor is not judged.
        val shelf = List(400) { floatArrayOf(2.5f * cell, 0.3f + it * 0.004f, 2.5f * cell, 0f) }
        val thin = cloud(extra = shelf) { c, r -> (if (c == 2 && r == 2) 30 else fullCell) to 0 }
        assertEquals(emptyList<Long>(), weak(thin))
        // The ceiling is not something standing: a thin floor under it is still to redo.
        val ceiling = List(400) { floatArrayOf(2.5f * cell, 2.5f, 2.5f * cell, 0f) }
        val under = cloud(extra = ceiling) { c, r -> (if (c == 2 && r == 2) 30 else fullCell) to 0 }
        assertEquals(listOf(CoverageGrid.key(2, 2)), weak(under))
    }

    @Test
    fun theScansEdgeIsNotJudged() {
        val edge = cloud { c, r -> (if (c == 0 && r == 2) 10 else fullCell) to 0 }
        assertEquals(emptyList<Long>(), weak(edge))
    }

    @Test
    fun theFloorIsFoundNearWhereItIsThought() {
        // A floor plane's guess 12 cm off: the band follows the floor the cloud holds.
        val low = cloud(floorY = -1.32f) { c, r -> (if (c == 2 && r == 2) 30 else fullCell) to 0 }
        assertEquals(-1.32f, LiveCoverageGround.floorOf(low, voxel, -1.2f), voxel)
        assertEquals(listOf(CoverageGrid.key(2, 2)), weak(low, floorY = -1.2f))
    }

    /**
     * Full-detail floor surfels, one per voxel square, over [columns] × [rows] cells from the
     * origin at the height [y] gives, [share] of each cell's squares kept (by row), plus [extra].
     */
    private fun floor(
        columns: Int,
        rows: Int,
        y: (Float, Float) -> Float = { _, _ -> 0f },
        extra: List<FloatArray> = emptyList(),
        share: (Int, Int) -> Float = { _, _ -> 1f },
    ): DenseCloud {
        val points = ArrayList<FloatArray>()
        val per = 12
        for (c in 0 until columns) for (r in 0 until rows) {
            val keep = (share(c, r) * per).toInt()
            for (a in 0 until per) for (b in 0 until keep) {
                val x = (c + (a + 0.5f) / per) * cell
                val z = (r + (b + 0.5f) / per) * cell
                points += floatArrayOf(x, y(x, z), z, 0f)
            }
        }
        points += extra
        return DenseCloud(
            FloatArray(points.size * 3) { points[it / 3][it % 3] },
            IntArray(points.size),
            levels = ByteArray(points.size) { points[it][3].toInt().toByte() },
        )
    }

    private fun keys(columns: Int, rows: Int) = LongArray(columns * rows) { CoverageGrid.key(it % columns, it / columns) }

    @Test
    fun aWideLowSeatIsNotTheFloor() {
        // A sofa's seat at 42 cm wider than the floor seen round it: the floor is the lowest wide
        // flat thing, so the floor stays judged and whole.
        val seat = List(6000) { floatArrayOf(3f + (it % 80) * 0.02f, 0.42f, (it / 80) * 0.02f, 0f) }
        val room = floor(5, 5, extra = seat)
        assertEquals(0f, LiveCoverageGround.floorOf(room, voxel, 0.2f), voxel)
        assertEquals(emptyList<Long>(), weak(room, floorY = 0.2f))
    }

    @Test
    fun aSlopeKeepsItsFloorBand() {
        // 6 m at 3 %: 18 cm from one end to the other, past one band; one thin cell at the top.
        val ramp = floor(24, 3, y = { x, _ -> 0.03f * x }) { c, r -> if (c == 21 && r == 1) 0.2f else 1f }
        assertEquals(listOf(CoverageGrid.key(21, 1)), LiveCoverageGround.weakCells(ramp, voxel, keys(24, 3), cell, 0f).toList())
    }

    @Test
    fun aStepDownKeepsBothFloors() {
        // A sunken half 20 cm down, its floor seen whole but one thin cell, the step's own cells left alone.
        val step = floor(16, 3, y = { x, _ -> if (x < 2f) 0f else -0.2f }) { c, r -> if (c == 13 && r == 1) 0.2f else 1f }
        assertEquals(listOf(CoverageGrid.key(13, 1)), LiveCoverageGround.weakCells(step, voxel, keys(16, 3), cell, 0f).toList())
    }

    @Test
    fun tooLittleScannedToJudge() {
        val three = LongArray(3) { CoverageGrid.key(it, 0) }
        assertEquals(emptyList<Long>(), weak(cloud { _, _ -> 1 to 0 }, covered = three))
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
