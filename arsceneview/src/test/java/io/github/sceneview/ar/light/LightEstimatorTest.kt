package io.github.sceneview.ar.light

import io.github.sceneview.math.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [LightEstimator]-related logic that does not require
 * Android, Filament, or ARCore.
 *
 * Tests cover:
 * 1. [LightEstimator.Estimation] data-class contract (equality, copy, nullability).
 * 2. [LightEstimator.SPHERICAL_HARMONICS_IRRADIANCE_FACTORS] math — the constant
 *    encodes a carefully-ordered set of pre-scaled SH coefficients that are
 *    critical for correct light estimation. Any accidental reordering or value
 *    change would silently produce wrong lighting in all AR apps.
 */
class LightEstimatorTest {

    // ── Estimation data class ────────────────────────────────────────────────

    @Test
    fun `Estimation default constructor has all fields null`() {
        val est = LightEstimator.Estimation()
        assertNull(est.mainLightColor)
        assertNull(est.mainLightIntensity)
        assertNull(est.mainLightDirection)
        assertNull(est.reflections)
        assertNull(est.irradiance)
    }

    @Test
    fun `Estimation equality holds for equal instances`() {
        val a = LightEstimator.Estimation(mainLightIntensity = 1.5f)
        val b = LightEstimator.Estimation(mainLightIntensity = 1.5f)
        assertEquals(a, b)
    }

    @Test
    fun `Estimation equality fails for different intensities`() {
        val a = LightEstimator.Estimation(mainLightIntensity = 1.5f)
        val b = LightEstimator.Estimation(mainLightIntensity = 2.0f)
        assertFalse(a == b)
    }

    @Test
    fun `Estimation copy preserves non-copied fields`() {
        val original = LightEstimator.Estimation(mainLightIntensity = 3.0f)
        val copy = original.copy(mainLightIntensity = 5.0f)
        assertEquals(5.0f, copy.mainLightIntensity)
        // Other fields should still be null (from default original)
        assertNull(copy.mainLightColor)
        assertNull(copy.mainLightDirection)
    }

    @Test
    fun `Estimation irradiance array survives data-class round-trip`() {
        val coeffs = FloatArray(9) { it.toFloat() }
        val est = LightEstimator.Estimation(irradiance = coeffs)
        assertNotNull(est.irradiance)
        assertEquals(9, est.irradiance!!.size)
        for (i in 0..8) {
            assertEquals(i.toFloat(), est.irradiance!![i], 0.0001f)
        }
    }

    // ── SPHERICAL_HARMONICS_IRRADIANCE_FACTORS ───────────────────────────────

    /**
     * The constant must have exactly 9 entries — one factor per SH band-coefficient
     * (L0, L1x, L1y, L1z, L2_0, L2_1, L2_2, L2_3, L2_4).
     */
    @Test
    fun `SPHERICAL_HARMONICS_IRRADIANCE_FACTORS has 9 entries`() {
        assertEquals(9, LightEstimator.SPHERICAL_HARMONICS_IRRADIANCE_FACTORS.size)
    }

    /**
     * Spot-check the first three values (the L0 and L1 band factors).
     * These are derived from the SH normalization + BRDF + pi factor conversion.
     * Values verified against the Filament SceneformMaintained reference:
     * https://github.com/ThomasGorisse/SceneformMaintained/pull/156
     */
    @Test
    fun `SPHERICAL_HARMONICS_IRRADIANCE_FACTORS first three values are correct`() {
        val f = LightEstimator.SPHERICAL_HARMONICS_IRRADIANCE_FACTORS
        assertEquals(0.282095f, f[0], 0.000001f)   // L0 band
        assertEquals(-0.325735f, f[1], 0.000001f)  // L1 x-axis
        assertEquals(0.325735f, f[2], 0.000001f)   // L1 y-axis
    }

