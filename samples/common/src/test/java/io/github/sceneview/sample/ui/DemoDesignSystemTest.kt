package io.github.sceneview.sample.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Guards on the shared demo design system.
 *
 * These are plain JVM tests on purpose: the properties worth pinning here are the palette's
 * completeness and the readout's formatting, neither of which needs a Compose host. The
 * composables' layout is covered by their `@Preview`s and by device QA.
 */
class DemoDesignSystemTest {

    @Test
    fun `dark palette covers exactly the light palette`() {
        // The bug this replaces: one screen carried a light-only copy of this palette, so in
        // dark mode it rendered the light hues — the exact case the dark palette exists to
        // avoid. A category added to one map and forgotten in the other fails here instead.
        assertEquals(
            DemoCategoryAccent.lightKeys(),
            DemoCategoryAccent.darkKeys()
        )
    }

    @Test
    fun `every category resolves to a distinct accent in both schemes`() {
        for (scheme in listOf(false, true)) {
            val accents = DemoCategoryAccent.categories.map { DemoCategoryAccent[it, scheme] }
            assertEquals(
                "duplicate accent in ${if (scheme) "dark" else "light"} scheme",
                accents.size,
                accents.toSet().size
            )
        }
    }

    @Test
    fun `each category reads differently in light and dark`() {
        for (category in DemoCategoryAccent.categories) {
            assertNotEquals(
                "$category has the same accent in both schemes",
                DemoCategoryAccent[category, false],
                DemoCategoryAccent[category, true]
            )
        }
    }

    @Test
    fun `an unknown category falls back rather than throwing`() {
        assertEquals(DemoCategoryAccent.Fallback, DemoCategoryAccent["Not A Category", false])
        assertEquals(DemoCategoryAccent.Fallback, DemoCategoryAccent["Not A Category", true])
    }

    @Test
    fun `slider readout formats to the requested precision`() {
        assertEquals("0.42", formatSliderValue(0.4157f, 2, null))
        assertEquals("0.4", formatSliderValue(0.4157f, 1, null))
        assertEquals("120000", formatSliderValue(120_000f, 0, null))
    }

    @Test
    fun `slider readout appends a unit after a thin space`() {
        // `\u2009` written as an escape, not typed: an invisible character in a test literal
        // reads as a plain space and fails with `expected:<3.5[ ]m> but was:<3.5[ ]m>`.
        assertEquals("3.5\u2009m", formatSliderValue(3.5f, 1, "m"))
        assertEquals("9.8\u2009m/s²", formatSliderValue(9.81f, 1, "m/s²"))
        assertEquals("0.50", formatSliderValue(0.5f, 2, ""))
        assertEquals("0.50", formatSliderValue(0.5f, 2, null))
    }

    @Test
    fun `slider readout uses a dot regardless of the default locale`() {
        // These readouts sit next to API values a reader copies into code; a decimal comma
        // would not round-trip through `toFloat()`.
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            assertEquals("0.42", formatSliderValue(0.4157f, 2, null))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `a negative decimal count is clamped rather than crashing`() {
        assertEquals("3", formatSliderValue(3.4f, -1, null))
    }
}
