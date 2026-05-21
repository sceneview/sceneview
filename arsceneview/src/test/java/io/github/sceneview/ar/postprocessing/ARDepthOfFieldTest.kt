package io.github.sceneview.ar.postprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure-Kotlin math behind [ARDepthOfField] (#1716):
 *  - [cocScaleForBlurStrength] clamps to `[0, MAX_BLUR_STRENGTH]` and is the identity inside the
 *    valid range, so `blurStrength == 1f` lands exactly on Filament's stock `cocScale` default.
 *  - [sanitizedFocusDepth] coerces non-positive / non-finite inputs to [MIN_FOCUS_DEPTH_METERS]
 *    so Filament's `Camera.setFocusDistance` never receives `0`, a negative, `NaN`, or `Infinity`.
 *  - [ARDepthOfFieldOptions] rejects `NaN` / `Infinity` at construction, ahead of any Filament
 *    mutation — failing loud at the API boundary instead of corrupting the GL state.
 */
class ARDepthOfFieldTest {

    // ── cocScaleForBlurStrength ──────────────────────────────────────────────────────────────

    @Test
    fun `cocScale identity at 1f matches Filament default`() {
        // Filament's `View.DepthOfFieldOptions.cocScale` defaults to 1.0 — feeding the user-facing
        // `blurStrength = 1f` knob through the mapper must hit exactly that default, otherwise
        // turning the effect on at the default knob position would silently boost the bokeh.
        assertEquals(1.0f, cocScaleForBlurStrength(1.0f), 0f)
    }

    @Test
    fun `cocScale clamps negative blurStrength to 0`() {
        assertEquals(0f, cocScaleForBlurStrength(-0.1f), 0f)
        assertEquals(0f, cocScaleForBlurStrength(-100f), 0f)
    }

    @Test
    fun `cocScale clamps blurStrength to MAX_BLUR_STRENGTH`() {
        assertEquals(MAX_BLUR_STRENGTH, cocScaleForBlurStrength(MAX_BLUR_STRENGTH + 1f), 0f)
        assertEquals(MAX_BLUR_STRENGTH, cocScaleForBlurStrength(1_000f), 0f)
    }

    @Test
    fun `cocScale is identity inside the valid range`() {
        listOf(0f, 0.25f, 0.5f, 2f, 4f, MAX_BLUR_STRENGTH).forEach { v ->
            assertEquals(v, cocScaleForBlurStrength(v), 0f)
        }
    }

    // ── sanitizedFocusDepth ──────────────────────────────────────────────────────────────────

    @Test
    fun `sanitizedFocusDepth passes through finite positives above the floor`() {
        // Pin a few representative values across the AR depth range. Plugin call doesn't change
        // them — this is the happy path that 99% of focus updates hit.
        listOf(0.05f, 0.5f, 1.0f, 5.0f, 50.0f).forEach { d ->
            assertEquals(d, sanitizedFocusDepth(d), 0f)
        }
    }

    @Test
    fun `sanitizedFocusDepth clamps zero and negatives to the floor`() {
        // Filament's setFocusDistance throws / behaves badly on non-positive — and the
        // ARCore depth image legitimately can carry `0` for unmapped pixels. The hitTestDepth API
        // already returns null in that case, but defensively guard here too in case a caller
        // computes focusDepth themselves (`prev * exp(-k)` going slightly negative on roundoff).
        assertEquals(MIN_FOCUS_DEPTH_METERS, sanitizedFocusDepth(0f), 0f)
        assertEquals(MIN_FOCUS_DEPTH_METERS, sanitizedFocusDepth(-0.1f), 0f)
        assertEquals(MIN_FOCUS_DEPTH_METERS, sanitizedFocusDepth(-1000f), 0f)
    }

    @Test
    fun `sanitizedFocusDepth clamps inputs at or below the floor`() {
        // The floor itself is rejected (the check is strictly greater than). Anything below the
        // floor also clamps up, so we always cross the Filament boundary with a known-safe value.
        assertEquals(MIN_FOCUS_DEPTH_METERS, sanitizedFocusDepth(MIN_FOCUS_DEPTH_METERS), 0f)
        assertEquals(MIN_FOCUS_DEPTH_METERS, sanitizedFocusDepth(MIN_FOCUS_DEPTH_METERS / 2f), 0f)
    }

    @Test
    fun `sanitizedFocusDepth rejects NaN and infinities`() {
        // The require() in ARDepthOfFieldOptions catches these at the API boundary, but the
        // internal helper is also exercised directly from applyARDepthOfField — keep it defensive
        // so an unconstrained Float never reaches Filament's JNI path.
        assertEquals(MIN_FOCUS_DEPTH_METERS, sanitizedFocusDepth(Float.NaN), 0f)
        assertEquals(MIN_FOCUS_DEPTH_METERS, sanitizedFocusDepth(Float.NEGATIVE_INFINITY), 0f)
        // POSITIVE_INFINITY is finite()==false in Kotlin so it gets clamped too — defensive parity
        // with the negative case.
        assertEquals(MIN_FOCUS_DEPTH_METERS, sanitizedFocusDepth(Float.POSITIVE_INFINITY), 0f)
    }

    // ── ARDepthOfFieldOptions constructor ────────────────────────────────────────────────────

    @Test
    fun `options reject NaN focusDepth at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            ARDepthOfFieldOptions(focusDepth = Float.NaN)
        }
    }

    @Test
    fun `options reject infinite focusDepth at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            ARDepthOfFieldOptions(focusDepth = Float.POSITIVE_INFINITY)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ARDepthOfFieldOptions(focusDepth = Float.NEGATIVE_INFINITY)
        }
    }

    @Test
    fun `options reject NaN blurStrength at construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            ARDepthOfFieldOptions(focusDepth = 1f, blurStrength = Float.NaN)
        }
    }

    @Test
    fun `options accept the recommended default knob position`() {
        // The defaults (focusDepth=1m, blurStrength=1f) should be a no-throw — they're the
        // values the first KDoc example in ARDepthOfField.kt pushes the user toward.
        val opts = ARDepthOfFieldOptions(focusDepth = 1.0f)
        assertEquals(1.0f, opts.focusDepth, 0f)
        assertEquals(1.0f, opts.blurStrength, 0f)
        assertTrue(opts.enabled)
    }

    @Test
    fun `options off-by-default-when-disabled flag round-trips`() {
        // Off-by-default isn't enforced at construction — apps opt in by passing
        // ARDepthOfFieldOptions at all — but the disabled-state value should still be a valid
        // struct so a `mutableStateOf(opts.copy(enabled = false))` pattern works cleanly.
        val off = ARDepthOfFieldOptions(focusDepth = 1.0f, enabled = false)
        assertTrue(!off.enabled)
        assertEquals(1.0f, off.focusDepth, 0f)
    }
}
