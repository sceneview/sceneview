package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a tap on Plan does: it opens the plan of the last room traced and raised and, with no such
 * room, says what is missing — trace it, close it, set its height (#4765); it says why too when that
 * room has no plan to draw. Never a silent tap.
 */
class MeasurePlanTapTest {

    /** A room traced along [corners] (x, z on the floor), closed, its walls [height] metres high. */
    private fun room(corners: List<Pair<Float, Float>>, height: Float? = 2.5f) = MeasureFigure(
        mode = MeasureMode.Room,
        points = corners.map { (x, z) -> Position(x, -1.2f, z) },
        closed = true,
        height = height,
    )

    private fun tap(vararg figures: MeasureFigure<Position>) =
        measurePlanTap(figures.toList(), figures.map { measureFigureOutline(it.mode, it.points) })

    private val rectangle = listOf(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f)

    /** Three corners in a line: the room closed and raised, but with no floor between its walls. */
    private val line = listOf(0f to 0f, 2f to 0f, 4f to 0f)

    /** The room still being traced along [corners]: open, or closed with its walls rising. */
    private fun tracing(corners: List<Pair<Float, Float>>, closed: Boolean = false) =
        room(corners, height = null).copy(closed = closed, raising = closed)

    @Test
    fun `with no room traced, Plan says to trace one`() {
        assertEquals(MeasurePlanTap.Refused(MeasureRefusal.PlanNeedsRoom), tap())
        // A wall measured on its own is no room.
        val wall = MeasureFigure(mode = MeasureMode.Wall, points = room(rectangle).points.take(2), height = 2.5f)
        assertEquals(MeasurePlanTap.Refused(MeasureRefusal.PlanNeedsRoom), tap(wall))
        assertEquals(-1, measurePlanRoom(listOf(room(rectangle, height = null))))
    }

    @Test
    fun `on a room still open, Plan says to close it`() {
        assertEquals(MeasurePlanTap.Refused(MeasureRefusal.PlanNeedsClose), tap(tracing(rectangle)))
        assertEquals(MeasurePlanTap.Refused(MeasureRefusal.PlanNeedsClose), tap(tracing(rectangle.take(2))))
    }

    @Test
    fun `on a closed room with no height yet, Plan says to set it`() {
        assertEquals(MeasurePlanTap.Refused(MeasureRefusal.PlanNeedsHeight), tap(tracing(rectangle, closed = true)))
    }

    @Test
    fun `a room traced after a raised one leaves Plan on the raised one`() {
        val opened = tap(room(rectangle), tracing(rectangle.take(2)))
        assertTrue(opened.toString(), opened is MeasurePlanTap.Open && opened.room == 0)
    }

    @Test
    fun `a raised room opens its plan`() {
        val opened = tap(room(rectangle))
        assertTrue(opened.toString(), opened is MeasurePlanTap.Open)
        opened as MeasurePlanTap.Open
        assertEquals(0, opened.room)
        assertEquals(12f, opened.survey.area!!.value, 1e-3f)
    }

    @Test
    fun `a raised room with no floor says it has no plan`() {
        assertEquals(MeasurePlanTap.Refused(MeasureRefusal.NoPlan), tap(room(line)))
    }

    @Test
    fun `Plan draws the last room raised`() {
        val opened = tap(room(line), room(rectangle))
        assertTrue(opened.toString(), opened is MeasurePlanTap.Open && opened.room == 1)
        assertEquals(1, measurePlanRoom(listOf(room(line), room(rectangle), room(rectangle, height = null))))
        // The last one has no floor: Plan says so rather than draw an older room.
        assertEquals(MeasurePlanTap.Refused(MeasureRefusal.NoPlan), tap(room(rectangle), room(line)))
    }
}
