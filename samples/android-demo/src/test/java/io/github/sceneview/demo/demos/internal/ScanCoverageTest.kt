package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CoverageGrid] (#4631): what the live map fills, outlines and counts as `Floor NN %`. */
class CoverageGridTest {

    /** [n] points at ([x], [z]), at mid height. */
    private fun at(x: Float, z: Float, n: Int = CoverageGrid.MIN_POINTS): FloatArray =
        FloatArray(n * 3) { i -> if (i % 3 == 0) x else if (i % 3 == 2) z else 1f }

    /** One covered cell per (cx, cz), points at the cell's centre. */
    private fun cells(vararg cells: Pair<Int, Int>): FloatArray = cells
        .map { (cx, cz) -> at((cx + 0.5f) * CoverageGrid.CELL_METRES, (cz + 0.5f) * CoverageGrid.CELL_METRES) }
        .reduce { a, b -> a + b }

    @Test
    fun `a cell is covered from its third point, not before`() {
        val grid = CoverageGrid()
        assertFalse(grid.update(at(0.1f, 0.1f, n = 2)))
        assertEquals(0, grid.covered)
        assertTrue(grid.update(at(0.1f, 0.1f, n = 2) + at(0.1f, 0.1f, n = 1)))
        assertTrue(grid.isCovered(0, 0))
        assertEquals(1, grid.covered)
    }

    @Test
    fun `only the points past the last update are binned`() {
        val grid = CoverageGrid()
        val first = at(0.1f, 0.1f)
        grid.update(first)
        val version = grid.version
        // The same prefix again: nothing new, nothing counted twice.
        assertFalse(grid.update(first))
        assertEquals(version, grid.version)
        assertTrue(grid.update(first + at(1.1f, 0.1f)))
        assertEquals(2, grid.covered)
    }

    @Test
    fun `a shorter cloud, a replay scrubbed back, starts the grid again`() {
        val grid = CoverageGrid()
        grid.update(cells(0 to 0, 1 to 0, 2 to 0))
        grid.update(cells(5 to 5))
        assertEquals(1, grid.covered)
        assertTrue(grid.isCovered(5, 5))
        assertFalse(grid.isCovered(0, 0))
    }

    @Test
    fun `a dense cloud revealed as a prefix bins only the points found so far`() {
        // #4619: a v2 replay's room is its dense cloud, revealed surfel by surfel along the timeline.
        val grid = CoverageGrid()
        val dense = cells(0 to 0, 1 to 0, 2 to 0)
        val perCell = CoverageGrid.MIN_POINTS
        assertTrue(grid.update(dense, count = perCell))
        assertEquals(1, grid.covered)
        assertTrue(grid.update(dense, count = 3 * perCell))
        assertEquals(3, grid.covered)
        // A count past the cloud is clamped to it, not an out-of-bounds read.
        assertFalse(grid.update(dense, count = 99 * perCell))
        assertEquals(3, grid.covered)
        // Back to the start of the timeline: the grid starts again, as a scrubbed replay does.
        grid.update(dense, count = 0)
        assertEquals(0, grid.covered)
    }

    @Test
    fun `negative coordinates fall in the cell below, not in cell zero`() {
        val grid = CoverageGrid()
        assertEquals(-1, grid.cellOf(-0.1f))
        assertEquals(0, grid.cellOf(0.1f))
        grid.update(at(-0.1f, -0.1f))
        assertTrue(grid.isCovered(-1, -1))
    }

    @Test
    fun `the frontier is the edge of what is covered`() {
        val grid = CoverageGrid()
        val block = (-1..1).flatMap { cx -> (-1..1).map { cz -> cx to cz } }.toTypedArray()
        grid.update(cells(*block))
        assertFalse(grid.isFrontier(0, 0))
        assertTrue(grid.isFrontier(1, 0))
        assertTrue(grid.isFrontier(-1, -1))
        // Not covered: not frontier either.
        assertFalse(grid.isFrontier(2, 0))
    }

    @Test
    fun `the covered share is of the scan's own footprint`() {
        val full = CoverageGrid().apply { update(cells(0 to 0, 1 to 0, 0 to 1, 1 to 1)) }
        assertEquals(1f, full.coveredShare, 1e-6f)
        val ell = CoverageGrid().apply { update(cells(0 to 0, 1 to 0, 0 to 1)) }
        assertEquals(0.75f, ell.coveredShare, 1e-6f)
        // Two walls apart, nothing between: most of that floor is not there yet.
        val walls = CoverageGrid().apply { update(cells(0 to 0, 0 to 1, 7 to 0, 7 to 1)) }
        assertEquals(0.25f, walls.coveredShare, 1e-6f)
        assertEquals(0f, CoverageGrid().coveredShare, 0f)
    }

    @Test
    fun `cells are listed in the order they were covered`() {
        val grid = CoverageGrid()
        grid.update(cells(3 to -2, -4 to 7))
        val seen = mutableListOf<Pair<Int, Int>>()
        grid.forEachCovered { cx, cz -> seen += cx to cz }
        assertEquals(listOf(3 to -2, -4 to 7), seen)
    }

    /** A flat 2 m × 2 m floor plane from (0, 0), world xyz. */
    private val floor2m = floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 2f, 0f, 2f, 0f, 0f, 2f)

    @Test
    fun `the floor extent is the floor plane, and the share counts what is still to scan`() {
        val grid = CoverageGrid()
        // The first half-metre band of the 2 m floor covered: 16 of its 64 cells.
        val covered = (0 until 8).flatMap { cx -> (0 until 2).map { cz -> cx to cz } }
        grid.update(cells(*covered.toTypedArray()))
        val extent = grid.floorExtent(listOf(floor2m))
        assertEquals(0f, extent.minOf { it.first }, 1e-4f)
        assertEquals(2f, extent.maxOf { it.first }, 1e-4f)
        assertEquals(2f, extent.maxOf { it.second }, 1e-4f)
        assertEquals(16f / 64f, grid.coveredShareOf(extent), 1e-4f)
    }

    @Test
    fun `covered cells outside the floor plane widen the extent instead of overflowing it`() {
        val grid = CoverageGrid()
        grid.update(cells(10 to 0))
        val extent = grid.floorExtent(listOf(floor2m))
        assertEquals(11 * CoverageGrid.CELL_METRES, extent.maxOf { it.first }, 1e-4f)
        assertTrue(CoverageGrid.insideConvex(extent, 10.5f * CoverageGrid.CELL_METRES, 0.1f))
    }

    @Test
    fun `no floor and nothing covered is no extent and no share`() {
        val grid = CoverageGrid()
        assertTrue(grid.floorExtent(emptyList()).isEmpty())
        assertEquals(0f, grid.coveredShareOf(emptyList()), 0f)
    }
}

