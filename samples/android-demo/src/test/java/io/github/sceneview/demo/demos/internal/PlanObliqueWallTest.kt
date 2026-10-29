package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RoomPlanLayoutTest.Companion.WORDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A wall square to neither side of the page is dimensioned along itself: its dimension line runs
 * parallel to it, outside the room, as long on the page as the length its label gives — not the
 * wall's shadow on the nearest axis with the true length written beside it.
 */
class PlanObliqueWallTest {

    /** Half the text's size per character: a fixed stand-in for a font. */
    private val metrics = PlanMetrics(1f) { text, size -> text.length * size * 0.5f }

    private fun layout(
        survey: RoomSurvey,
        width: Float = 340f,
        height: Float = 300f,
        wording: SurveyWording = WORDING,
    ) = RoomPlanLayout.layout(survey, SurveyReport.planTexts(survey, wording), width, height, metrics)

    /** 4 × 3 m with its top-right corner cut at 45°: wall B runs from (3, 0) to (4, 1), 1.41 m long. */
    private val cut = planSurvey(0f, 0f, 3f, 0f, 4f, 1f, 4f, 3f, 0f, 3f)

    /** 4 × 3 m, its right wall leaning out 5° from square. */
    private val fiveDegrees = run {
        val lean = 3f * tan(Math.toRadians(5.0)).toFloat()
        planSurvey(0f, 0f, 4f, 0f, 4f + lean, 3f, 0f, 3f)
    }

    /** 4 m at the top, 5 m at the bottom, 3 m high: the whole right side is one leaning wall, 3.16 m long. */
    private val trapezoid = planSurvey(0f, 0f, 4f, 0f, 5f, 3f, 0f, 3f)

    /** A tall page, a square one and a wide one. */
    private val pages = listOf(340f to 300f, 300f to 340f, 600f to 260f)

    /** Wall [i] on the page: its start, and its direction and outward normal, both unit. */
    private class Side(val ax: Float, val ay: Float, val bx: Float, val by: Float) {
        val span = hypot(bx - ax, by - ay)
        val dx = (bx - ax) / span
        val dy = (by - ay) / span

        // Clockwise on a page whose y goes down: the outside is on the left of the walk.
        val nx = dy
        val ny = -dx
        val mx = (ax + bx) / 2
        val my = (ay + by) / 2
    }

    private fun side(drawing: RoomPlanDrawing, i: Int): Side {
        val n = drawing.floor.size / 2
        val j = (i + 1) % n
        return Side(drawing.floor[i * 2], drawing.floor[i * 2 + 1], drawing.floor[j * 2], drawing.floor[j * 2 + 1])
    }

    private fun PlanLine.length() = hypot(x1 - x0, y1 - y0)

    /** The sine of the angle between this line and the unit direction ([dx], [dy]): 0 when parallel. */
    private fun PlanLine.skew(dx: Float, dy: Float) = abs(((x1 - x0) * dy - (y1 - y0) * dx) / length())

    private fun assertClean(drawing: RoomPlanDrawing) {
        val texts = drawing.texts
        for (i in texts.indices) {
            val a = texts[i]
            val onPage = a.left >= 0f && a.top >= 0f && a.right <= drawing.width && a.bottom <= drawing.height
            assertTrue("${a.text} leaves the page", onPage)
            for (j in i + 1 until texts.size) assertFalse("${a.text} over ${texts[j].text}", a.overlaps(texts[j]))
        }
        for (line in drawing.lines) {
            val onPage = minOf(line.x0, line.x1) >= -0.5f && maxOf(line.x0, line.x1) <= drawing.width + 0.5f &&
                minOf(line.y0, line.y1) >= -0.5f && maxOf(line.y0, line.y1) <= drawing.height + 0.5f
            assertTrue("a ${line.stroke} line leaves the page", onPage)
        }
    }

    @Test
    fun `an oblique wall's dimension runs along it, outside the room, at its true length`() {
        val drawing = layout(cut)
        val b = side(drawing, 1)

        val along = drawing.lines.filter { it.stroke == PlanStroke.Dimension && it.skew(b.dx, b.dy) < 1e-3f }
        assertEquals(1, along.size)
        val line = along.single()
        assertEquals(sqrt(2f) * drawing.pxPerMetre, line.length(), 0.5f)
        val out = ((line.x0 + line.x1) / 2 - b.mx) * b.nx + ((line.y0 + line.y1) / 2 - b.my) * b.ny
        assertTrue("$out", out > 0f)
    }