    /**
     * Pinning #1093: the SH factors match Filament's `CubemapSH.cpp` band layout
     * directly — NO swap is needed. The pre-#1093 code applied a `mapIndexed { 6 ->
     * [7]; 7 -> [6] }` swap based on a v1.x assumption that ARCore and Filament
     * used different orderings; cross-checking both APIs showed they use the SAME
     * order (y00, y1m1, y10, y11, y2m2, y2m1, y20, y21, y22). The swap inverted
     * y20 (0.078848) and y21 (-0.273137) → matte AR surfaces were lit with wrong
     * up-axis direction-dependent shifts.
     *
     * If a future SDK upgrade actually changes ARCore's ordering, this test will
     * fail and force a re-evaluation rather than silently producing wrong output.
     */
    @Test
    fun `SPHERICAL_HARMONICS_IRRADIANCE_FACTORS matches Filament band layout (no swap, #1093)`() {
        val f = LightEstimator.SPHERICAL_HARMONICS_IRRADIANCE_FACTORS
        // y20 = sqrt(5/(16π)) ≈ 0.078848 — Filament's CubemapSH.cpp ki[6]
        assertEquals(0.078848f, f[6], 0.000001f)
        // y21 = -sqrt(15/(4π))/2 ≈ -0.273137 — Filament's ki[7]
        assertEquals(-0.273137f, f[7], 0.000001f)
        // y22 = sqrt(15/(16π)) ≈ 0.136569 — Filament's ki[8]
        assertEquals(0.136569f, f[8], 0.000001f)
    }

    /**
     * Full pin of all 9 SH factors against Filament's K constants. Catches any
     * accidental reordering / sign flip / value drift in a single shot.
     */
    @Test
    fun `SPHERICAL_HARMONICS_IRRADIANCE_FACTORS full Filament K layout`() {
        val f = LightEstimator.SPHERICAL_HARMONICS_IRRADIANCE_FACTORS
        val expected = floatArrayOf(
            0.282095f,                              // y00
            -0.325735f, 0.325735f, -0.325735f,      // y1m1, y10, y11
            0.273137f, -0.273137f,                  // y2m2, y2m1
            0.078848f, -0.273137f, 0.136569f,       // y20, y21, y22
        )
        for (i in expected.indices) {
            assertEquals("factor[$i] mismatch", expected[i], f[i], 0.000001f)
        }
    }

    /**
     * All factors must be finite (no NaN or Infinity that would corrupt
     * the Filament irradiance upload).
     */
    @Test
    fun `SPHERICAL_HARMONICS_IRRADIANCE_FACTORS are all finite`() {
        for ((i, v) in LightEstimator.SPHERICAL_HARMONICS_IRRADIANCE_FACTORS.withIndex()) {
            assertTrue("factor[$i] = $v is not finite", v.isFinite())
        }
    }

    /**
     * The magnitude of each factor is reasonable (within [-1, 1]).
     * Larger values would over-amplify the SH and blow out the lighting.
     */
    @Test
    fun `SPHERICAL_HARMONICS_IRRADIANCE_FACTORS magnitudes are within (-1, 1)`() {
        for ((i, v) in LightEstimator.SPHERICAL_HARMONICS_IRRADIANCE_FACTORS.withIndex()) {
            assertTrue(
                "factor[$i] = $v is out of expected range (-1, 1)",
                v > -1f && v < 1f
            )
        }
    }

    /**
     * Ambient intensity normalization: factor[3] (L1 z-axis) must be negative,
     * matching the Filament convention where the Y-up axis has negative contribution.
     */
    @Test
    fun `SPHERICAL_HARMONICS_IRRADIANCE_FACTORS index-3 is negative`() {
        val f = LightEstimator.SPHERICAL_HARMONICS_IRRADIANCE_FACTORS
        assertTrue("factor[3] should be negative, got ${f[3]}", f[3] < 0f)
    }

    // ── Estimation.clear() — reused-instance reset (#1105) ───────────────────

    /**
     * #1105: `update()` reuses a single [LightEstimator.Estimation] instance to
     * avoid a per-frame allocation on the 30-60 Hz render thread. [clear] must
     * reset every field so a frame that does not take a given path (e.g.
     * `environmentalHdrReflections` off) never surfaces a stale value from a
     * previous frame.
     */
    @Test
    fun `Estimation clear resets every field to null`() {
        val est = LightEstimator.Estimation(
            mainLightIntensity = 4.2f,
            irradiance = FloatArray(27) { it.toFloat() }
        )
        est.clear()
        assertNull(est.mainLightColor)
        assertNull(est.mainLightIntensity)
        assertNull(est.mainLightDirection)
        assertNull(est.reflections)
        assertNull(est.irradiance)
    }

    @Test
    fun `Estimation clear on already-empty instance is a no-op`() {
        val est = LightEstimator.Estimation()
        est.clear()
        assertNull(est.mainLightIntensity)
        assertNull(est.irradiance)
    }

    // ── Spherical-harmonics conversion (in-place loop, #1105) ────────────────

