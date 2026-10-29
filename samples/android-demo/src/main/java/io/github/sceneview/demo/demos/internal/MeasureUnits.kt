@file:Suppress("MatchingDeclarationName")

package io.github.sceneview.demo.demos.internal

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Length and area units of the `ar-measure` demo. One rule with its plan ([SurveyFormat.units]):
 * feet and inches in US English, metres in every other language. The settings sheet picks the
 * other one, for the screen and its plan alike.
 */
enum class MeasureUnitSystem {
    Metric,
    Imperial,
    ;

    companion object {
        /** The units Measure starts in under [locale]: the plan's ([SurveyFormat.units]). */
        fun forLocale(locale: Locale): MeasureUnitSystem = SurveyFormat.units(locale)
    }
}

private const val METERS_PER_INCH = 0.0254f
private const val INCHES_PER_FOOT = 12
private const val SQUARE_FEET_PER_SQUARE_METER = 10.763910f
private const val CUBIC_FEET_PER_CUBIC_METER = 35.314667f
private const val CENTIMETERS_PER_METER = 100f
private const val AREA_DECIMAL_LIMIT = 10f

/**
 * A length as a person reads it off a tape: "42 cm" under a metre, "1.42 m" above (with the
 * locale's decimal separator), "11 in" under a foot, "3 ft 4 in" above.
 *
 * Whole centimetres and whole inches only: ARCore on a phone is good to a few centimetres,
 * and a millimetre digit would promise a precision the pose does not have (`AR_MEASURE.md`).
 */
fun formatMeasureLength(meters: Float, system: MeasureUnitSystem, locale: Locale): String {
    val safe = meters.coerceAtLeast(0f)
    return when (system) {
        MeasureUnitSystem.Metric -> {
            val centimeters = (safe * CENTIMETERS_PER_METER).roundToInt()
            if (centimeters < CENTIMETERS_PER_METER) {
                "$centimeters cm"
            } else {
                String.format(locale, "%.2f m", centimeters / CENTIMETERS_PER_METER)
            }
        }
        MeasureUnitSystem.Imperial -> {
            val inches = (safe / METERS_PER_INCH).roundToInt()
            val feet = inches / INCHES_PER_FOOT
            val rest = inches % INCHES_PER_FOOT
            when {
                feet == 0 -> "$rest in"
                rest == 0 -> "$feet ft"
                else -> "$feet ft $rest in"
            }
        }
    }
}

/** An area: "3.24 m²" / "12.5 m²", or "35 ft²" / "4.2 ft²" — two significant decimals at most. */
fun formatMeasureArea(squareMeters: Float, system: MeasureUnitSystem, locale: Locale): String {
    val safe = squareMeters.coerceAtLeast(0f)
    return when (system) {
        MeasureUnitSystem.Metric ->
            if (safe < AREA_DECIMAL_LIMIT) {
                String.format(locale, "%.2f m²", safe)
            } else {
                String.format(locale, "%.1f m²", safe)
            }
        MeasureUnitSystem.Imperial -> {
            val squareFeet = safe * SQUARE_FEET_PER_SQUARE_METER
            if (squareFeet < AREA_DECIMAL_LIMIT) {
                String.format(locale, "%.1f ft²", squareFeet)
            } else {
                "${squareFeet.roundToInt()} ft²"
            }
        }
    }
}

/** A volume: "4.50 m³" / "30.0 m³", or "8.5 ft³" / "1059 ft³" — like [formatMeasureArea]. */
fun formatMeasureVolume(cubicMeters: Float, system: MeasureUnitSystem, locale: Locale): String {
    val safe = cubicMeters.coerceAtLeast(0f)
    return when (system) {
        MeasureUnitSystem.Metric ->
            if (safe < AREA_DECIMAL_LIMIT) {
                String.format(locale, "%.2f m³", safe)
            } else {
                String.format(locale, "%.1f m³", safe)
            }
        MeasureUnitSystem.Imperial -> {
            val cubicFeet = safe * CUBIC_FEET_PER_CUBIC_METER
            if (cubicFeet < AREA_DECIMAL_LIMIT) {
                String.format(locale, "%.1f ft³", cubicFeet)
            } else {
                "${cubicFeet.roundToInt()} ft³"
            }
        }
    }
}
