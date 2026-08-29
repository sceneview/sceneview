package io.github.sceneview.demo.demos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Pins the shadow-intensity slider's value → readout mapping.
 *
 * The bug this guards (#3372): the readout was `"${(factor * 100).toInt()}%"` while the track ran
 * `0f..1.5f`, so the default `1f` printed a flat `100%` with the thumb two thirds along — a
 * percentage claiming to be full at the point the control is only two thirds open. Plain JVM, no
 * Robolectric: the mapping is arithmetic and string formatting, and it should fail in
 * milliseconds rather than behind a Compose renderer.
 *
 * The load-bearing assertion is [readout_at_track_end_is_the_maximum_readout]: whatever the range
 * becomes, the value printed when the thumb is at the end must be the largest one the control can
 * show. That is the invariant the old percentage broke, and it survives a future range change.
 */
class ShadowIntensityReadoutTest {

    @Test
    fun readout_is_a_multiplier_not_a_percentage() {
        assertEquals("0.00×", formatShadowIntensityFactor(0f))
        assertEquals("1.00×", formatShadowIntensityFactor(1f))
        assertEquals("1.50×", formatShadowIntensityFactor(1.5f))
    }

    /**
     * The regression itself. `1f` is the default the demo opens on, and it sits at two thirds of
     * the track — so it must NOT render as the "everything" value a `100%` implies.
     */
    @Test
    fun default_value_does_not_read_as_full() {
        val default = 1f
        val fractionOfTrack =
            (default - SHADOW_INTENSITY_RANGE.start) /
                (SHADOW_INTENSITY_RANGE.endInclusive - SHADOW_INTENSITY_RANGE.start)
        assertEquals(2f / 3f, fractionOfTrack, 1e-6f)
        assertTrue(
            "The default readout must not claim to be a full 100%",
            "100%" !in formatShadowIntensityFactor(default),
        )
        assertTrue(
            "A multiplier readout must never carry a percent sign",
            '%' !in formatShadowIntensityFactor(default),
        )
    }

    /** The end of the track is the top of the scale — the contract the old readout violated. */
    @Test
    fun readout_at_track_end_is_the_maximum_readout() {
        val atEnd = formatShadowIntensityFactor(SHADOW_INTENSITY_RANGE.endInclusive)
        val printedAtEnd = atEnd.dropLast(1).toFloat()
        (0..100).forEach { step ->
            val value = SHADOW_INTENSITY_RANGE.start + step * STEP
            val printed = formatShadowIntensityFactor(value).dropLast(1).toFloat()
            assertTrue(
                "$value printed $printed, above the track end's $atEnd",
                printed <= printedAtEnd,
            )
        }
    }

    /** Monotonic: dragging right never prints a smaller number. */
    @Test
    fun readout_grows_with_the_value() {
        val printed = (0..100).map {
            formatShadowIntensityFactor(SHADOW_INTENSITY_RANGE.start + it * STEP).dropLast(1)
                .toFloat()
        }
        printed.zipWithNext { a, b ->
            assertTrue("Readout went backwards: $a then $b", b >= a)
        }
    }

    /**
     * Locale-independent, like every other slider readout in the samples: a decimal comma would
     * not round-trip through `toFloat()` for a reader copying the number into code.
     */
    @Test
    fun readout_uses_a_decimal_point_under_a_comma_locale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.FRANCE)
            assertEquals("1.00×", formatShadowIntensityFactor(1f))
        } finally {
            Locale.setDefault(previous)
        }
    }

    private companion object {
        /** One hundredth of the track, so the sweep lands exactly on both ends. */
        val STEP =
            (SHADOW_INTENSITY_RANGE.endInclusive - SHADOW_INTENSITY_RANGE.start) / 100f
    }
}
