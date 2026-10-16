package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pins the dimensioned plan and the survey's words (#4540): labels that never overlap and stay
 * on the page, a round scale on paper, dashed marks for what was not seen, and a text that is a
 * few lines saying what is measured, what is an estimate and what is missing.
 */
class RoomPlanLayoutTest {

    /** A survey over the outline [corners] (x, y pairs, clockwise on the page); [unseen] walls are dashed estimates. */
    private fun surveyOf(vararg corners: Float, unseen: Set<Int> = emptySet(), measured: Boolean = true): RoomSurvey {
        val n = corners.size / 2
        val walls = List(n) { i ->
            val j = (i + 1) % n
            val length = hypot(corners[j * 2] - corners[i * 2], corners[j * 2 + 1] - corners[i * 2 + 1])
            val seen = i !in unseen
            SurveyWall(
                RoomSurveyor.tagOf(i), corners[i * 2], corners[i * 2 + 1], corners[j * 2], corners[j * 2 + 1],
                SurveyFigure(length, if (seen) 0.03f else 0f, estimate = !seen),
                if (seen) 1f else 0f, seen, if (seen) emptyList() else listOf(0f..length),
            )
        }
        var twice = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            twice += corners[i * 2] * corners[j * 2 + 1] - corners[j * 2] * corners[i * 2 + 1]
        }
        val length = (0 until n).maxOf { corners[it * 2] }
        val width = (0 until n).maxOf { corners[it * 2 + 1] }
        return RoomSurvey(
            walls, length, width,
            SurveyFigure(length, 0.03f).takeIf { measured }, SurveyFigure(width, 0.03f),
            SurveyFigure(abs(twice) / 2, 0.3f).takeIf { measured },
            SurveyFigure(walls.sumOf { it.length.value.toDouble() }.toFloat(), 0.1f).takeIf { measured },
            SurveyFigure(2.5f, 0.02f).takeIf { measured },
            if (measured) emptyList() else listOf(SurveyGap.MissingWall, SurveyGap.Ceiling), 0f, 0.02f, 0f,
        )
    }

    private val rectangle = surveyOf(0f, 0f, 5f, 0f, 5f, 4f, 0f, 4f)
    private val lShape = surveyOf(0f, 0f, 5f, 0f, 5f, 2.5f, 3f, 2.5f, 3f, 4f, 0f, 4f)

    /** A room with a 40 cm recess and a 30 cm jog: walls too short to carry their own label along them. */
    private val recess =
        surveyOf(0f, 0f, 2f, 0f, 2f, 0.4f, 2.6f, 0.4f, 2.6f, 0f, 5f, 0f, 5f, 3.7f, 4.7f, 3.7f, 4.7f, 4f, 0f, 4f)

    /** Half the text's size per character: a fixed stand-in for a font. */
    private val metrics = PlanMetrics(1f) { text, size -> text.length * size * 0.5f }

    private fun layout(survey: RoomSurvey, width: Float = 340f, height: Float = 300f, scales: List<Float>? = null) =
        RoomPlanLayout.layout(survey, SurveyReport.planTexts(survey, WORDING), width, height, metrics, scales)

    private fun assertClean(drawing: RoomPlanDrawing) {
        val texts = drawing.texts
        for (i in texts.indices) {
            val a = texts[i]
            val onPage = a.left >= 0f && a.top >= 0f && a.right <= drawing.width && a.bottom <= drawing.height
            assertTrue("${a.text} leaves the page", onPage)
            for (j in i + 1 until texts.size) assertFalse("${a.text} over ${texts[j].text}", a.overlaps(texts[j]))
        }
        for (line in drawing.lines) {
            assertTrue(line.x0 >= 0f && line.x1 <= drawing.width + 0.5f)
            assertTrue(line.y0 >= 0f && line.y1 <= drawing.height + 0.5f)
        }
    }

    @Test
    fun `every wall of a rectangle is dimensioned, the area in the middle, nothing overlapping`() {
        val drawing = layout(rectangle)

        assertClean(drawing)
        val dimensions = drawing.texts.filter { it.role == PlanTextRole.Dimension }.map { it.text }
        assertEquals(listOf("5.00", "4.00", "5.00", "4.00"), dimensions)
        val area = drawing.texts.single { it.role == PlanTextRole.Area }
        assertEquals("20.0 m²", area.text)
        // In the middle of the floor.
        val xs = drawing.floor.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = drawing.floor.filterIndexed { i, _ -> i % 2 == 1 }
        assertEquals((xs.min() + xs.max()) / 2, area.cx, 1f)
        assertTrue(area.cy > ys.min() && area.cy < ys.max())
        assertEquals(1, drawing.texts.count { it.role == PlanTextRole.Scale })
        assertEquals(4, drawing.lines.count { it.stroke == PlanStroke.Wall })
    }

    @Test
    fun `dimension lines stand outside the outline`() {
        val drawing = layout(rectangle)
        val xs = drawing.floor.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = drawing.floor.filterIndexed { i, _ -> i % 2 == 1 }

        for (line in drawing.lines.filter { it.stroke == PlanStroke.Dimension }) {
            val outside = line.y0 < ys.min() || line.y0 > ys.max() || line.x0 < xs.min() || line.x0 > xs.max()
            assertTrue(outside)
        }
        assertEquals(4, drawing.lines.count { it.stroke == PlanStroke.Dimension })
    }

    @Test
    fun `an L-shaped room and a room with short walls keep their labels apart`() {
        for (survey in listOf(lShape, recess)) {
            for ((width, height) in listOf(340f to 300f, 300f to 300f, 515f to 388f, 420f to 262f)) {
                val drawing = layout(survey, width, height)
                assertClean(drawing)
                assertEquals(survey.walls.size, drawing.texts.count { it.role == PlanTextRole.Dimension })
            }
        }
    }

    @Test
    fun `the plan is to scale, and on paper the scale is a round one`() {
        val scales = listOf(20, 25, 50, 75, 100, 150, 200).map { 2834.65f / it }
        val drawing = layout(rectangle, 515f, 388f, scales)

        assertTrue(drawing.pxPerMetre in scales)
        // The largest of them the room fits at.
        assertEquals(2834.65f / 50, drawing.pxPerMetre, 0.01f)
        assertEquals(5f * drawing.pxPerMetre, drawing.floor[2] - drawing.floor[0], 0.01f)
        assertEquals(4f * drawing.pxPerMetre, drawing.floor[5] - drawing.floor[3], 0.01f)
        assertClean(drawing)
    }

    @Test
    fun `a wall the scan did not see is dashed and its dimension marked`() {
        val survey = surveyOf(0f, 0f, 5f, 0f, 5f, 4f, 0f, 4f, unseen = setOf(1), measured = false)
        val drawing = layout(survey)

        assertEquals(1, drawing.lines.count { it.stroke == PlanStroke.WallUnseen })
        assertEquals(3, drawing.lines.count { it.stroke == PlanStroke.Wall })
        assertEquals("≈ 4.00", drawing.texts.filter { it.role == PlanTextRole.Dimension }[1].text)
        // No area was given: none is written on the floor.
        assertNull(drawing.texts.firstOrNull { it.role == PlanTextRole.Area })
        assertClean(drawing)
    }

    @Test
    fun `lengths round to 5 cm and areas to a tenth of a square metre`() {
        assertEquals("3.60", SurveyFormat.length(3.62f))
        assertEquals("3.65", SurveyFormat.length(3.63f))
        assertEquals("14.3", SurveyFormat.area(14.26f))
        assertEquals("14.2", SurveyFormat.area(14.24f))
        assertEquals("19.9 m² ± 0.3", SurveyReport.area(SurveyFigure(19.9f, 0.3f)))
        assertEquals("5.00 m ± 0.05", SurveyReport.length(SurveyFigure(4.98f, 0.03f)))
    }

    @Test
    fun `an estimate is marked and carries no plus-minus, on the table, the plan and the text`() {
        assertEquals("≈ 5.00 m", SurveyReport.length(SurveyFigure.of(4.98f, 0.25f, estimate = true)))
        assertEquals("≈ 19.9 m²", SurveyReport.area(SurveyFigure.of(19.9f, 0.3f, estimate = true)))
        val rough = RoomSurvey(
            rectangle.walls, 5f, 4f,
            SurveyFigure(5f, 0f, estimate = true), SurveyFigure(4f, 0.03f),
            SurveyFigure(20f, 0f, estimate = true), SurveyFigure(18f, 0f, estimate = true),
            SurveyFigure(2.5f, 0f, estimate = true), listOf(SurveyGap.ObliqueWall), 0f, 0.02f, 0f,
        )

        val rows = SurveyReport.rows(rough, WORDING).map { it.value }
        assertEquals(listOf("≈ 20.0 m²", "≈ 5.00 × 4.00 m", "≈ 18.00 m", "≈ 2.50 m"), rows)
        val texts = SurveyReport.planTexts(rough, WORDING)
        assertEquals("≈ 20.0 m²", texts.area)
        assertNull(texts.areaNote)
        assertClean(layout(rough))
        val text = SurveyReport.text(rough, WORDING, "Attic", null)
        assertEquals("Attic", text.lines().first())
        assertTrue(text.contains("Floor area: ≈ 20.0 m² · Length × width: ≈ 5.00 × 4.00 m · Perimeter: ≈ 18.00 m"))
        assertTrue(text.contains("This wall is not square to the others."))
        assertTrue(text.contains(WORDING.estimateNote))
        // Its walls are measured: their ± is explained.
        assertTrue(text.contains(WORDING.spreadNote))
    }

    @Test
    fun `the screen, the PDF and the text read the same rows and the same notes`() {
        val survey = surveyOf(0f, 0f, 5f, 0f, 5f, 4f, 0f, 4f, unseen = setOf(1), measured = false)
        val text = SurveyReport.text(survey, WORDING, "Bedroom", "13 Oct 2026")

        for (row in SurveyReport.rows(survey, WORDING)) {
            assertTrue(row.value, text.contains(row.label + ": " + row.value))
        }
        SurveyReport.wallRows(survey, WORDING).forEachIndexed { i, row ->
            assertTrue(row.value, text.contains(survey.walls[i].tag + " " + row.value))
        }
        for (note in SurveyReport.notes(survey, WORDING)) assertTrue(note, text.contains(note))
        for (hint in SurveyReport.hints(survey, WORDING)) assertTrue(hint, text.contains(hint))
        assertEquals(listOf(WORDING.estimateNote, WORDING.spreadNote), SurveyReport.notes(survey, WORDING))
        // Nothing measured, nothing to explain a ± for.
        assertEquals(emptyList<String>(), SurveyReport.notes(RoomSurvey.empty(SurveyGap.NoWalls), WORDING))
    }

    @Test
    fun `the shared text is a few plain lines ending on the notice`() {
        val text = SurveyReport.text(rectangle, WORDING, "Living room", "13 Oct 2026")
        val lines = text.lines()

        assertTrue(lines.size in 3..7)
        assertEquals("Living room · 13 Oct 2026", lines.first())
        assertTrue(lines[1].contains("Floor area: 20.0 m² ± 0.3"))
        assertTrue(lines[1].contains("Length × width: 5.00 × 4.00 m ± 0.05"))
        assertTrue(text.contains("Ceiling height: 2.50 m ± 0.05"))
        // A measured wall keeps its ±, as on the screen and the PDF.
        assertTrue(text.contains("Walls: A 5.00 m ± 0.05, B 4.00 m ± 0.05, C 5.00 m ± 0.05, D 4.00 m ± 0.05"))
        assertTrue(lines.last().startsWith(WORDING.notice))
        // Every ± is explained, once.
        assertEquals(WORDING.spreadNote, lines[lines.size - 2])
        // Nothing here is an estimate: the text does not explain a mark it does not use.
        assertFalse(text.contains("≈"))
        assertFalse(text.contains(WORDING.estimateNote))
    }

    @Test
    fun `a scan without a date gives none, never today's`() {
        val text = SurveyReport.text(rectangle, WORDING, "Living room", null)

        assertEquals("Living room", text.lines().first())
        assertEquals("Living room", SurveyReport.heading(WORDING, "Living room", null))
    }

    @Test
    fun `the text of a scan missing a wall gives no area and says what to scan`() {
        val survey = surveyOf(0f, 0f, 5f, 0f, 5f, 4f, 0f, 4f, unseen = setOf(1), measured = false)
        val text = SurveyReport.text(survey, WORDING, "Bedroom", "13 Oct 2026")
        val rows = SurveyReport.rows(survey, WORDING)

        assertTrue(text.lines().size in 3..8)
        assertTrue(text.contains("Floor area: not measured"))
        assertTrue(text.lines().contains("Scan the missing wall. Raise the phone to the ceiling."))
        assertTrue(text.contains("Ceiling height: not seen"))
        assertTrue(text.contains("B ≈ 4.00 m · not seen"))
        assertFalse(text.contains("≈ 4.00 m ±"))
        assertTrue(text.contains(WORDING.estimateNote))
        assertTrue(text.contains(WORDING.notice))
        assertFalse(text.contains("m²"))
        assertTrue(rows.first().muted)
        assertEquals("Between facing walls", rows[1].label)
        assertEquals("≈ 4.00 m · not seen", SurveyReport.wallRows(survey, WORDING)[1].value)
        assertNotNull(SurveyReport.areaHint(survey, WORDING))
    }

    @Test
    fun `a scan with nothing to draw still shares what is missing`() {
        val text = SurveyReport.text(RoomSurvey.empty(SurveyGap.NoWalls), WORDING, "Hall", "13 Oct 2026")

        assertTrue(text.lines().size in 3..6)
        assertFalse(text.contains("±"))
        assertTrue(text.contains("not measured"))
        assertTrue(text.contains(WORDING.notice))
    }

    internal companion object {
        val WORDING = SurveyWording(
            heading = "%1\$s · %2\$s",
            area = "Floor area",
            dimensions = "Length × width",
            perimeter = "Perimeter",
            height = "Ceiling height",
            span = "Between facing walls",
            walls = "Walls",
            wall = "Wall %1\$s",
            notSeen = "not seen",
            notMeasured = "not measured",
            estimated = "estimated",
            estimateNote = "≈ and dashed walls are estimates.",
            spreadNote = "± is the scatter of the scan, not an accuracy.",
            notice = "Indicative plan — not a certified measurement.",
            madeWith = "Made with SceneView.",
            scaleBar = "%1\$s m",
            gaps = mapOf(
                SurveyGap.NoDepth to "This scan has no depth.",
                SurveyGap.NoWalls to "Scan the walls.",
                SurveyGap.MissingWall to "Scan the missing wall.",
                SurveyGap.ObliqueWall to "This wall is not square to the others.",
                SurveyGap.Ceiling to "Raise the phone to the ceiling.",
            ),
        )
    }
}
