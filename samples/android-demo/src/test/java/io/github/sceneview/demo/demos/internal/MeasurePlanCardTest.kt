package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * One unit rule for Measure and its plan: feet and inches in US English, metres everywhere else
 * ([SurveyFormat.units]). The Measure screen starts there ([MeasureUnitSystem.forLocale]); a unit
 * picked in its settings goes to the plan's card as well: one page, one unit system. In metres the
 * card writes every length in metres, as the plan beside it does: `0.90 m`, never `90 cm`.
 */
class MeasurePlanCardTest {

    // A 0.9 × 2.1 m door on wall B and a 1.2 × 1 m window on wall C; 31.91 m² of wall left.
    private val openings = listOf(
        MeasurePlanOpening("B", MeasureOpening(from = 0.5f, to = 1.4f, bottom = 0f, top = 2.1f, wall = 1)),
        MeasurePlanOpening("C", MeasureOpening(from = 1f, to = 2.2f, bottom = 0.9f, top = 1.9f, wall = 2)),
    )

    /** The card under [locale], in the Measure screen's [units]: by default, the ones it starts with. */
    private fun card(locale: Locale, units: MeasureUnitSystem = MeasureUnitSystem.forLocale(locale)) =
        measurePlanCard(NET_WALL_AREA, openings, units, locale)

    @Test
    fun `Spanish in the US reads the screen and the card in metres`() {
        val locale = Locale.forLanguageTag("es-US")
        assertEquals(MeasureUnitSystem.Metric, MeasureUnitSystem.forLocale(locale))
        val card = card(locale)
        assertEquals("31.9 m²", card.netWallArea)
        assertEquals(
            listOf(MeasurePlanCardOpening("B", "0.90 m", "2.10 m"), MeasurePlanCardOpening("C", "1.20 m", "1.00 m")),
            card.openings,
        )
    }

    @Test
    fun `English in Liberia reads the screen and the card in metres`() {
        val locale = Locale.forLanguageTag("en-LR")
        assertEquals(MeasureUnitSystem.Metric, MeasureUnitSystem.forLocale(locale))
        val card = card(locale)
        assertEquals("31.9 m²", card.netWallArea)
        assertEquals(MeasurePlanCardOpening("B", "0.90 m", "2.10 m"), card.openings.first())
    }

    @Test
    fun `US English reads the screen and the card in feet and inches by default`() {
        assertEquals(MeasureUnitSystem.Imperial, MeasureUnitSystem.forLocale(Locale.US))
        val card = card(Locale.US)
        assertEquals("343 ft²", card.netWallArea)
        assertEquals(
            listOf(MeasurePlanCardOpening("B", "2 ft 11 in", "6 ft 11 in"), MeasurePlanCardOpening("C", "3 ft 11 in", "3 ft 3 in")),
            card.openings,
        )
    }

    @Test
    fun `French reads the card in metres with its own decimal mark`() {
        val card = card(Locale.FRANCE)
        assertEquals("31,9 m²", card.netWallArea)
        assertEquals(MeasurePlanCardOpening("B", "0,90 m", "2,10 m"), card.openings.first())
    }

    @Test
    fun `French writes a door narrower than a metre in metres, as its height`() {
        val door = MeasurePlanOpening("B", MeasureOpening(from = 0.5f, to = 1.43f, bottom = 0f, top = 2.04f, wall = 1))
        val card = measurePlanCard(NET_WALL_AREA, listOf(door), MeasureUnitSystem.Metric, Locale.FRANCE)
        assertEquals(listOf(MeasurePlanCardOpening("B", "0,93 m", "2,04 m")), card.openings)
    }

    @Test
    fun `French with imperial picked in the settings reads the card in feet and inches`() {
        val card = card(Locale.FRANCE, MeasureUnitSystem.Imperial)
        assertEquals("343 ft²", card.netWallArea)
        assertEquals(MeasurePlanCardOpening("B", "2 ft 11 in", "6 ft 11 in"), card.openings.first())
    }

    @Test
    fun `US English with metric picked in the settings reads the card in metres`() {
        val card = card(Locale.US, MeasureUnitSystem.Metric)
        assertEquals("31.9 m²", card.netWallArea)
        assertEquals(MeasurePlanCardOpening("B", "0.90 m", "2.10 m"), card.openings.first())
    }

    @Test
    fun `the Measure screen starts in the plan's units, whatever the locale`() {
        val locales = listOf("en-US", "es-US", "en-LR", "my-MM", "en-GB", "fr-FR", "de-DE", "en-CA").map(Locale::forLanguageTag)
        for (locale in locales) {
            assertEquals(locale.toLanguageTag(), SurveyFormat.units(locale), MeasureUnitSystem.forLocale(locale))
        }
    }

    private companion object {
        const val NET_WALL_AREA = 31.91f
    }
}
