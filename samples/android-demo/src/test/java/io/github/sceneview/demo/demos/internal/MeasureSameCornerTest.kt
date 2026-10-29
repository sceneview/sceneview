package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #4765: the add button on the corner just placed adds no side of length 0. The room is built
 * as on screen, tap by tap ([measureAddPoint]); a second tap on the same corner is refused with
 * "Move to the next corner first", leaves the sides as they were and makes no anchor.
 */
class MeasureSameCornerTest {
    private val floor = -1.3f

    private fun at(x: Float, z: Float, y: Float = floor) = Position(x, y, z)

    private fun add(board: MeasureBoard<Position>, p: Position) = measureAddPoint(board, p, { it }) { p }

    /** A board in [mode] with [corners] tapped one after the other. */
    private fun traced(mode: MeasureMode, vararg corners: Position): MeasureBoard<Position> =
        corners.fold(MeasureBoard<Position>(mode = mode)) { board, p -> add(board, p).board }

    /** The sides of the board's last figure, as the card counts them ("Wall 2"). */
    private fun sides(board: MeasureBoard<Position>): Int {
        val figure = board.figures.last()
        return measureFigureNumbers(figure, measureFigureOutline(figure.mode, figure.points)).segments.size
    }

    @Test
    fun `the same corner tapped twice adds no side and says to move on`() {
        val board = traced(MeasureMode.Room, at(0f, 0f), at(4f, 0f))
        assertEquals(1, sides(board))
        val again = add(board, at(4f, 0f))
        assertEquals(MeasureRefusal.SameCorner, again.refusal)
        assertEquals(board, again.board)
        assertEquals(1, sides(again.board))
    }

    @Test
    fun `a corner a few centimetres from the last one is the same corner`() {
        val board = traced(MeasureMode.Room, at(0f, 0f), at(4f, 0f), at(4f, 3f))
        // 3 cm off along the floor, or caught 6 cm up the skirting board: the same corner on the floor line.
        for (p in listOf(at(4.03f, 3f), at(4f, 2.97f), at(4.02f, 3.02f, floor + 0.06f))) {
            val tap = add(board, p)
            assertEquals(p.toString(), MeasureRefusal.SameCorner, tap.refusal)
            assertEquals(p.toString(), 2, sides(tap.board))
        }
    }

    @Test
    fun `the next corner is added`() {
        val board = traced(MeasureMode.Room, at(0f, 0f), at(4f, 0f))
        val next = add(board, at(4f, 3f))
        assertNull(next.refusal)
        assertEquals(2, sides(next.board))
        // Past the margin is a short wall, kept: a chimney breast's return, say.
        assertNull(add(board, at(4f, 0.08f)).refusal)
    }

    @Test
    fun `a refused corner makes no anchor`() {
        val board = traced(MeasureMode.Room, at(0f, 0f), at(4f, 0f))
        var anchored = false
        val tap = measureAddPoint(board, at(4.01f, 0f), { it }) {
            anchored = true
            at(4.01f, 0f)
        }
        assertEquals(MeasureRefusal.SameCorner, tap.refusal)
        assertFalse(anchored)
    }

    @Test
    fun `a wall's second end on its first raises no wall of width 0`() {
        val board = traced(MeasureMode.Wall, at(0f, 0f))
        val tap = add(board, at(0.01f, 0f))
        assertEquals(MeasureRefusal.SameCorner, tap.refusal)
        assertEquals(MeasurePhase.Next, tap.board.phase)
        assertEquals(1, tap.board.active!!.points.size)
    }

    @Test
    fun `a distance chain keeps its short segments`() {
        val tap = add(traced(MeasureMode.Distance, at(0f, 0f)), at(0.02f, 0f))
        assertNull(tap.refusal)
        assertEquals(2, tap.board.active!!.points.size)
    }

    @Test
    fun `a new measure may start on the last corner of the one before`() {
        val done = traced(MeasureMode.Wall, at(0f, 0f), at(3f, 0f)).lockHeight(2.5f)
        val tap = add(done, at(3f, 0f))
        assertNull(tap.refusal)
        assertEquals(2, tap.board.figures.size)
    }
}
