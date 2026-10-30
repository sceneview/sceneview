package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RoomPlanLayoutTest.Companion.WORDING
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

/**
 * The floor every result view stands its room on (#4735): the scan's frame laid on the plan's
 * page, the room's dimensions read off its walls, and the grid the plan and the dollhouse draw.
 */
class ResultFloorTest {
    private val square = floatArrayOf(0f, 0f, 4f, 0f, 4f, 3f, 0f, 3f)
    private val deep = floatArrayOf(0f, 0f, 3f, 0f, 3f, 5f, 0f, 5f)
    private val yaw = (PI / 6).toFloat()
    private val turned = turnedRoom(4f, 3f, yaw, u0 = 1f, v0 = 2f)

    @Test
    fun `the scan's corners land on the plan's corners`() {
        for ((corners, at) in listOf(square to 0f, deep to 0f, turned to yaw)) {
            val structure = structureOf(*corners, yaw = at)
            val survey = RoomSurveyor.of(structure)
            val page = PlanPage.of(survey)!!
            val expected = survey.walls.map { it.ax to it.ay }
            val mapped = structure.walls.map { page.toPage(it.ax, it.az).let { p -> p[0] to p[1] } }
            assertEquals(expected.size, mapped.size)
            for (p in mapped) {
                assertTrue("$p is no corner of $expected", expected.any { abs(it.first - p.first) < 1e-4f && abs(it.second - p.second) < 1e-4f })
            }
            for (e in expected) {
                assertTrue("$e is not reached", mapped.any { abs(it.first - e.first) < 1e-4f && abs(it.second - e.second) < 1e-4f })
            }
        }
    }

    @Test
    fun `a box on the floor is its four corners on the page`() {
        val survey = structuredSurvey(*turned, yaw = yaw)
        val page = PlanPage.of(survey)!!
        val box = floatArrayOf(1.2f, 0f, 2.4f, 2.0f, 0.05f, 3.1f)
        val quad = page.quad(box)
        val corners = listOf(box[0] to box[2], box[3] to box[2], box[3] to box[5], box[0] to box[5])
        val expected = corners.flatMap { (x, z) -> page.toPage(x, z).toList() }.toFloatArray()
        assertArrayEquals(expected, quad, 1e-6f)
    }

    @Test
    fun `a plan with no structure behind it has no page`() {
        assertNull(PlanPage.of(planSurvey(0f, 0f, 4f, 0f, 4f, 3f, 0f, 3f)))
        assertNull(PlanPage.of(RoomSurvey.empty(SurveyGap.NoWalls)))
    }

    @Test
    fun `the room's dimensions are its walls' rectangle, squared to them`() {
        val flat = ResultFloor.measureOf(structuredSurvey(*square))!!
        assertEquals(4f, flat.width, 1e-4f)
        assertEquals(3f, flat.depth, 1e-4f)
        assertEquals(0f, flat.yaw, 1e-6f)
        assertArrayEquals(square, flat.corners, 1e-4f)

        val long = ResultFloor.measureOf(structuredSurvey(*deep))!!
        assertEquals(3f, long.width, 1e-4f)
        assertEquals(5f, long.depth, 1e-4f)

        val oblique = ResultFloor.measureOf(structuredSurvey(*turned, yaw = yaw))!!
        assertEquals(4f, oblique.width, 1e-4f)
        assertEquals(3f, oblique.depth, 1e-4f)
        assertEquals(yaw, oblique.yaw, 1e-6f)
        assertArrayEquals(turned, oblique.corners, 1e-4f)
    }

    @Test
    fun `no dimensions without walls read off a scan, nor for a room under a table's size`() {
        assertNull(ResultFloor.measureOf(planSurvey(0f, 0f, 4f, 0f, 4f, 3f, 0f, 3f)))
        assertNull(ResultFloor.measureOf(structuredSurvey(0f, 0f, 0.5f, 0f, 0.5f, 2f, 0f, 2f)))
    }