/** [ScanCoach] (#4631): one line, from the phone's own movement and whether the scan grows. */
class ScanCoachTest {

    private fun pose(x: Float, z: Float = 0f) = DebugPose(x, 1.4f, z)

    /** Walks along X at [speed] m/s for [seconds], 30 frames a second, from [from]. */
    private fun ScanCoach.walk(speed: Float, seconds: Float, from: Float = 0f, grow: Boolean = false): Float {
        val frames = (seconds * FPS).toInt()
        var t = from
        repeat(frames) {
            t += 1f / FPS
            update(t, pose(speed * t), grew = grow)
        }
        return t
    }

    @Test
    fun `standing still says nothing at first, then that the scan waits on the phone`() {
        val coach = ScanCoach()
        val t = coach.walk(speed = 0f, seconds = ScanCoach.STILL_SECONDS - 1f)
        assertNull(coach.line)
        coach.walk(speed = 0f, seconds = 2f, from = t)
        assertEquals(ScanCoach.Line.Still, coach.line)
    }

    @Test
    fun `standing still while the scan grows says nothing`() {
        val coach = ScanCoach()
        coach.walk(speed = 0f, seconds = 10f, grow = true)
        assertNull(coach.line)
    }

    @Test
    fun `a phone moved too fast is told to slow down`() {
        val coach = ScanCoach()
        coach.walk(speed = 2f, seconds = 2f, grow = true)
        assertEquals(ScanCoach.Line.Slower, coach.line)
    }

    @Test
    fun `a walk that adds nothing for three seconds is told where to point`() {
        val coach = ScanCoach()
        val t = coach.walk(speed = 0.5f, seconds = 2.5f, grow = false)
        assertNull(coach.line)
        coach.walk(speed = 0.5f, seconds = 1f, from = t, grow = false)
        assertEquals(ScanCoach.Line.NothingNew, coach.line)
    }

    @Test
    fun `a walk that keeps adding says nothing`() {
        val coach = ScanCoach()
        coach.walk(speed = 0.5f, seconds = 8f, grow = true)
        assertNull(coach.line)
    }

    @Test
    fun `a line holds long enough to be read, then goes with its cause`() {
        val coach = ScanCoach()
        var t = coach.walk(speed = 2f, seconds = 1.5f, grow = true)
        assertEquals(ScanCoach.Line.Slower, coach.line)
        // Stopped dead: the line stays its hold, then leaves.
        val stoppedAt = 2f * t
        repeat(FPS / 2) {
            t += 1f / FPS
            coach.update(t, pose(stoppedAt), grew = true)
        }
        assertEquals(ScanCoach.Line.Slower, coach.line)
        repeat((ScanCoach.HOLD_SECONDS * FPS).toInt() + FPS) {
            t += 1f / FPS
            coach.update(t, pose(stoppedAt), grew = true)
        }
        assertNull(coach.line)
    }

    @Test
    fun `no pose leaves the line as it was`() {
        val coach = ScanCoach()
        coach.walk(speed = 2f, seconds = 2f, grow = true)
        assertEquals(ScanCoach.Line.Slower, coach.update(10f, camera = null, grew = false))
    }

    private companion object {
        const val FPS = 30
    }
}
