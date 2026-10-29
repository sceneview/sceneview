package io.github.sceneview.demo.demos.internal

import java.text.DecimalFormatSymbols
import java.util.Locale

/*
 * 3D Scan names a room in the floor plan's units (#4599): the replay's floor labels, the room line
 * under its timeline, the floor its result says it covered, the camera path in its settings and
 * the plan the scan opens all read [SurveyFormat.units] of the same locale, so one screen never
 * says feet and the next metres.
 */

/** [RoomMeasure.summary] in the floor plan's units under [locale]: what 3D Scan names the room. */
fun RoomMeasure.scanSummary(locale: Locale): String = summary(SurveyFormat.units(locale), locale)

/** The width and depth labels 3D Scan draws on the floor, in the floor plan's units under [locale]. */
fun RoomMeasure.scanLabels(locale: Locale): Pair<String, String> {
    val units = SurveyFormat.units(locale)
    return formatMeasureLength(width, units, locale) to formatMeasureLength(depth, units, locale)
}

/**
 * The floor a scan covered, as its result names it, in the floor plan's units under [locale]:
 * `73 ft²` in US English; elsewhere rounded as the live map rounds it ([ScanAreaCopy.area]), with
 * the language's decimal separator (`6,8 m²` in French).
 */
fun scanFloorArea(squareMetres: Float, locale: Locale): String {
    val area = measured(squareMetres)
    return when (SurveyFormat.units(locale)) {
        MeasureUnitSystem.Imperial -> SurveyFormat.squareFeet(area)
        MeasureUnitSystem.Metric ->
            ScanAreaCopy.area(area).replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator)
    }
}

/**
 * How far the phone went, as the replay's settings name it, in the floor plan's units under
 * [locale]: `46 ft 8 in` in US English, `14,2 m` in French, `42 cm` under a metre.
 */
fun scanPathLength(metres: Float, locale: Locale): String {
    val length = measured(metres)
    return when (SurveyFormat.units(locale)) {
        MeasureUnitSystem.Imperial -> SurveyFormat.feetAndInches(length)
        MeasureUnitSystem.Metric -> ArDebugFormat.distance(length, locale)
    }
}

/** A figure the recording could not measure reads as zero: the formatters round, and NaN cannot be. */
private fun measured(value: Float): Float = if (value.isFinite() && value > 0f) value else 0f