    @Test
    fun `its extension lines leave the wall's own ends, square to it`() {
        val drawing = layout(cut)
        val b = side(drawing, 1)

        val square = drawing.lines.filter { it.stroke == PlanStroke.Extension && it.skew(b.nx, b.ny) < 1e-3f }
        for ((px, py) in listOf(b.ax to b.ay, b.bx to b.by)) {
            assertTrue("no extension line from ($px, $py)", square.any { hypot(it.x0 - px, it.y0 - py) < 6f })
        }
    }

    @Test
    fun `its label stands beside its middle, outside the room, clear of the rest`() {
        val drawing = layout(cut)
        val b = side(drawing, 1)

        val label = drawing.texts.single { it.role == PlanTextRole.Dimension && it.text == "1.40" }
        val out = (label.cx - b.mx) * b.nx + (label.cy - b.my) * b.ny
        val aside = (label.cx - b.mx) * b.dx + (label.cy - b.my) * b.dy
        assertTrue("$out", out > 20f)
        assertEquals(0f, aside, 1f)
        assertClean(drawing)
    }

    @Test
    fun `a wall five degrees off square is dimensioned at its true length, not its shadow`() {
        val room = fiveDegrees
        val drawing = layout(room)
        val b = side(drawing, 1)
        val wall = room.walls[1].length.value * drawing.pxPerMetre

        val along = drawing.lines.filter { it.stroke == PlanStroke.Dimension && it.skew(b.dx, b.dy) < 1e-3f }
        assertEquals(1, along.size)
        assertEquals(wall, along.single().length(), wall * 1e-3f)
        assertClean(drawing)
    }

    @Test
    fun `the square walls keep their dimension chains`() {
        val drawing = layout(cut)
        for (i in listOf(0, 2, 3, 4)) {
            val wall = side(drawing, i)
            val own = drawing.lines.filter {
                it.stroke == PlanStroke.Dimension && it.skew(wall.dx, wall.dy) < 1e-3f &&
                    abs(it.length() - wall.span) < 0.5f
            }
            assertEquals("wall ${cut.walls[i].tag}", 1, own.size)
        }
    }

    /**
     * The side has no chain of its own, so nothing but the leaning wall's own reach widens its
     * margin. Were it left at a bare side's, the dimension line, its ticks and its label would run
     * off the first two pages.
     */
    @Test
    fun `a side that is one leaning wall keeps its dimension and its label on the page`() {
        for ((width, height) in pages) {
            assertClean(layout(trapezoid, width, height))
        }
    }

    /** A wall's label in feet and inches, `13' 10"`, is half as wide again as in metres, `4.20`. */
    @Test
    fun `in feet and inches the leaning walls' dimensions stay on the page and clear of each other`() {
        val imperial = imperialWording()
        for ((name, room) in listOf("cut corner" to cut, "five degrees" to fiveDegrees, "trapezoid" to trapezoid)) {
            for ((width, height) in pages) {
                val drawing = layout(room, width, height, imperial)
                val walls = drawing.texts.filter { it.role == PlanTextRole.Dimension }.map { it.text }
                assertTrue("$name: $walls", walls.size == room.walls.size && walls.all { it.endsWith("\"") })
                assertClean(drawing)
            }
        }
    }

    /**
     * The area's spot is searched over the points inside the floor. A long leaning wall is where
     * a test that only counts the walls running up and down, by their first end, lets a point
     * outside the room through: the widest such point is outside, left of the leaning wall.
     */
    @Test
    fun `the area stands inside a room with a cut corner or a long leaning wall`() {
        val leaning = planSurvey(3f, 0f, 4f, 0f, 4f, 3f, 0f, 3f)
        for ((name, room) in listOf("cut corner" to cut, "leaning wall" to leaning)) {
            val drawing = layout(room)
            val area = drawing.texts.single { it.role == PlanTextRole.Area }
            var crossings = 0
            val n = drawing.floor.size / 2
            for (i in 0 until n) {
                val wall = side(drawing, i)
                if ((wall.ay > area.cy) == (wall.by > area.cy)) continue
                val at = wall.ax + (area.cy - wall.ay) * (wall.bx - wall.ax) / (wall.by - wall.ay)
                if (at > area.cx) crossings++
            }
            assertEquals("$name: (${area.cx}, ${area.cy})", 1, crossings % 2)
        }
    }
}