    /**
     * #1105 replaced `mapIndexed { … }.toFloatArray()` with an in-place loop into
     * a reused 27-element buffer. The arithmetic must be identical: each of the
     * 27 ARCore SH floats is scaled by `FACTORS[index / 3]`. Pins the conversion
     * so the allocation refactor cannot silently change irradiance output.
     */
    @Test
    fun `SH conversion scales each component by FACTORS index over 3`() {
        val factors = LightEstimator.SPHERICAL_HARMONICS_IRRADIANCE_FACTORS
        val arCoreSh = FloatArray(27) { (it + 1).toFloat() }

        val converted = FloatArray(27)
        for (index in converted.indices) {
            converted[index] = arCoreSh[index] * factors[index / 3]
        }

        // Reference computation via the pre-#1105 mapIndexed form.
        val reference = arCoreSh
            .mapIndexed { index, v -> v * factors[index / 3] }
            .toFloatArray()

        assertEquals(27, converted.size)
        for (i in converted.indices) {
            assertEquals("component[$i] mismatch", reference[i], converted[i], 1e-6f)
        }
    }

    // ── Source-level regression pins (#1120) ─────────────────────────────────
    //
    // `LightEstimator`'s instance fields can only be observed by constructing the
    // class, which needs a real Filament `Engine` + `IBLPrefilter` (JNI, not
    // shadowed by Robolectric). The established repo pattern for that situation
    // — see `ARCompletenessDefaultsTest` — is to pin the source declaration with
    // a regex so a revert fails the build. These pins fill the test gap left by
    // PRs #1086 and #1091 (post-merge audit batch on `ce1f4a79`, see #1120).

    private val lightEstimatorSource: String by lazy {
        val f = java.io.File(
            "src/main/java/io/github/sceneview/ar/light/LightEstimator.kt"
        )
        assertTrue(
            "Expected ${f.absolutePath} — JVM test must run from arsceneview module root.",
            f.exists(),
        )
        f.readText()
    }

    @Test
    fun `environmentalHdrSpecularFilter defaults to true (#1086)`() {
        // PR #1086 flipped the default false → true (#1064): Filament's
        // `IndirectLight.reflections()` expects a roughness-prefiltered mip
        // chain, so a raw cubemap makes every reflection mirror-like at any
        // roughness. A future "default-off for perf" PR would silently revert
        // this without a test catching it (#1120).
        val pattern = Regex(
            """var\s+environmentalHdrSpecularFilter\s*=\s*true"""
        )
        assertTrue(
            "LightEstimator.environmentalHdrSpecularFilter MUST default to `true` " +
                "(#1086 / #1064). Source did not match $pattern.",
            pattern.containsMatchIn(lightEstimatorSource),
        )
    }

    @Test
    fun `cubemap upload callback is the hoisted no-double-close uploadCompletedCallback (#1091)`() {
        // PR #1091 (#1090) removed the inline callback that double-closed the
        // already-`use {}`-closed ARCore Images. CORR-B (#1094) then restored a
        // *synchronisation-only* callback, hoisted by #1102 to the instance-scoped
        // `uploadCompletedCallback`. Pin BOTH facets of the evolved contract:
        //   1. the callback handed to `PixelBufferDescriptor` is the hoisted ref;
        //   2. that hoisted ref's body is a pure flag flip — it must NEVER close
        //      `arImages` again (the #1091 double-close regression).
        val src = lightEstimatorSource
        // Strip Kotlin line comments — the call site carries an inline comment
        // between `null,` and `uploadCompletedCallback` explaining the #1102 hoist.
        val codeOnly = src.lineSequence()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        assertTrue(
            "The `Texture.PixelBufferDescriptor` callback MUST be the hoisted " +
                "`uploadCompletedCallback` (#1091 double-close fix + #1102 hoist).",
            Regex("""1,\s*0,\s*0,\s*0,\s*null,\s*uploadCompletedCallback""")
                .containsMatchIn(codeOnly),
        )
        val callbackDecl = Regex(
            """uploadCompletedCallback\s*=\s*Runnable\s*\{[^}]*}"""
        ).find(codeOnly)?.value
        requireNotNull(callbackDecl) {
            "Could not find the `uploadCompletedCallback` Runnable declaration."
        }
        assertTrue(
            "uploadCompletedCallback body must be the pure flag flip " +
                "`uploadInFlight = false` (#1094). Saw: $callbackDecl",
            Regex("""uploadInFlight\s*=\s*false""").containsMatchIn(callbackDecl),
        )
        assertFalse(
            "uploadCompletedCallback MUST NOT close `arImages` — that is the " +
                "#1090/#1091 double-close regression. Saw: $callbackDecl",
            callbackDecl.contains("arImages"),
        )
    }

