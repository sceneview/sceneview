package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** A room traced in Measure becomes the plan 3D Scan draws and shares as a PDF. */
class MeasurePlanTest {

    /** [corners] on the plan, turned by [yaw] radians and moved, as a floor outline in world space. */
    private fun traced(corners: List<Pair<Float, Float>>, yaw: Float = 0f, y: Float = -1.2f): List<Position> {
        val c = cos(yaw)
        val s = sin(yaw)
        return corners.map { (x, z) -> Position(x * c - z * s + 0.7f, y, x * s + z * c - 2.1f) }
    }

    private val rectangle = listOf(0f to 0f, 4f to 0f, 4f to 3f, 0f to 3f)
    private val lShape = listOf(0f to 0f, 5f to 0f, 5f to 2f, 3f to 2f, 3f to 4f, 0f to 4f)

    @Test
    fun `a turned room squares up on the page, walls clockwise from the top left`() {
        for (outline in listOf(traced(rectangle, 0.52f), traced(rectangle, 0.52f).reversed())) {
            val plan = measureRoomSurvey(outline, height = 2.5f)
            assertNotNull(plan)
            plan!!
            assertEquals(listOf("A", "B", "C", "D"), plan.walls.map { it.tag })
            assertEquals(4f, plan.planLength, 1e-3f)
            assertEquals(3f, plan.planWidth, 1e-3f)
            // Wall A runs along the top, left to right, from the top-left corner.
            val a = plan.walls.first()
            assertEquals(0f, a.ax, 1e-3f)
            assertEquals(0f, a.ay, 1e-3f)
            assertEquals(4f, a.bx, 1e-3f)
            assertEquals(0f, a.by, 1e-3f)
            // Then down the right side.
            assertEquals(3f, plan.walls[1].by, 1e-3f)
            assertEquals(listOf(4f, 3f, 4f, 3f), plan.walls.map { it.length.value }.map { Math.round(it * 1000) / 1000f })
            assertEquals(12f, plan.area!!.value, 1e-3f)
            assertEquals(14f, plan.perimeter!!.value, 1e-3f)
            assertEquals(2.5f, plan.ceilingHeight!!.value, 0f)
            assertTrue(plan.gaps.isEmpty())
            assertEquals(-1.2f, plan.floorY, 0f)
        }
    }

    @Test
    fun `every figure is measured, none estimated`() {
        val plan = measureRoomSurvey(traced(rectangle), height = 2.5f)!!
        val figures = listOf(plan.length!!, plan.width!!, plan.area!!, plan.perimeter!!, plan.ceilingHeight!!) +
            plan.walls.map { it.length }
        assertTrue(figures.none { it.estimate })
        assertTrue(figures.all { it.plusMinus == 0f })
        assertTrue(plan.walls.all { it.seen && it.seenShare == 1f && it.unseen.isEmpty() })
    }

    @Test
    fun `an L keeps its notch`() {
        val plan = measureRoomSurvey(traced(lShape, -1.1f), height = 2.4f)!!
        assertEquals(6, plan.walls.size)
        assertEquals(5f, plan.planLength, 1e-3f)
        assertEquals(4f, plan.planWidth, 1e-3f)
        assertEquals(16f, plan.area!!.value, 1e-3f)
        assertEquals(18f, plan.perimeter!!.value, 1e-3f)
        // Closed: each wall ends where the next one starts.
        plan.walls.forEachIndexed { i, wall ->
            val next = plan.walls[(i + 1) % plan.walls.size]
            assertEquals(wall.bx, next.ax, 1e-4f)
            assertEquals(wall.by, next.ay, 1e-4f)
        }
    }

    @Test
    fun `a room deeper than wide turns its long side along the page`() {
        val plan = measureRoomSurvey(traced(listOf(0f to 0f, 2f to 0f, 2f to 5f, 0f to 5f), 0.3f), height = 2.5f)!!
        assertEquals(5f, plan.planLength, 1e-3f)
        assertEquals(2f, plan.planWidth, 1e-3f)
    }

    @Test
    fun `the plan reads the same figures as the readout`() {
        val outline = traced(listOf(0f to 0f, 4f to 0f, 4f to 3f, -0.26f to 3f), 2.2f)
        val room = MeasureFigure(MeasureMode.Room, outline, closed = true, height = 2.5f)
        val numbers = measureFigureNumbers(room, outline)
        val plan = measureRoomSurvey(outline, height = 2.5f)!!
        assertEquals(numbers.outlineArea!!, plan.area!!.value, 1e-3f)
        assertEquals(numbers.length, plan.perimeter!!.value, 1e-3f)
    }

    @Test
    fun `without a height the ceiling is a gap`() {
        val plan = measureRoomSurvey(traced(rectangle), height = null)!!
        assertNull(plan.ceilingHeight)
        assertEquals(listOf(SurveyGap.Ceiling), plan.gaps)
    }

    @Test
    fun `no plan under three corners or without an area`() {
        assertNull(measureRoomSurvey(traced(listOf(0f to 0f, 4f to 0f)), 2.5f))
        assertNull(measureRoomSurvey(traced(listOf(0f to 0f, 2f to 0f, 4f to 0f)), 2.5f))
    }
}
