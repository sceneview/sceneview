package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RoomPlanLayoutTest.Companion.WORDING
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The doors and windows marked on a traced room's walls reach its plan (#4783): drawn on their
 * wall, to scale, where they were marked along it; listed under their wall with the net wall area
 * after the walls; one line each in the shared text. A plan given none, as 3D Scan's, is drawn,
 * listed and shared exactly as before.
 */
class PlanOpeningsTest {

    /** A 4 × 3 m room, 2.50 m high: wall A along the top, B down the right, C along the bottom, D up the left. */
    private val room = planSurvey(0f, 0f, 4f, 0f, 4f, 3f, 0f, 3f)

    /** A 1.20 × 1.10 m window 1.00 m along wall A, its sill at 0.90 m. */
    private val window = PlanOpening(wall = 0, from = 1f, to = 2.2f, bottom = 0.9f, top = 2f)

    /** A 0.90 × 2.10 m door 0.50 m along wall B, on the floor. */
    private val door = PlanOpening(wall = 1, from = 0.5f, to = 1.4f, bottom = 0f, top = 2.1f)

    private val openings = PlanOpenings(listOf(window, door), netWallArea = 31.79f)

    private val wording = SurveyWording(
        heading = WORDING.heading,
        area = WORDING.area,
        dimensions = WORDING.dimensions,
        perimeter = WORDING.perimeter,
        height = WORDING.height,
        volume = WORDING.volume,
        rooms = WORDING.rooms,
        room = WORDING.room,
        total = WORDING.total,
        span = WORDING.span,
        walls = WORDING.walls,
        wall = WORDING.wall,
        notSeen = WORDING.notSeen,
        notMeasured = WORDING.notMeasured,
        estimated = WORDING.estimated,
        estimateNote = WORDING.estimateNote,
        spreadNote = WORDING.spreadNote,
        notice = WORDING.notice,
        madeWith = WORDING.madeWith,
        scaleBar = WORDING.scaleBar,
        gaps = WORDING.gaps,
        door = "Door",
        window = "Window",
        netWallArea = "Net wall area",
    )

    /** Half the text's size per character: a fixed stand-in for a font. */
    private val metrics = PlanMetrics(1f) { text, size -> text.length * size * 0.5f }

    private fun layout(survey: RoomSurvey, with: List<PlanOpening>? = null): RoomPlanDrawing {
        val texts = SurveyReport.planTexts(survey, wording)
        return if (with == null) {
            RoomPlanLayout.layout(survey, texts, 400f, 340f, metrics)
        } else {
            RoomPlanLayout.layout(survey, texts, 400f, 340f, metrics, openings = with)
        }
    }

    private fun near(a: Float, b: Float) = abs(a - b) < 0.01f

