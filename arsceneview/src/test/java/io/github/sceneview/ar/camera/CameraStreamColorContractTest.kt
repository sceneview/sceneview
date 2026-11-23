package io.github.sceneview.ar.camera

import org.junit.Assert.assertEquals
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
 * A third correction came with #5219: Filament's ColorGrading applies the tone mapper in its
 * Rec.2020 working space. Inverted channel by channel in Rec.709, the Filmic leg cancelled for
 * greys only, and a `#66B0F5` sky showed at `#93B2FA`. The three materials now include the
 * inverse every pass-through material shares (`sceneview/src/main/materials/include/
 * view_tone_map_inverse.fs`), which converts to Rec.2020, inverts there and converts back.
 *
 * The shader chain is reproduced as pure Kotlin so its output can be checked directly.
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

    // --- Rec.2020 grading (#5219) ---------------------------------------------------------

    /** Filament's sRGB_to_REC2020 and REC2020_to_sRGB, row-major. */
    private val rec709ToRec2020 = arrayOf(
        doubleArrayOf(0.6274040, 0.3292820, 0.0433136),
        doubleArrayOf(0.0690970, 0.9195400, 0.0113612),
        doubleArrayOf(0.0163916, 0.0880132, 0.8955950),
    )
    private val rec2020ToRec709 = arrayOf(
        doubleArrayOf(1.6604910, -0.5876411, -0.0728499),
        doubleArrayOf(-0.1245505, 1.1328999, -0.0083494),
        doubleArrayOf(-0.0181508, -0.1005789, 1.1187297),
    )

    private fun Array<DoubleArray>.times(v: DoubleArray) =
        DoubleArray(3) { r -> (0..2).sumOf { this[r][it] * v[it] } }

    /** What the View shows for a scene colour: Filmic per channel in Rec.2020, then the OETF. */
    private fun shown(scene: DoubleArray): List<Int> {
        val graded = rec2020ToRec709.times(rec709ToRec2020.times(scene).map(::filmic).toDoubleArray())
        return graded.map { Math.round(linearToSrgbExact(it.coerceIn(0.0, 1.0)) * 255).toInt() }
    }

    /** `inverseViewTonemapSRGB(camera, true)` of the shared include. */
    private fun cameraScene(srgb: IntArray): DoubleArray {
        val working = rec709ToRec2020.times(DoubleArray(3) { srgbToLinearExact(srgb[it] / 255.0) })
        return rec2020ToRec709.times(working.map(::inverseFilmic).toDoubleArray()).map { maxOf(it, 0.0) }
            .toDoubleArray()
    }

    private fun bytes(rgb: Int) = IntArray(3) { (rgb shr (16 - 8 * it)) and 0xFF }

    @Test
    fun `a saturated camera colour displays as it came from the camera`() {
        val chart = listOf(0x66B0F5, 0xD95F57, 0x4CB050, 0x2196F2, 0xF5428C, 0x00BDD4, 0xFFC207, 0x9E75BD, 0x808080)
        chart.forEach { rgb ->
            val source = bytes(rgb)
            val got = shown(cameraScene(source))
            for (c in 0..2) {
                // A channel that would need a negative scene value is clipped at zero.
                val tolerance = if (source[c] < 0x30) 16 else 1
                assertTrue(
                    "#%06X shows $got (tolerance $tolerance)".format(rgb),
                    abs(got[c] - source[c]) <= tolerance,
                )
            }
        }
    }

    @Test
    fun `inverted channel by channel in Rec 709, the sky was lighter and less saturated`() {
        val sky = bytes(0x66B0F5)
        val per709 = DoubleArray(3) { inverseFilmic(srgbToLinearExact(sky[it] / 255.0)) }
        assertEquals(listOf(0x93, 0xB2, 0xFA), shown(per709))
    }

    @Test
    fun `the three camera materials include the shared inverse for a Filmic view`() {
        val include = "../../../../sceneview/src/main/materials/include/view_tone_map_inverse.fs"
        assertTrue(
            "the include the materials name resolves from their directory",
            File("src/main/materials", include).isFile,
        )
        for (name in listOf("flat", "depth", "person_occlusion")) {
            val source = File("src/main/materials/camera_stream_$name.mat").readText()
            val code = source.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
            assertTrue("$name includes the shared inverse", code.contains("#include \"$include\""))
            assertTrue(
                "$name pre-distorts the camera for createARView's Filmic",
                Regex("inverseViewTonemapSRGB\\(\\s*texture\\(materialParams_cameraTexture, cameraUv\\)\\.rgb, true\\)")
                    .containsMatchIn(code) ||
                    code.contains("inverseViewTonemapSRGB(camera, true)"),
            )
            listOf("Inverse_Tonemap_Filmic(", "srgbToLinearExact(", "inverseTonemapSRGB(").forEach {
                assertTrue("$name still calls $it", !code.contains(it))
            }
        }
    }
}