    // ── environmentalHdrMainLight hue × magnitude decomposition (#2483) ─────
    //
    // The ARSceneView consumer multiplies a first-frame baseline by BOTH
    // `mainLightColor` and `mainLightIntensity`, so the ARCore estimate's
    // magnitude must be carried by exactly ONE of the two. The pre-fix code
    // carried it in both (raw radiance × 1/ev100 in the color AND its average
    // in the intensity) → the estimate was applied ~squared and the main light
    // collapsed to ~black in every ENVIRONMENTAL_HDR session — the Pixel 9
    // "placed models too dark / green-tinted" device-review finding.

    @Test
    fun `environmentalHdrMainLight normalizes hue to max component == 1`() {
        val (color, intensity) =
            LightEstimator.environmentalHdrMainLight(Color(0.2f, 0.5f, 0.4f))
        assertNotNull(color)
        assertEquals(0.4f, color!!.r, 1e-6f)
        assertEquals(1.0f, color.g, 1e-6f)
        assertEquals(0.8f, color.b, 1e-6f)
        assertEquals(0.5f, intensity, 1e-6f)
    }

    @Test
    fun `environmentalHdrMainLight conserves energy - hue x magnitude == input`() {
        // The exact decomposition contract: (c / max) × max == c, so the
        // consumer's baseline-multiply applies the raw radiance exactly once.
        val input = Color(0.21f, 0.47f, 0.33f)
        val (color, intensity) = LightEstimator.environmentalHdrMainLight(input)
        assertNotNull(color)
        assertEquals(input.r, color!!.r * intensity, 1e-6f)
        assertEquals(input.g, color.g * intensity, 1e-6f)
        assertEquals(input.b, color.b * intensity, 1e-6f)
    }

    @Test
    fun `environmentalHdrMainLight pitch-black estimate keeps baseline hue, zero intensity`() {
        val (color, intensity) =
            LightEstimator.environmentalHdrMainLight(Color(0f, 0f, 0f))
        assertNull(color)
        assertEquals(0f, intensity, 0f)
    }

    @Test
    fun `environmentalHdrMainLight indoor estimate no longer collapses the light (#2483)`() {
        // Pre-fix, with the default AR exposure f/12 1/200s ISO200 (exposureFactor = 1/ev100
        // ≈ 0.0668) and a typical indoor estimate (~0.3 relative radiance):
        //   color     ≈ 0.3 × 0.0668            ≈ 0.02   (2% of baseline hue)
        //   intensity ≈ avg(0.02) / 4           ≈ 0.005
        //   effective ≈ 0.02 × 0.005 × baseline ≈ 1e-4 × baseline → black.
        // Post-fix the magnitude is applied once: baseline × max component.
        val baselineIntensity = 10_000f
        val (color, intensity) =
            LightEstimator.environmentalHdrMainLight(Color(0.24f, 0.30f, 0.27f))
        val effectiveIntensity = baselineIntensity * intensity
        assertEquals(3_000f, effectiveIntensity, 0.5f)
        // The hue is bounded — a green-dominant camera feed tints the light but
        // never scales any channel above the estimate's own magnitude.
        assertEquals(1.0f, color!!.g, 1e-6f)
        assertTrue(color.r in 0f..1f && color.b in 0f..1f)
    }

    @Test
    fun `environmentalHdrMainLight sun-bright estimate scales past the baseline`() {
        // ARCore HDR radiance is unbounded — direct sun can exceed 1.0. The
        // magnitude passes through so the baseline-multiply can over-drive.
        val (color, intensity) =
            LightEstimator.environmentalHdrMainLight(Color(8f, 7.5f, 7f))
        assertEquals(8f, intensity, 1e-6f)
        assertEquals(1.0f, color!!.r, 1e-6f)
    }
    @Test
    fun `single-frame radiance spikes are capped at the defensive ceiling`() {
        val (color, intensity) = LightEstimator.environmentalHdrMainLight(
            Color(30f, 28f, 25f)  // absurd spike, far above real sun (~8)
        )
        assertEquals(LightEstimator.MAIN_LIGHT_MAX_INTENSITY_FACTOR, intensity)
        // Hue stays normalized against the RAW max — chromaticity is unaffected
        // by the cap.
        assertEquals(1.0f, color!!.r, 1e-6f)
    }
}
