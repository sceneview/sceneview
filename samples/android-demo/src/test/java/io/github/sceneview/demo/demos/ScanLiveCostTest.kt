package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.ScanMapHatch
import io.github.sceneview.demo.demos.internal.ScanObserveGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3D Scan's live screen at the Pixel 4a's frame rate (#4743): the live view hears each frame
 * once, and the floor map's plan paper is cut by the coverage instead of clipped by it each draw —
 * with the same lines where the floor is still to scan.
 */
class ScanLiveCostTest {

    private fun pose(x: Float) = DebugPose(x, 0f, 0f, 0f, 0f, 0f, 1f)

    @Test
    fun `the same frame, pose and snapshot are heard once`() {
        val gate = ScanObserveGate()
        val frame = Any()
        val cells = LongArray(0)
        assertTrue(gate.isNew(frame, pose(0f), cells))
        assertFalse(gate.isNew(frame, pose(0f), cells))
        assertFalse(gate.isNew(frame, pose(0f), cells))
    }

    @Test
    fun `a new frame, a moved phone or a new snapshot is heard`() {
        val gate = ScanObserveGate()
        val frame = Any()
        val cells = LongArray(0)
        gate.isNew(frame, pose(0f), cells)
        assertTrue("another frame", gate.isNew(Any(), pose(0f), cells))
        val next = Any()
        gate.isNew(next, pose(0f), cells)
        // Paused, the trace's frame stays while the live pose moves: the map still follows it.
        assertTrue("the phone moved", gate.isNew(next, pose(1f), cells))
        assertTrue("a new dense snapshot", gate.isNew(next, pose(1f), LongArray(0)))
        assertTrue("the snapshot went away", gate.isNew(next, pose(1f), null))
        assertFalse(gate.isNew(next, pose(1f), null))
    }

    @Test
    fun `a reset gate hears the next call`() {
        val gate = ScanObserveGate()
        val frame = Any()
        gate.isNew(frame, null, null)
        gate.reset()
        assertTrue(gate.isNew(frame, null, null))
    }

    private val square = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)

    /** The segments as (x0, z0, x1, z1) quadruples, for comparing. */
    private fun FloatArray.segments(): List<List<Float>> = toList().chunked(4)

    @Test
    fun `an uncovered floor is the whole grid, cut to its outline`() {
        val hatch = ScanMapHatch.of(square, 0.25f, 1, 4) { _, _ -> false }
        // Five lines each way at 0, .25, .5, .75 and 1 m; 0 and 1 m are major lines.
        val all = hatch.minor.segments() + hatch.major.segments()
        assertEquals(10, all.size)
        assertEquals(4, hatch.major.segments().size)
        assertTrue(listOf(0.25f, 0f, 0.25f, 1f) in hatch.minor.segments())
        assertTrue(listOf(0f, 0.5f, 1f, 0.5f) in hatch.minor.segments())
    }

    @Test
    fun `a line stops where the cells on both its sides are covered`() {
        // Cells (0, 0) and (1, 0) covered: the line x = .25 m runs between them on its first
        // quarter metre, so it starts at z = .25; the line x = .5 m has (2, 0) uncovered on its
        // right and is drawn whole.
        val covered = setOf(0 to 0, 1 to 0)
        val hatch = ScanMapHatch.of(square, 0.25f, 1, 4) { cx, cz -> (cx to cz) in covered }
        val minor = hatch.minor.segments()
        assertTrue(listOf(0.25f, 0.25f, 0.25f, 1f) in minor)
        assertTrue(listOf(0.5f, 0f, 0.5f, 1f) in minor)
        // The line z = .25 m runs between (0, 0) and (0, 1): (0, 1) is not covered, so it stays.
        assertTrue(listOf(0f, 0.25f, 1f, 0.25f) in minor)
    }

    @Test
    fun `a covered floor has no paper left inside`() {
        val hatch = ScanMapHatch.of(square, 0.25f, 1, 4) { cx, cz -> cx in 0..3 && cz in 0..3 }
        // Only the outline's own lines stay, which have an uncovered cell outside them.
        val all = hatch.minor.segments() + hatch.major.segments()
        assertTrue(all.all { (x0, z0, x1, z1) -> x0 == x1 && (x0 == 0f || x0 == 1f) || z0 == z1 && (z0 == 0f || z0 == 1f) })
        assertEquals(4, all.size)
    }

    @Test
    fun `a slanted outline cuts each line where it crosses it`() {
        val triangle = listOf(0f to 0f, 1f to 0f, 0f to 1f)
        val hatch = ScanMapHatch.of(triangle, 0.25f, 1, 4) { _, _ -> false }
        val line = hatch.minor.segments().single { (x0, _, x1, _) -> x0 == 0.5f && x1 == 0.5f }
        assertEquals(0f, line[1], 1e-6f)
        assertEquals(0.5f, line[3], 1e-6f)
    }

    @Test
    fun `a metre pitch keeps lines on whole metres, every fourth one major`() {
        val room = listOf(-2f to -1f, 2.5f to -1f, 2.5f to 1f, -2f to 1f)
        val hatch = ScanMapHatch.of(room, 0.25f, 4, 4) { _, _ -> false }
        val xs = (hatch.minor.segments() + hatch.major.segments()).filter { it[0] == it[2] }.map { it[0] }.sorted()
        assertEquals(listOf(-2f, -1f, 0f, 1f, 2f), xs)
        // x = 0 m is line 0 — a major line, the way the plan marks its metres.
        assertTrue(hatch.major.segments().any { it[0] == 0f && it[2] == 0f })
    }

    @Test
    fun `a line that misses the outline has no span`() {
        assertNull(ScanMapHatch.spanAt(square, 2f, 0))
        assertEquals(0f to 1f, ScanMapHatch.spanAt(square, 0.5f, 1))
    }

    @Test
    fun `no outline, no paper`() {
        val hatch = ScanMapHatch.of(listOf(0f to 0f, 1f to 0f), 0.25f, 1, 4) { _, _ -> false }
        assertEquals(0, hatch.minor.size + hatch.major.size)
    }
}
