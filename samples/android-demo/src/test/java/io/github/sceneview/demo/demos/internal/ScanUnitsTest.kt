package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * 3D Scan names a room in the floor plan's units (#4599): the replay's floor labels, its room line,
 * the floor its result says it covered and the camera path in its settings follow
 * [SurveyFormat.units] like the plan they open, feet and inches in US English, metres in every
 * other language, other English ones included, with that language's decimal separator.
 */
class ScanUnitsTest {

    private val room = RoomMeasure(floatArrayOf(0f, 0f, 3.73f, 0f, 3.73f, 3.76f, 0f, 3.76f), 3.73f, 3.76f, 0f)

    @Test
    fun `replay labels read feet and inches in US English`() {
        val (width, depth) = room.scanLabels(Locale.US)
        assertEquals(formatMeasureLength(3.73f, MeasureUnitSystem.Imperial, Locale.US), width)
        assertEquals(formatMeasureLength(3.76f, MeasureUnitSystem.Imperial, Locale.US), depth)
        assertEquals("12 ft 3 in" to "12 ft 4 in", width to depth)
    }

    @Test
    fun `replay summary reads feet in US English`() {
        val summary = room.scanSummary(Locale.US)
        assertEquals(room.summary(MeasureUnitSystem.Imperial, Locale.US), summary)
        assertEquals(false, summary.contains(" m"))
    }

    @Test
    fun `replay labels read metres in British English`() {
        assertEquals("3.73 m" to "3.76 m", room.scanLabels(Locale.UK))
    }

    @Test
    fun `replay summary reads metres in British English`() {
        assertEquals("3.73 m × 3.76 m · 14.0 m²", room.scanSummary(Locale.UK))
    }

    @Test
    fun `replay labels read metres in French with the locale's decimal separator`() {
        assertEquals("3,73 m" to "3,76 m", room.scanLabels(Locale.FRANCE))
    }

    @Test
    fun `replay summary reads metres in French`() {
        assertEquals(room.summary(MeasureUnitSystem.Metric, Locale.FRANCE), room.scanSummary(Locale.FRANCE))
        assertEquals(true, room.scanSummary(Locale.FRANCE).startsWith("3,73 m"))
    }

    @Test
    fun `floor covered reads square feet in US English`() {
        assertEquals("73 ft²", scanFloorArea(6.8f, Locale.US))
        assertEquals("4.3 ft²", scanFloorArea(0.4f, Locale.US))
    }

    @Test
    fun `floor covered reads square metres with a decimal comma in French`() {
        assertEquals("6,8 m²", scanFloorArea(6.8f, Locale.FRANCE))
        assertEquals("38 m²", scanFloorArea(38.2f, Locale.FRANCE))
    }

    @Test
    fun `floor covered takes the decimal separator of British English and German`() {
        assertEquals("6.8 m²", scanFloorArea(6.8f, Locale.UK))
        assertEquals("6,8 m²", scanFloorArea(6.8f, Locale.GERMANY))
    }

    /** The result names the figure the live coverage map counted up to, rounded the same way. */
    @Test
    fun `floor covered in metres rounds as the live map does`() {
        listOf(0.375f, 7.5f, 9.97f, 38.2f).forEach { m2 ->
            assertEquals(ScanAreaCopy.area(m2), scanFloorArea(m2, Locale.UK))
            assertEquals(ScanAreaCopy.area(m2).replace('.', ','), scanFloorArea(m2, Locale.FRANCE))
        }
    }

    @Test
    fun `camera path reads feet and inches in US English`() {
        assertEquals("46 ft 8 in", scanPathLength(14.236f, Locale.US))
        assertEquals("1 ft 5 in", scanPathLength(0.42f, Locale.US))
    }

    @Test
    fun `camera path reads metres with a decimal comma in French`() {
        assertEquals("14,2 m", scanPathLength(14.236f, Locale.FRANCE))
        assertEquals("42 cm", scanPathLength(0.42f, Locale.FRANCE))
    }

    @Test
    fun `camera path takes the separators of British English and German`() {
        assertEquals("14.2 m", scanPathLength(14.236f, Locale.UK))
        assertEquals("14,2 m", scanPathLength(14.236f, Locale.GERMANY))
        assertEquals("1.234 m", scanPathLength(1234.5f, Locale.GERMANY))
    }

    /** A figure the recording could not measure reads as zero in either system, never fails the screen. */
    @Test
    fun `an unmeasured figure reads as zero`() {
        assertEquals("0.0 ft²", scanFloorArea(Float.NaN, Locale.US))
        assertEquals("0 in", scanPathLength(Float.NaN, Locale.US))
        assertEquals("0,0 m²", scanFloorArea(Float.NaN, Locale.FRANCE))
        assertEquals("0 m", scanPathLength(Float.NaN, Locale.FRANCE))
    }
}
