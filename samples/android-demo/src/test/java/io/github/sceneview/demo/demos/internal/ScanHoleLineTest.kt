package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 3D Scan's guidance line and the hatching on the live model, driven in the screen's order: they
 * name the same empty area, or the hatching shows nothing (Codex's review of u30: the hatching
 * moved to the next gap while the line kept the side of the one just filled for two seconds).
 */
class ScanHoleLineTest {
    private val guide = ScanHoleGuide()
    private val line = ScanLiveInstruction()
    private val phone = DebugPose(0f, 0f, 0f, qy = 0f, qw = 1f)

    private val left = -1.5f to 0f
    private val right = 2f to 0f

    /** One pass of the screen: the guide, then the line, then the point the model hatches round. */
    private fun pass(
        holes: List<Pair<Float, Float>>,
        nowMs: Long,
        tooFast: Boolean = false,
    ): Pair<ScanInstruction?, Pair<Float, Float>?> {
        val side = guide.update(holes, phone, nowMs)
        val shown = line.update(nowMs = nowMs, tooFast = tooFast, hole = side, holeId = guide.gapId)
        return shown to guide.marked(line.namedHole)
    }

    /** Both gaps seen from the start: the line names the nearer, [left], once its intro is over. */
    private fun namingLeft(): Long {
        pass(listOf(left, right), 0L)
        val after = ScanLiveInstruction.INTRO_MS + 1
        assertEquals(ScanInstruction.HoleLeft to left, pass(listOf(left, right), after))
        return after
    }

    @Test
    fun `nothing is hatched while the line says something else`() {
        pass(listOf(left), 0L)
        // The gap has settled, the line is still on its opening sweep.
        val (shown, marked) = pass(listOf(left), ScanHoleGuide.SETTLE_MS)
        assertEquals(ScanInstruction.Sweep, shown)
        assertNull(marked)
    }

    @Test
    fun `the line follows the hatching at once when the named area fills`() {
        val after = namingLeft()
        // A tenth of a second later, well inside the line's two-second hold.
        assertEquals(ScanInstruction.HoleRight to right, pass(listOf(right), after + 100))
    }

    @Test
    fun `a line naming an area gives way at once when none is left`() {
        val after = namingLeft()
        assertEquals(ScanInstruction.KeepGoing to null, pass(emptyList(), after + 100))
    }

    @Test
    fun `a problem takes the line and the hatching together`() {
        val after = namingLeft()
        val later = after + ScanLiveInstruction.HOLD_MS
        assertEquals(ScanInstruction.Slower to null, pass(listOf(left, right), later, tooFast = true))
        // Back to the same area once the problem is over.
        assertEquals(
            ScanInstruction.HoleLeft to left,
            pass(listOf(left, right), later + ScanLiveInstruction.HOLD_MS),
        )
    }
}