    @Test
    fun `the page's origin on screen lays every corner on the plan's outline`() {
        val metrics = PlanMetrics(1f) { text, size -> text.length * size * 0.5f }
        for ((corners, at) in listOf(square to 0f, deep to 0f, turned to yaw)) {
            val survey = structuredSurvey(*corners, yaw = at)
            val drawing = RoomPlanLayout.layout(survey, SurveyReport.planTexts(survey, WORDING), 800f, 600f, metrics)
            val origin = ResultFloor.planOrigin(drawing, survey)!!
            survey.walls.forEachIndexed { i, wall ->
                assertEquals(drawing.floor[i * 2], origin[0] + wall.ax * drawing.pxPerMetre, 1e-2f)
                assertEquals(drawing.floor[i * 2 + 1], origin[1] + wall.ay * drawing.pxPerMetre, 1e-2f)
            }
        }
        assertNull(ResultFloor.planOrigin(RoomPlanDrawing(10f, 10f, 0f, FloatArray(0), emptyList(), emptyList()), RoomSurvey.empty(SurveyGap.NoWalls)))
    }

    @Test
    fun `the plan's grid runs every quarter metre from the room's corner, a bolder line every metre`() {
        val grid = ResultFloor.planGrid(structuredSurvey(*square))
        fun lines(segments: FloatArray, vertical: Boolean) = (0 until segments.size / 4).count {
            val upright = abs(segments[it * 4] - segments[it * 4 + 2]) < 1e-6f
            upright == vertical
        }
        assertEquals(0, grid.minor.size % 4)
        assertEquals(0, grid.major.size % 4)
        // 4 m along: 17 lines, 5 of them on whole metres. 3 m across: 13, and 4.
        assertEquals(5, lines(grid.major, vertical = true))
        assertEquals(12, lines(grid.minor, vertical = true))
        assertEquals(4, lines(grid.major, vertical = false))
        assertEquals(9, lines(grid.minor, vertical = false))
        // Every line spans the room, and none strays outside it.
        for (segments in listOf(grid.minor, grid.major)) {
            for (k in 0 until segments.size / 4) {
                val x0 = segments[k * 4]
                val y0 = segments[k * 4 + 1]
                val x1 = segments[k * 4 + 2]
                val y1 = segments[k * 4 + 3]
                assertTrue(minOf(x0, x1) >= -1e-5f && maxOf(x0, x1) <= 4f + 1e-5f)
                assertTrue(minOf(y0, y1) >= -1e-5f && maxOf(y0, y1) <= 3f + 1e-5f)
                if (abs(x0 - x1) < 1e-6f) assertEquals(3f, abs(y1 - y0), 1e-5f) else assertEquals(4f, abs(x1 - x0), 1e-5f)
            }
        }
        val empty = ResultFloor.planGrid(RoomSurvey.empty(SurveyGap.NoWalls))
        assertEquals(0, empty.minor.size + empty.major.size)
    }

    @Test
    fun `the dollhouse's grid lies under its plinth, out past its margin`() {
        val fit = DollhouseFit(centerX = 1f, floorY = -1.2f, centerZ = 2f, width = 4f, height = 2.5f, depth = 3f, denominator = 10)
        val foot = fit.floorY - RoomDollhouse.BASE_DROP_M - RoomDollhouse.plinthThickness(fit.scale)
        assertEquals(foot, ResultFloor.dollhouseFoot(fit), 1e-6f)
        val m = RoomDollhouse.BASE_MARGIN_M
        val y = foot - ResultFloor.UNDER_M
        assertArrayEquals(
            floatArrayOf(1f - 2f - m, y, 2f - 1.5f - m, 1f + 2f + m, y, 2f + 1.5f + m),
            ResultFloor.dollhouseGridBounds(fit),
            1e-5f,
        )
        // The dimensions lie on the grid, over it.
        assertTrue(ResultFloor.dollhouseMeasureY(fit) > y)
        assertTrue(ResultFloor.dollhouseMeasureY(fit) < fit.floorY - RoomDollhouse.BASE_DROP_M)
    }
}
