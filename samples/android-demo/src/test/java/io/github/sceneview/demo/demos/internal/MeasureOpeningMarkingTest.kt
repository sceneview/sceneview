package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A door or window half marked goes with the wall it was begun on, never onto another one. */
class MeasureOpeningMarkingTest {
    private fun p(x: Float, z: Float) = Position(x, 0f, z)

    // A wall measured foot to foot, then raised to [height].
    private fun MeasureBoard<Position>.wall(a: Position, b: Position, height: Float) =
        tap(a, false).tap(b, false).lockHeight(height)

    // The add button with the aim on the wall at [point], as the screen plays it while marking:
    // the first tap places a corner, the second adds the opening between the two.
    private fun MeasureBoard<Position>.tapOnWall(point: MeasureWallPoint): MeasureBoard<Position> {
        if (!markingOpening || openingWall == null) return this
        val first = openingCorner ?: return withOpeningCorner(point)
        return measureOpening(first, point)?.let { addOpening(it).stopOpening() } ?: this
    }

    private fun MeasureBoard<Position>.lastNumbers(): MeasureFigureNumbers {
        val figure = figures.last()
        return measureFigureNumbers(figure, measureFigureOutline(figure.mode, figure.points))
    }

    // A 4 m wall, 2.5 m high: Door or window on, its first corner placed 3.5 m along, 2 m up.
    private fun markedFirstCorner(): MeasureBoard<Position> =
        MeasureBoard<Position>(mode = MeasureMode.Wall)
            .wall(p(0f, -2f), p(4f, -2f), 2.5f)
            .startOpening()
            .tapOnWall(MeasureWallPoint(along = 3.5f, up = 2f))

    @Test
    fun `a first corner left on one wall marks nothing on the next one`() {
        var board = markedFirstCorner()
        assertNotNull(board.openingCorner)

        // Off to Room for a 3 × 4 m room, then back to Wall for a new wall, 2 m × 2.5 m.
        board = board.withMode(MeasureMode.Room)
            .tap(p(0f, 0f), false).tap(p(3f, 0f), false).tap(p(3f, 4f), false).tap(p(0f, 4f), false)
            .tap(null, closesOutline = true)
            .lockHeight(2.4f)
        board = board.withMode(MeasureMode.Wall).wall(p(5f, 0f), p(7f, 0f), 2.5f)
        assertNotNull(board.openingWall)
        assertFalse(board.markingOpening)
        assertNull(board.openingCorner)

        // The next tap on the new wall, near its foot, cuts no opening out of it.
        board = board.tapOnWall(MeasureWallPoint(along = 0.5f, up = 0f))
        val wall = board.figures.last()
        assertTrue(wall.openings.isEmpty())
        val numbers = board.lastNumbers()
        assertEquals(5f, numbers.wallArea!!, 1e-4f)
        assertNull(numbers.openingsArea)
        assertNull(numbers.netWallArea)
    }

    @Test
    fun `a mode change ends the marking, even straight back on the same wall`() {
        val board = markedFirstCorner().withMode(MeasureMode.Distance).withMode(MeasureMode.Wall)
        assertNotNull(board.openingWall)
        assertFalse(board.markingOpening)
        assertNull(board.openingCorner)
    }

    @Test
    fun `undoing the wall's height ends the marking, and locking it again does not resume it`() {
        var board = markedFirstCorner()
        board = board.undo() // the first corner
        assertTrue(board.markingOpening)
        board = board.undo() // the height: the wall rises again
        assertNull(board.openingWall)
        board = board.lockHeight(2.5f)
        assertFalse(board.markingOpening)
        assertNull(board.openingCorner)
    }
}
