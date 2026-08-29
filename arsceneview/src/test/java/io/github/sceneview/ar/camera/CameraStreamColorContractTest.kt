package io.github.sceneview.ar.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Colour-chain contract for the three AR camera-stream materials (#3338).
 *
 * ARCore hands the camera buffer over with dataspace `0x08810000`, which decodes as
 * `STANDARD_BT709 | TRANSFER_SRGB | RANGE_FULL`: the EGL external sampler has already done
 * YUV→RGB with the right matrix and the right range, so what the fragment shader reads is
 * sRGB-encoded, full-range BT.709 RGB. Nothing to fix on the YUV side — the "weird colour"
 * lived entirely in what the shader did *after* the sample.
 *
 * The camera background is drawn full-screen and then pushed through Filament's post-process
 * chain like any other pixel. For it to survive unchanged, the material has to pre-distort it
 * by the exact inverse of that chain, in two legs:
 *
 *  1. **Tone map.** `Inverse_Tonemap_Filmic` is the exact analytic inverse of the Narkowicz
 *     curve behind `ToneMapper.Filmic`, which `createARView()` installs. This leg always
 *     cancelled and is untouched by #3338, and is pinned here so a future tone-mapper change
 *     cannot silently break it.
 *  2. **Transfer function.** Filament's colour-grading output stage is Rec709-sRGB-D65, i.e.
 *     the exact piecewise sRGB OETF. The materials used to decode with `pow(c, 2.2)` (what
 *     Filament's `inverseTonemapSRGB()` helper does), and `sRGB_OETF(pow(c, 2.2))` is not the
 *     identity. That mismatch is the bug.
 *
 * A GLSL shader cannot run in a pure-JVM test, so this file does two things instead: it
 * replicates the shader math in Kotlin and pins the numbers, and it reads the three `.mat`
 * sources to guard that they still use the exact path. The on-device look needs a real
 * session — see the PR's needs-device section.
 */
class CameraStreamColorContractTest {

    // --- The chain, replicated ------------------------------------------------------------

    /** Exact sRGB EOTF (IEC 61966-2-1) — what the materials now use to decode the camera texel. */
    private fun srgbToLinearExact(s: Double): Double =
        if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)

    /** The `pow(c, 2.2)` approximation Filament's `inverseTonemapSRGB()` decodes with. */
    private fun srgbToLinearPow22(s: Double): Double = s.pow(2.2)

    /** Exact sRGB OETF — Filament's Rec709-sRGB-D65 colour-grading output stage. */
    private fun linearToSrgbExact(c: Double): Double =
        if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055

    /** Narkowicz 2015 ACES approximation — the curve behind Filament's `ToneMapper.Filmic`. */
    private fun filmic(x: Double): Double = (x * (x * 2.51 + 0.03)) / (x * (x * 2.43 + 0.59) + 0.14)

    /** `Inverse_Tonemap_Filmic` exactly as the compiled shader spells it. */
    private fun inverseFilmic(y: Double): Double =
        ((0.03 - y * 0.59) - sqrt((0.0009 + y * 1.3702) - (y * 1.0127) * y)) / (-5.02 + y * 4.86)

    /** Camera texel in, framebuffer pixel out, for a given decode leg. */
    private fun roundTrip(srgbIn: Double, decode: (Double) -> Double): Double =
        linearToSrgbExact(filmic(inverseFilmic(decode(srgbIn))))

    private val codes = (0..255).map { it / 255.0 }

    // --- Numerical contract ---------------------------------------------------------------

    @Test
    fun `filmic tone map leg cancels exactly`() {
        // Pinned independently of the transfer leg: if someone swaps ToneMapper.Filmic for
        // another curve without changing the materials, this is the test that says so.
        codes.forEach { linear ->
            assertEquals(
                "Inverse_Tonemap_Filmic must be the exact inverse of ToneMapper.Filmic at $linear",
                linear,
                filmic(inverseFilmic(linear)),
                1e-9,
            )
        }
    }

    @Test
    fun `exact sRGB decode round-trips the camera pixel at every 8-bit code`() {
        val worst = codes.maxOf { abs(roundTrip(it, ::srgbToLinearExact) - it) }
        assertTrue(
            "The exact-EOTF chain must be invisible on an 8-bit display: worst error was " +
                "${worst * 255} / 255 (#3338).",
            worst * 255.0 < 0.5,
        )
    }

    @Test
    fun `the pow 2_2 decode crushed the shadows`() {
        // The defect, pinned so nobody "simplifies" the material back to inverseTonemapSRGB().
        // Achromatic (identical on R, G and B), so it read as an S-curve contrast boost applied
        // to the camera background only, not as a colour cast.
        val err = { code: Int ->
            (roundTrip(code / 255.0, ::srgbToLinearPow22) - code / 255.0) * 255.0
        }

        assertEquals("peak shadow error sits at code 16", -8.54, err(16), 0.05)
        assertEquals("still visibly crushed at code 8", -6.38, err(8), 0.05)
        assertTrue("error crosses zero in the midtones", err(102) > 0.0)
        assertEquals("and lifts the highlights slightly", 1.47, err(179), 0.05)
        assertEquals("white stays white", 0.0, err(255), 0.05)

        val worst = codes.maxOf { abs(roundTrip(it, ::srgbToLinearPow22) - it) }
        assertTrue(
            "The old chain was off by more than 8/255 somewhere — that is the visible defect " +
                "the exact decode removes; if this ever drops below 4/255 the premise of " +
                "#3338 changed and the material comment needs revisiting.",
            worst * 255.0 > 4.0,
        )
    }

    // --- Source contract ------------------------------------------------------------------

    private fun mat(name: String): String {
        val file = File("src/main/materials/$name.mat")
        assertTrue(
            "Expected ${file.absolutePath} — JVM test must run from the arsceneview root.",
            file.exists(),
        )
        return file.readText()
    }

    /**
     * `//` comments stripped. The materials *document* the old `inverseTonemapSRGB()` call in
     * their rationale comments, so the "no longer called" guard has to look at code only.
     */
    private fun matCode(name: String): String =
        mat(name).lineSequence().joinToString("\n") { it.substringBefore("//") }

    private val cameraMaterials = listOf(
        "camera_stream_flat",
        "camera_stream_depth",
        "camera_stream_person_occlusion",
    )

    @Test
    fun `every camera material decodes with the exact sRGB EOTF`() {
        cameraMaterials.forEach { name ->
            val source = matCode(name)
            assertTrue(
                "$name.mat must define the exact IEC 61966-2-1 decode (#3338).",
                source.contains("vec3 srgbToLinearExact(const vec3 srgb)"),
            )
            // The two constants that separate the exact piecewise curve from an approximation.
            assertTrue("$name.mat lost the linear segment slope", source.contains("1.0 / 12.92"))
            assertTrue("$name.mat lost the knee threshold", source.contains("0.04045"))
            assertTrue(
                "$name.mat must still invert the Filmic curve the View re-applies.",
                source.contains("Inverse_Tonemap_Filmic(") &&
                    source.contains("srgbToLinearExact(")
            )
        }
    }

    @Test
    fun `no camera material calls inverseTonemapSRGB`() {
        cameraMaterials.forEach { name ->
            assertFalse(
                "$name.mat must not use Filament's inverseTonemapSRGB(): it decodes with " +
                    "pow(c, 2.2), which does not cancel the exact sRGB OETF Filament re-encodes " +
                    "with, and crushes the camera background's shadows by up to 8.5/255 (#3338).",
                matCode(name).contains("inverseTonemapSRGB("),
            )
        }
    }
}
