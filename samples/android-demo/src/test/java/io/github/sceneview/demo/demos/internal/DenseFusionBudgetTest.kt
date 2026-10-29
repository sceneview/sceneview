package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor

/**
 * #4629: a depth scan's dense map stays within the phone's budget over a whole flat. Past it,
 * it merges the areas the camera has left into coarser points instead of dropping the new ones,
 * so every room keeps its surfaces and the count never stops at a cap.
 *
 * One synthetic walk ([ScanWalkthrough]: four furnished rooms, then back into the first, with
 * a sensor's depth noise) is fused three ways and shared by the tests: unbounded (the reference),
 * with the old fixed 500 k cap, and on the smallest phone budget.
 */
class DenseFusionBudgetTest {
    @Test
    fun `the old fixed cap is hit in the second room and drops every room after it`() {
        val fixed = Runs.fixed
        assertTrue("one room of real depth already fills half the old cap", Runs.unboundedRoomPoints[0] > 250_000)
        assertTrue(fixed.fusion.saturated)
        assertEquals(DenseFusion.MAX_POINTS, fixed.fusion.count)
        assertEquals("rooms 3 and 4 are lost", 0, fixed.roomCells[2] + fixed.roomCells[3])
    }

    @Test
    fun `the adaptive map keeps every room of a flat within its budget, and never fills up`() {
        val run = Runs.adaptive
        assertTrue("never past the budget: ${run.maxCount}", run.maxCount <= BUDGET)
        assertFalse("an ordinary flat never fills the map", run.fusion.saturated)
        assertTrue("it did have to make room", run.fusion.compactions > 0)
        val levels = run.fusion.levelCounts()
        assertTrue("the fine level stays the biggest: ${levels.joinToString()}", levels[0] == levels.max())
        for (room in 0 until ScanWalkthrough.ROOMS) {
            val kept = run.roomCells[room].toFloat() / Runs.unboundedRoomCells[room]
            assertTrue("room $room keeps ${(kept * 100).toInt()} % of its surface", kept >= MIN_SURFACE_KEPT)
        }
    }

    @Test
    fun `the surface the scan covers only grows, room after room`() {
        val cells = Runs.adaptive.cellsAtRoomEnds
        for (k in 1 until cells.size) {
            assertTrue("covered cells fell from ${cells[k - 1]} to ${cells[k]}", cells[k] >= cells[k - 1])
        }
        assertTrue(cells.last() > cells.first() * (ScanWalkthrough.ROOMS - 1))
    }

    @Test
    fun `the live count is the saved count, through every compaction`() {
        val run = Runs.adaptive
        for (k in run.confirmedAtRoomEnds.indices) {
            assertEquals(run.savedAtRoomEnds[k], run.confirmedAtRoomEnds[k])
        }
    }

    @Test
    fun `the replay's reveal counts rise to exactly the saved cloud`() {
        val run = Runs.adaptive
        val atFrames = IntArray(run.walk.frameCount / REVEAL_STEP) { (it + 1) * REVEAL_STEP }
        val reveal = run.fusion.revealCounts(atFrames)
        for (k in 1 until reveal.size) assertTrue(reveal[k] >= reveal[k - 1])
        assertEquals(run.fusion.cloud(minViews = DenseFusion.MIN_VIEWS).count, reveal.last())
        assertEquals(0, run.fusion.revealCounts(intArrayOf(0))[0])
    }

    @Test
    fun `a map that is not allowed to coarsen keeps the old cap`() {
        val capped = DenseFusion(maxPoints = 3, maxLevel = 0)
        val line = FloatArray(30) { if (it % 3 == 0) it / 3 * 0.1f else 0f }
        val samples = DenseSamples(10, line, FloatArray(30), IntArray(10), ByteArray(10) { -1 })
        assertEquals(3, capped.add(samples).total)
        assertTrue(capped.saturated)
        assertEquals(0, capped.compactions)
    }

    @Test
    fun `the budget follows the heap, between a quarter and three fifths of a million points`() {
        assertEquals(DenseFusion.MIN_BUDGET, DenseFusion.budgetFor(64L shl 20))
        assertEquals(524_288, DenseFusion.budgetFor(256L shl 20))
        assertEquals(DenseFusion.MAX_BUDGET, DenseFusion.budgetFor(1L shl 30))
    }

    @Test
    fun `a compaction that coarsens without merging voxels still emits their levels`() {
        val fusion = DenseFusion(maxPoints = 10, maxLevel = 1)
        val positions = FloatArray(8 * 3) { if (it % 3 == 0) (it / 3 * 2) * 0.02f + 0.001f else 0f }
        fusion.add(DenseSamples(8, positions, FloatArray(positions.size), IntArray(8), ByteArray(8)))

        assertEquals(8, fusion.count)
        assertEquals(0, fusion.compactions)
        assertArrayEquals(ByteArray(8) { 1 }, fusion.cloud().levels)
    }

    // ── The saved format and the drawing ─────────────────────────────────────