    // ── The drawing ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a window breaks its wall between its jambs, to scale, where it was marked`() {
        val drawing = layout(room, listOf(window))
        val scale = drawing.pxPerMetre
        val x0 = drawing.floor[0]
        val y0 = drawing.floor[1]
        val wallA = drawing.lines.filter { it.stroke == PlanStroke.Wall && near(it.y0, y0) && near(it.y1, y0) }
        // Wall A runs 0 → 1.00 m, then 2.20 → 4.00 m: nothing of it across the window.
        val spans = wallA.map { minOf(it.x0, it.x1) to maxOf(it.x0, it.x1) }.sortedBy { it.first }
        assertEquals(2, spans.size)
        assertTrue(spans.toString(), near(spans[0].first, x0) && near(spans[0].second, x0 + 1f * scale))
        assertTrue(spans.toString(), near(spans[1].first, x0 + 2.2f * scale) && near(spans[1].second, x0 + 4f * scale))
        val marks = drawing.lines.filter { it.stroke == PlanStroke.Opening }
        // Two jambs across the wall at 1.00 and 2.20 m, and the frame's two lines along the gap.
        val jambs = marks.filter { near(it.x0, it.x1) }.map { it.x0 }.sorted()
        assertEquals(2, jambs.size)
        assertTrue(jambs.toString(), near(jambs[0], x0 + 1f * scale) && near(jambs[1], x0 + 2.2f * scale))
        val frame = marks.filter { near(it.y0, it.y1) }
        assertEquals(2, frame.size)
        frame.forEach {
            assertTrue(near(minOf(it.x0, it.x1), x0 + 1f * scale))
            assertTrue(near(maxOf(it.x0, it.x1), x0 + 2.2f * scale))
        }
        assertTrue("a window sweeps no arc", drawing.lines.none { it.stroke == PlanStroke.Swing })
    }

    @Test
    fun `a door is drawn open into the room, its swing reaching its other jamb`() {
        val drawing = layout(room, listOf(door))
        val scale = drawing.pxPerMetre
        val x0 = drawing.floor[0]
        val y0 = drawing.floor[1]
        val right = x0 + 4f * scale
        val hinge = y0 + 0.5f * scale
        val strike = y0 + 1.4f * scale
        val wallB = drawing.lines.filter { it.stroke == PlanStroke.Wall && near(it.x0, right) && near(it.x1, right) }
        assertEquals(2, wallB.size)
        assertTrue(wallB.none { minOf(it.y0, it.y1) < strike - 0.01f && maxOf(it.y0, it.y1) > hinge + 0.01f })
        // The leaf: from the hinge, square to the wall, into the room (leftwards), as long as the door is wide.
        // Longer than a jamb, which crosses the wall the same way.
        val leaf = drawing.lines.single { it.stroke == PlanStroke.Opening && near(it.y0, it.y1) && it.x0 - it.x1 > 0.5f * scale }
        assertTrue(near(leaf.x0, right) && near(leaf.y0, hinge))
        assertTrue(near(right - leaf.x1, 0.9f * scale))
        val swing = drawing.lines.filter { it.stroke == PlanStroke.Swing }
        assertTrue(swing.size > 4)
        assertTrue("the swing starts at the open leaf's end", near(swing.first().x0, leaf.x1) && near(swing.first().y0, hinge))
        assertTrue("the swing ends on the far jamb", near(swing.last().x1, right) && near(swing.last().y1, strike))
        swing.forEach { assertTrue("the swing stays a door's width from its hinge", near(hypot(it.x1 - right, it.y1 - hinge), 0.9f * scale)) }
    }

    @Test
    fun `a plan with no openings is laid out line for line as before`() {
        val scan = planSurvey(0f, 0f, 3f, 0f, 4f, 1f, 4f, 3f, 0f, 3f, spread = 0.03f)
        for (survey in listOf(room, scan)) {
            val before = layout(survey)
            val after = layout(survey, emptyList())
            assertEquals(before.pxPerMetre, after.pxPerMetre)
            assertTrue(before.floor.contentEquals(after.floor))
            assertEquals(before.lines.size, after.lines.size)
            before.lines.zip(after.lines).forEach { (a, b) ->
                assertEquals(listOf(a.x0, a.y0, a.x1, a.y1), listOf(b.x0, b.y0, b.x1, b.y1))
                assertEquals(a.stroke, b.stroke)
            }
            assertEquals(before.texts.map { Triple(it.text, it.cx, it.cy) }, after.texts.map { Triple(it.text, it.cx, it.cy) })
            assertTrue(after.lines.none { it.stroke == PlanStroke.Opening || it.stroke == PlanStroke.Swing })
        }
    }

    // ── The walls' table and the text ────────────────────────────────────────────────────────

    @Test
    fun `the walls list names each wall's openings under it and the net wall area last`() {
        val rows = SurveyReport.wallRows(room, wording, openings).map { it.label to it.value }
        assertEquals(
            listOf(
                "Wall A" to "4.00 m",
                "Window" to "1.20 m × 1.10 m",
                "Wall B" to "3.00 m",
                "Door" to "0.90 m × 2.10 m",
                "Wall C" to "4.00 m",
                "Wall D" to "3.00 m",
                "Net wall area" to "31.8 m²",
            ),
            rows,
        )
    }

    @Test
    fun `the shared text has a line per opening and the net wall area`() {
        val text = SurveyReport.text(room, wording, "Kitchen", null, openings)
        val lines = text.lines()
        val walls = lines.indexOfFirst { it.startsWith("Walls: ") }
        assertEquals("Window · Wall A: 1.20 m × 1.10 m", lines[walls + 1])
        assertEquals("Door · Wall B: 0.90 m × 2.10 m", lines[walls + 2])
        assertEquals("Net wall area: 31.8 m²", lines[walls + 3])
        // The walls' own line stays as it was: a letter and a length each.
        assertEquals("Walls: A 4.00 m; B 3.00 m; C 4.00 m; D 3.00 m", lines[walls])
    }

    @Test
    fun `in feet the openings read as the Measure screen writes them`() {
        val feet = SurveyWording(
            heading = wording.heading, area = wording.area, dimensions = wording.dimensions, perimeter = wording.perimeter,
            height = wording.height, volume = wording.volume, rooms = wording.rooms, room = wording.room,
            total = wording.total, span = wording.span, walls = wording.walls, wall = wording.wall,
            notSeen = wording.notSeen, notMeasured = wording.notMeasured, estimated = wording.estimated,
            estimateNote = wording.estimateNote, spreadNote = wording.spreadNote, notice = wording.notice,
            madeWith = wording.madeWith, scaleBar = "%1\$s ft", gaps = wording.gaps, units = MeasureUnitSystem.Imperial,
            door = "Door", window = "Window", netWallArea = "Net wall area",
        )
        val text = SurveyReport.text(room, feet, "Kitchen", null, openings)
        assertTrue(text, "Door · Wall B: 2 ft 11 in × 6 ft 11 in" in text)
        assertTrue(text, "Net wall area: 342 ft²" in text)
    }

    @Test
    fun `a survey given no openings keeps its walls and its text as before`() {
        val scan = planSurvey(0f, 0f, 4f, 0f, 4f, 3f, 0f, 3f, spread = 0.03f)
        for (survey in listOf(room, scan)) {
            val before = SurveyReport.wallRows(survey, wording).map { it.label to it.value }
            val none = SurveyReport.wallRows(survey, wording, PlanOpenings.None).map { it.label to it.value }
            assertEquals(before, none)
            assertEquals(survey.walls.size, none.size)
            val text = SurveyReport.text(survey, wording, "Bedroom", "13 Oct 2026")
            assertEquals(text, SurveyReport.text(survey, wording, "Bedroom", "13 Oct 2026", PlanOpenings.None))
            assertFalse(text, "Door" in text || "Window" in text || "Net wall area" in text)
        }
    }

    @Test
    fun `an opening on the floor is a door, one higher up a window`() {
        assertTrue(door.door)
        assertTrue(door.copy(bottom = 0.08f).door)
        assertFalse(window.door)
        assertFalse(door.copy(bottom = 0.4f).door)
    }

    // ── From Measure's board to the plan ─────────────────────────────────────────────────────

    @Test
    fun `a door marked in Measure lands on the plan's wall, where it was marked along it`() {
        // Traced anticlockwise on the page: the plan walks it the other way round, so each wall runs backwards there.
        val outline = listOf(0f to 0f, 0f to 3f, 4f to 3f, 4f to 0f).map { (x, z) -> Position(x, -1.2f, z) }
        val survey = measureRoomSurvey(outline, height = 2.5f)!!
        // On the traced wall from (4, 3) to (4, 0), 0.5 m to 1.4 m from (4, 3).
        val marked = MeasureOpening(from = 0.5f, to = 1.4f, bottom = 0f, top = 2.1f, wall = 2)
        val onPlan = measurePlanOpeningsOnPlan(measurePlanOpenings(outline, listOf(marked)), netWallArea = 33.11f)
        val opening = onPlan.openings.single()
        val wall = survey.walls[opening.wall]
        // On the plan that wall runs from (4, 0) down to (4, 3): the door is 1.6 m to 2.5 m along it.
        assertEquals(listOf(4f, 0f, 4f, 3f), listOf(wall.ax, wall.ay, wall.bx, wall.by).map { Math.round(it * 100) / 100f })
        assertEquals(1.6f, opening.from, 1e-3f)
        assertEquals(2.5f, opening.to, 1e-3f)
        assertTrue(opening.door)
        assertEquals(33.11f, onPlan.netWallArea!!, 1e-3f)
    }
}
