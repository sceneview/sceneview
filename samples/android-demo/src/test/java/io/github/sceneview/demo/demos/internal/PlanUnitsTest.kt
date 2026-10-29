package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RoomPlanLayoutTest.Companion.WORDING
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * The plan's figures in feet and inches: which language gets them, how a scanned room's "±" reads
 * in them, and how a wall's label, an estimate and the scale bar are written.
 */
class PlanUnitsTest {

    private val imperial = imperialWording()

    /**
     * 4.03 × 3.12 m and 2.50 m high, scanned: every figure with a scatter of 0.03, the area's too —
     * a fixture's figure; a real scan's area spreads by its sides' scatter times their length.
     */
    private val scanned = planSurvey(0f, 0f, 4.03f, 0f, 4.03f, 3.12f, 0f, 3.12f, spread = 0.03f)

    @Test
    fun `only US English gets feet and inches`() {
        assertEquals(MeasureUnitSystem.Imperial, SurveyFormat.units(Locale.US))
        val others = listOf(Locale.UK, Locale.FRANCE, Locale.CANADA, Locale.GERMANY, Locale.forLanguageTag("es-US"))
        for (locale in others) {
            assertEquals(locale.toString(), MeasureUnitSystem.Metric, SurveyFormat.units(locale))
        }
    }

    @Test
    fun `a wording that names no units stays in metres`() {
        assertEquals(MeasureUnitSystem.Metric, WORDING.units)
        assertEquals("4.05 × 3.10 m ± 0.05", SurveyReport.rows(scanned, WORDING)[1].value)
    }

    @Test
    fun `a scanned room's scatter is rounded up to the inch and the square foot`() {
        val rows = SurveyReport.rows(scanned, imperial).map { it.value }
        assertEquals("135 ft² ± 1", rows[0])
        assertEquals("13 ft 3 in × 10 ft 3 in ± 2 in", rows[1])
        assertEquals("8 ft 2 in ± 2 in", rows[3])
        val texts = SurveyReport.planTexts(scanned, imperial)
        assertEquals("135 ft²", texts.area)
        assertEquals("± 1 ft²", texts.areaNote)
        assertEquals(listOf("13' 3\"", "10' 3\"", "13' 3\"", "10' 3\""), texts.walls)
    }

    @Test
    fun `a traced room's figures carry no scatter in feet either`() {
        val traced = planSurvey(0f, 0f, 4.03f, 0f, 4.03f, 3.12f, 0f, 3.12f)
        val rows = SurveyReport.rows(traced, imperial).map { it.value }
        assertEquals(listOf("135 ft²", "13 ft 3 in × 10 ft 3 in", "46 ft 11 in", "8 ft 2 in", "1110 ft³"), rows)
        assertNull(SurveyReport.planTexts(traced, imperial).areaNote)
    }

    @Test
    fun `a wall's label carries its inches into feet and keeps its estimate mark`() {
        assertEquals("1' 0\"", SurveyFormat.feetAndInchesMark(0.3048f))
        assertEquals("0' 11\"", SurveyFormat.feetAndInchesMark(0.28f))
        val guessed = SurveyWall(
            tag = "A",
            ax = 0f,
            ay = 0f,
            bx = 4f,
            by = 0f,
            length = SurveyFigure(4f, 0f, estimate = true),
            seenShare = 0f,
            seen = false,
            unseen = listOf(0f..4f),
        )
        assertEquals("≈ 13' 1\"", SurveyFormat.wall(guessed, MeasureUnitSystem.Imperial))
        assertEquals("≈ 4.00", SurveyFormat.wall(guessed, MeasureUnitSystem.Metric))
    }

    @Test
    fun `the scale bar is drawn at a round number of feet in feet, of metres otherwise`() {
        val bars = SurveyFormat.scaleBars(MeasureUnitSystem.Imperial)!!
        assertArrayEquals(floatArrayOf(1f, 2f, 5f, 10f, 20f, 50f), bars.map { it / 0.3048f }.toFloatArray(), 1e-4f)
        assertNull(SurveyFormat.scaleBars(MeasureUnitSystem.Metric))
        assertEquals("10 ft", SurveyReport.planTexts(scanned, imperial).scale(10 * 0.3048f))
    }

    @Test
    fun `the scale bar's feet are written through the wording, as its metres are`() {
        val translated = imperialWording(scaleBar = "%1\$s pi")
        assertEquals("10 pi", SurveyReport.planTexts(scanned, translated).scale(10 * 0.3048f))
    }
}
