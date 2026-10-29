package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RoomPlanLayoutTest.Companion.WORDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A room traced corner by corner rather than scanned: its figures are measured end to end and
 * carry no scatter. Its plan, its table and its shared text give them bare — a "± 0.05" there
 * would read as a scatter the room does not have — and say nothing about a scan. A scanned room
 * keeps its ± and the note that says what it is.
 */
class PlanMeasuredFiguresTest {

    /** 4.03 × 3.12 m and 2.50 m high, traced with a tape. */
    private val traced = planSurvey(0f, 0f, 4.03f, 0f, 4.03f, 3.12f, 0f, 3.12f)

    /** The same room scanned: every figure with a 3 cm scatter. */
    private val scanned = planSurvey(0f, 0f, 4.03f, 0f, 4.03f, 3.12f, 0f, 3.12f, spread = 0.03f)

    @Test
    fun `a figure without a scatter is given bare, to the plan's step`() {
        assertEquals("4.05 m", SurveyReport.length(SurveyFigure(4.03f, 0f)))
        assertEquals("12.6 m²", SurveyReport.area(SurveyFigure(12.57f, 0f)))
        assertEquals("31.4 m³", SurveyReport.volume(SurveyFigure(31.43f, 0f)))
    }

    @Test
    fun `a traced room's figures, rooms and walls carry no plus-minus`() {
        assertEquals(
            listOf("12.6 m²", "4.05 × 3.10 m", "14.30 m", "2.50 m", "31.4 m³"),
            SurveyReport.rows(traced, WORDING).map { it.value },
        )
        assertEquals(
            listOf("12.6 m² · 31.4 m³", "12.6 m² · 31.4 m³"),
            SurveyReport.roomRows(SurveyReport.areas(traced, WORDING), WORDING).map { it.value },
        )
        assertEquals(
            listOf("4.05 m", "3.10 m", "4.05 m", "3.10 m"),
            SurveyReport.wallRows(traced, WORDING).map { it.value },
        )
    }

    @Test
    fun `a traced room's plan writes no scatter under its area`() {
        val texts = SurveyReport.planTexts(traced, WORDING)
        assertEquals("12.6 m²", texts.area)
        assertNull(texts.areaNote)
    }

    @Test
    fun `a traced room says nothing about a scan`() {
        assertEquals(emptyList<String>(), SurveyReport.notes(traced, WORDING))
        val text = SurveyReport.text(traced, WORDING, "Kitchen", "21 Oct 2026")
        assertFalse(text, text.contains("±"))
        assertFalse(text, text.contains(WORDING.spreadNote))
        assertTrue(text, text.lines().last().startsWith(WORDING.notice))
    }

    @Test
    fun `a scanned room keeps its plus-minus and the note that says what it is`() {
        assertEquals("5.00 m ± 0.05", SurveyReport.length(SurveyFigure(4.98f, 0.03f)))
        assertEquals(
            listOf("12.6 m² ± 0.1", "4.05 × 3.10 m ± 0.05", "14.30 m ± 0.05", "2.50 m ± 0.05", "31.4 m³ ± 0.5"),
            SurveyReport.rows(scanned, WORDING).map { it.value },
        )
        assertEquals("± 0.1 m²", SurveyReport.planTexts(scanned, WORDING).areaNote)
        assertEquals(listOf(WORDING.spreadNote), SurveyReport.notes(scanned, WORDING))
    }

    @Test
    fun `only the figures that carry a scatter show one, and then the note says what it is`() {
        // A traced floor under a scanned ceiling height.
        val mixed = RoomSurvey(
            walls = traced.walls,
            planLength = traced.planLength,
            planWidth = traced.planWidth,
            length = traced.length,
            width = traced.width,
            area = traced.area,
            perimeter = traced.perimeter,
            ceilingHeight = SurveyFigure(2.5f, 0.02f),
            gaps = emptyList(),
            floorY = 0f,
            floorThickness = 0f,
            yaw = 0f,
        )
        assertEquals(
            listOf("12.6 m²", "4.05 × 3.10 m", "14.30 m", "2.50 m ± 0.05", "31.4 m³ ± 0.3"),
            SurveyReport.rows(mixed, WORDING).map { it.value },
        )
        assertNull(SurveyReport.planTexts(mixed, WORDING).areaNote)
        assertEquals(listOf(WORDING.spreadNote), SurveyReport.notes(mixed, WORDING))
    }
}