    @Test
    fun `detail levels round-trip through SVPC, and a cloud without them encodes as before`() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f)
        val plain = DenseCloud(positions, intArrayOf(COLOR, COLOR))
        val flat = DenseCloud(positions, intArrayOf(COLOR, COLOR), levels = byteArrayOf(0, 0))
        assertArrayEquals("all-fine levels write nothing", SvpcCodec.encode(plain), SvpcCodec.encode(flat))
        assertEquals(SvpcCodec.sizeOf(2, 0), SvpcCodec.encode(plain).size)

        val mixed = DenseCloud(positions, intArrayOf(COLOR, COLOR), levels = byteArrayOf(0, 2))
        val bytes = SvpcCodec.encode(mixed)
        assertEquals(SvpcCodec.sizeOf(2, SvpcCodec.FLAG_LEVELS), bytes.size)
        val back = SvpcCodec.decode(bytes)!!
        assertEquals(0, back.levelOf(0))
        assertEquals(2, back.levelOf(1))
        assertEquals(0, SvpcCodec.decode(SvpcCodec.encode(plain))!!.levelOf(1))
    }

    @Test
    fun `a coarse surfel is drawn as wide as the area it stands for`() {
        val cloud = DenseCloud(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f),
            intArrayOf(COLOR, COLOR),
            normals = floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f),
            levels = byteArrayOf(0, 2),
        )
        val mesh = DenseSurfels.mesh(cloud, DenseFusion.VOXEL_M)
        fun width(surfel: Int): Float {
            val xs = (0 until 4).map { mesh.positions[(surfel * 4 + it) * 3] }
            return xs.max() - xs.min()
        }
        assertEquals(4f, width(1) / width(0), 1e-3f)
    }

    /** One walk, fused three ways, measured at the end of each room. */
    private class Run(val walk: ScanWalkthrough, val fusion: DenseFusion) {
        var maxCount = 0
        val cellsAtRoomEnds = ArrayList<Int>()
        val confirmedAtRoomEnds = ArrayList<Int>()
        val savedAtRoomEnds = ArrayList<Int>()
        lateinit var roomCells: IntArray
        lateinit var roomPoints: IntArray

        fun play(checkpoints: Boolean): Run {
            val perRoom = walk.frameCount / walk.rooms.size
            for (i in 0 until walk.frameCount) {
                fusion.add(DepthBackProjection.project(walk.frame(i)))
                maxCount = maxOf(maxCount, fusion.count)
                if (checkpoints && (i + 1) % perRoom == 0) {
                    val saved = fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
                    cellsAtRoomEnds += coveredCells(saved).size
                    confirmedAtRoomEnds += fusion.confirmed
                    savedAtRoomEnds += saved.count
                }
            }
            val saved = fusion.cloud(minViews = DenseFusion.MIN_VIEWS)
            roomPoints = walk.pointsPerRoom(saved)
            roomCells = IntArray(ScanWalkthrough.ROOMS)
            for (cell in coveredCells(saved)) {
                val room = floor((cell.first * CELL_M + ROOM_HALF_M) / ScanWalkthrough.ROOM_SPACING_M).toInt()
                if (room in 0 until ScanWalkthrough.ROOMS) roomCells[room]++
            }
            return this
        }
    }

    private object Runs {
        val walk = ScanWalkthrough(yawSteps = YAW_STEPS)
        val unbounded by lazy { Run(walk, DenseFusion(maxPoints = 4_000_000, maxLevel = 0)).play(checkpoints = false) }
        val unboundedRoomCells by lazy { unbounded.roomCells }
        val unboundedRoomPoints by lazy { unbounded.roomPoints }
        val fixed by lazy { Run(walk, DenseFusion(maxPoints = DenseFusion.MAX_POINTS, maxLevel = 0)).play(false) }
        val adaptive by lazy { Run(walk, DenseFusion(maxPoints = BUDGET)).play(checkpoints = true) }
    }

    private companion object {
        /** The smallest phone's budget: the hardest case. */
        const val BUDGET = DenseFusion.MIN_BUDGET

        /** A quicker walk than the bundled room's (30 steps a turn): the same rooms, fewer frames. */
        const val YAW_STEPS = 12

        /** 8 cm, the coarsest level: a cell any level of point marks as covered. */
        const val CELL_M = 0.08f
        const val ROOM_HALF_M = 2f

        /** Of each room's surface, at 8 cm, what the map must still hold at the end of the walk. */
        const val MIN_SURFACE_KEPT = 0.9f
        const val REVEAL_STEP = 30
        const val COLOR = 0xFF336699.toInt()

        /** The 8 cm cells (x, y, z) [cloud]'s points fall in. */
        fun coveredCells(cloud: DenseCloud): Set<Triple<Int, Int, Int>> {
            val cells = HashSet<Triple<Int, Int, Int>>(cloud.count / 4)
            for (i in 0 until cloud.count) {
                cells += Triple(
                    floor(cloud.positions[i * 3] / CELL_M).toInt(),
                    floor(cloud.positions[i * 3 + 1] / CELL_M).toInt(),
                    floor(cloud.positions[i * 3 + 2] / CELL_M).toInt(),
                )
            }
            return cells
        }
    }
}
