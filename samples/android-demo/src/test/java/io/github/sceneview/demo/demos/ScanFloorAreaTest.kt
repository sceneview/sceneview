package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.ArDebugLogWriter
import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.CoverageGrid
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.ScanFloorArea
import io.github.sceneview.demo.demos.internal.of
import io.github.sceneview.demo.demos.internal.parseArDebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The floor covered, one figure on three screens (#4734): live it never goes down and never
 * stalls while cells keep coming; its replay and its result read the cells the capture recorded,
 * so the three say the same m² — where a kitchen read `16 m²` live, `25 m²` in Replay, `72 %` on
 * its result.
 */
class ScanFloorAreaTest {

    private val cell = CoverageGrid.CELL_METRES

    /** Three points a voxel apart in cell ([cx], [cz]): enough to cover it on their own. */
    private fun cellPoints(cx: Int, cz: Int): FloatArray {
        val out = FloatArray(CoverageGrid.MIN_POINTS * 3)
        for (i in 0 until CoverageGrid.MIN_POINTS) {
            out[i * 3] = cx * cell + 0.02f + i * 0.07f
            out[i * 3 + 1] = 0f
            out[i * 3 + 2] = cz * cell + 0.1f
        }
        return out
    }

    /**
     * A live capture: [steps] frames, each adding feature points over one new cell along X and a
     * depth snapshot covering a growing strip along Z — a snapshot that, as a fusion's compaction
     * does, sometimes loses a cell it had. Returns the trace and the figure after each frame.
     */
    private fun liveCapture(steps: Int): Pair<ArDebugTrace, List<Float>> {
        val trace = ArDebugTrace().apply { journal = ArrayList() }
        val coverage = ScanCoverageState()
        val figures = ArrayList<Float>()
        for (step in 0 until steps) {
            val nanos = step * STEP_NANOS
            trace.addPose(nanos, DebugPose(step * 0.1f, 1.4f, 0f))
            trace.addPoints(nanos, cellPoints(step, 0))
            // The snapshot strip: rows 1..step+1 of column 0, but the oldest row is dropped every
            // third snapshot, as a compaction merges surfels.
            val from = if (step % 3 == 2) 2 else 1
            val snapshot = LongArray(step + 2 - from) { CoverageGrid.key(0, from + it) }
            coverage.observe(trace.frameAt(trace.duration), snapshotCells = snapshot, record = trace)
            figures += coverage.coveredSquareMetres
        }
        return trace to figures
    }

    /** [trace]'s journal written and read back, as a saved scan reopens. */
    private fun saved(trace: ArDebugTrace): ArDebugTrace {
        val events: List<ArDebugEvent> = trace.journal.orEmpty().toList()
        return ArDebugTrace.of(parseArDebugLog(ArDebugLogWriter.write(events).lineSequence()))
    }

    @Test
    fun `live, the figure never goes down and grows with every new cell`() {
        val (_, figures) = liveCapture(steps = 12)
        for (i in 1 until figures.size) {
            // Each frame covers a new feature cell and a new snapshot row: the figure moves.
            assertTrue("frame $i: ${figures[i - 1]} → ${figures[i]}", figures[i] > figures[i - 1])
        }
    }

    @Test
    fun `live, replay and result say the same floor covered`() {
        val (live, figures) = liveCapture(steps = 12)
        val liveFigure = figures.last()
        val scan = saved(live)
        assertTrue(scan.hasFloorCells)

        // Replay: played to its end, with a dense cloud the recorded cells take precedence over.
        val dense = FloatArray(0)
        val replay = ScanCoverageState()
        var t = 0f
        while (t < scan.duration) {
            replay.observe(scan.frameAt(t), dense, 0, saved = scan)
            t += STEP_SECONDS / 2
        }
        replay.observe(scan.frameAt(scan.duration), dense, 0, saved = scan)
        assertEquals(liveFigure, replay.coveredSquareMetres, 0f)

        // Result: the same cells, read once.
        assertEquals(liveFigure, ScanFloorArea.ofSaved(scan, dense, 0), 0f)
        // And the figure is the cells covered, in m²: 12 feature cells and 12 snapshot rows.
        assertEquals(ScanFloorArea.squareMetres(24), liveFigure, 0f)
    }

    @Test
    fun `a replay scrubbed back says what was covered by then, then catches up`() {
        val (live, _) = liveCapture(steps = 8)
        val scan = saved(live)
        val replay = ScanCoverageState()
        replay.observe(scan.frameAt(scan.duration), saved = scan)
        val end = replay.coveredSquareMetres
        replay.observe(scan.frameAt(0f), saved = scan)
        assertTrue(replay.coveredSquareMetres < end)
        replay.observe(scan.frameAt(scan.duration), saved = scan)
        assertEquals(end, replay.coveredSquareMetres, 0f)
    }

    @Test
    fun `a scan saved before the cells were recorded reads the same on replay and result`() {
        val trace = ArDebugTrace()
        for (step in 0 until 6) trace.addPoints(step * STEP_NANOS, cellPoints(step, step))
        assertFalse(trace.hasFloorCells)
        val replay = ScanCoverageState()
        replay.observe(trace.frameAt(trace.duration), saved = trace)
        assertEquals(replay.coveredSquareMetres, ScanFloorArea.ofSaved(trace, null, 0), 0f)
        assertEquals(ScanFloorArea.squareMetres(6), replay.coveredSquareMetres, 0f)
    }

    @Test
    fun `the recorded cells survive the log, each once`() {
        val trace = ArDebugTrace().apply { journal = ArrayList() }
        trace.addPose(0L, DebugPose(0f, 0f, 0f))
        trace.addFloorCells(STEP_NANOS, longArrayOf(CoverageGrid.key(-3, 7), CoverageGrid.key(2, -1)))
        // Again, plus one: only the new one is kept.
        trace.addFloorCells(2 * STEP_NANOS, longArrayOf(CoverageGrid.key(2, -1), CoverageGrid.key(0, 0)))
        val scan = saved(trace)
        assertEquals(3, scan.floorCellsAt(scan.duration))
        assertEquals(0, scan.floorCellsAt(0f)) // none at the start
        assertEquals(CoverageGrid.key(-3, 7), scan.floorCell(0))
        assertEquals(CoverageGrid.key(0, 0), scan.floorCell(2))
    }

    private companion object {
        const val STEP_NANOS = 500_000_000L
        const val STEP_SECONDS = 0.5f
    }
}
