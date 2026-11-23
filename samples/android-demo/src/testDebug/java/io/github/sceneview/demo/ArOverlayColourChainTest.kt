package io.github.sceneview.demo

import androidx.compose.ui.graphics.Color
import io.github.sceneview.demo.CameraChainReplay.REC2020_TO_REC709
import io.github.sceneview.demo.CameraChainReplay.REC709_TO_REC2020
import io.github.sceneview.demo.CameraChainReplay.cameraToBuffer
import io.github.sceneview.demo.CameraChainReplay.inverseFilmic
import io.github.sceneview.demo.CameraChainReplay.srgbToLinearExact
import io.github.sceneview.demo.CameraChainReplay.times
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The AR overlay colours through the AR colour chain, on the JVM (#4623).
 *
 * The camera image reaches the HDR buffer as `inverseViewTonemapSRGB(c, true)` ([cameraToBuffer],
 * what `camera_stream_flat.mat` does since #5219): exact sRGB EOTF, the inverse Filmic per channel
 * in Filament's Rec.2020 working space, back to Rec.709, clipped at 0. An overlay blends with it
 * there, and the View brings the result to the screen ([screen]): `ToneMapper.Filmic` per channel
 * in Rec.2020, back to Rec.709, the sRGB OETF (#5231). The tint material blends as
 * `0.1·S + S·dst` with `S = colour · strength · 2.5` (`ar_mesh_tint.mat`); the overlays it
 * replaced blended as `a·colour + (1 − a)·dst`.
 */
class ArOverlayColourChainTest {

    @Test
    fun `the replay converts with the matrices and the curve of the shared include`() {
        val include = File(SHARED_INVERSE).readText()
        for ((name, rows) in listOf(
            "VIEW_INVERSE_REC709_TO_REC2020" to REC709_TO_REC2020,
            "VIEW_INVERSE_REC2020_TO_REC709" to REC2020_TO_REC709,
        )) {
            val columnMajor = Regex("const mat3 $name = mat3\\(([^)]*)\\);").find(include)!!
                .groupValues[1].split(',').map { it.trim().toFloat() }
            assertEquals("$name has 9 entries", 9, columnMajor.size)
            for (r in 0..2) for (c in 0..2) assertEquals("$name[$r][$c]", columnMajor[c * 3 + r], rows[r][c], 0f)
        }
        assertTrue(
            "viewInverseFilmic is the curve inverseFilmic replays",
            include.contains("return (0.03 - 0.59 * x - sqrt(0.0009 + 1.3702 * x - 1.0127 * x * x)) / (-5.02 + 4.86 * x);"),
        )
        assertTrue(
            "the Filmic branch converts, inverts, converts back and clips as cameraToBuffer does",
            include.contains("vec3 working = VIEW_INVERSE_REC709_TO_REC2020 * linear;") &&
                include.contains("return max(VIEW_INVERSE_REC2020_TO_REC709 * viewInverseFilmic(working), vec3(0.0));"),
        )
    }

    @Test
    fun `the camera image round-trips through the chain`() {
        for (code in listOf(0, 16, 64, 128, 200, 235, 255)) {
            val s = code / 255f
            val shown = screen(cameraToBuffer(floatArrayOf(s, s, s)))
            shown.forEach { assertEquals("grey $code", s, it, 1.5f / 255f) }
        }
        for (rgb in SATURATED_PHOTO) {
            val source = bytes(rgb)
            val shown = screen(cameraToBuffer(FloatArray(3) { source[it] / 255f })).map { (it * 255).roundToInt() }
            for (c in 0..2) {
                // A channel that would need a negative scene value is clipped at zero, as the
                // material does: a strong blue's and a cyan's red land a few steps above the source.
                val tolerance = if (source[c] < 0x30) 16 else 1
                assertTrue("#%06X shows $shown".format(rgb), abs(shown[c] - source[c]) <= tolerance)
            }
        }
    }

    @Test
    fun `inverted channel by channel in Rec 709, the replay showed a saturated photo lighter and paler`() {
        // What the QA screen did before #5231, and the camera feed before #5219.
        val sky = bytes(0x66B0F5)
        val per709 = FloatArray(3) { inverseFilmic(srgbToLinearExact(sky[it] / 255f)) }
        assertEquals(listOf(0x93, 0xB2, 0xFA), screen(per709).map { (it * 255).roundToInt() })
    }

    @Test
    fun `the old translucent overlays wash out over a bright wall`() {
        val wall = cameraToBuffer(grey(BRIGHT_WALL))
        for (old in listOf(SceneViewColors.Primary, SceneViewColors.TintSoft)) {
            val colour = old.channels()
            val out = screen(FloatArray(3) { OLD_ALPHA * linear(colour[it]) + (1 - OLD_ALPHA) * wall[it] })
            assertTrue("$old kept saturation ${saturation(out)}", saturation(out) < 0.3f)
        }
    }

    @Test
    fun `the tint keeps each overlay colour saturated and on its hue, bright wall to dark corner`() {
        for (token in listOf(SceneViewTokens.ArOverlay.meshGround, SceneViewTokens.ArOverlay.meshStructure)) {
            for (camera in listOf(BRIGHT_WALL, MID_GREY, DARK_CORNER)) {
                val out = tinted(token, cameraToBuffer(grey(camera)))
                val where = "$token over $camera"
                assertTrue("$where: saturation ${saturation(out)}", saturation(out) > 0.8f)
                assertTrue("$where: hue ${hue(out)} vs ${hue(token.channels())}", hueGap(out, token) < MAX_HUE_DRIFT)
            }
        }
    }

    @Test
    fun `the structure orange stays orange, neither red on a grey floor nor yellow on a white wall`() {
        // A clipped highlight (code 255) sits at ~7 in the buffer and takes any warm tint to
        // yellow; a lit wall short of clipping is the bright case that matters.
        for (camera in listOf(BRIGHT_WALL, MID_GREY, DARK_CORNER, 0f)) {
            val out = tinted(SceneViewTokens.ArOverlay.meshStructure, cameraToBuffer(grey(camera)))
            assertTrue("over $camera: hue ${hue(out)}", hue(out) in ORANGE_HUES)
        }
    }

    /** `ar_mesh_tint.mat` over the buffer colour [dst], on screen. */
    private fun tinted(token: Color, dst: FloatArray): List<Float> {
        val colour = token.channels()
        return screen(
            FloatArray(3) {
                val s = linear(colour[it]) * SceneViewTokens.ArOverlay.MESH_FILL_ALPHA * GAIN
                LAID_DOWN * s + s * dst[it]
            },
        )
    }

    private fun Color.channels() = listOf(red, green, blue)

    private fun grey(s: Float) = floatArrayOf(s, s, s)

    private fun bytes(rgb: Int) = IntArray(3) { (rgb shr (16 - 8 * it)) and 0xFF }

    private fun linear(s: Float) = if (s < 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)

    /** What the View shows for a buffer colour: `ToneMapper.Filmic` per channel in Rec.2020, then the exact sRGB OETF. */
    private fun screen(x: FloatArray): List<Float> {
        val working = REC709_TO_REC2020 * x
        val graded = REC2020_TO_REC709 * FloatArray(3) { filmic(working[it]) }
        return graded.map {
            val t = it.coerceIn(0f, 1f)
            if (t < 0.0031308f) t * 12.92f else 1.055f * t.pow(1f / 2.4f) - 0.055f
        }
    }

    private fun filmic(x: Float) = (x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f)

    private fun saturation(rgb: List<Float>): Float {
        val hi = rgb.max()
        val lo = rgb.min()
        val l = (hi + lo) / 2
        return if (hi == lo) 0f else (hi - lo) / (1 - abs(2 * l - 1))
    }

    private fun hue(rgb: List<Float>): Float {
        val (r, g, b) = rgb
        val hi = max(r, max(g, b))
        val d = hi - min(r, min(g, b))
        if (d == 0f) return 0f
        val h = when (hi) {
            r -> ((g - b) / d).mod(6f)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return h * 60f
    }

    private fun hueGap(rgb: List<Float>, token: Color): Float {
        val gap = abs(hue(rgb) - hue(token.channels()))
        return min(gap, 360f - gap)
    }

    private companion object {
        /** The include the camera-stream materials share, from this module's directory. */
        const val SHARED_INVERSE = "../../sceneview/src/main/materials/include/view_tone_map_inverse.fs"

        /** The saturated chart of `CameraStreamColorContractTest`, as a photo or a camera image carries it. */
        val SATURATED_PHOTO = listOf(0x66B0F5, 0xD95F57, 0x4CB050, 0x2196F2, 0xF5428C, 0x00BDD4, 0xFFC207, 0x9E75BD)

        const val OLD_ALPHA = 0.55f
        const val BRIGHT_WALL = 0.95f
        const val MID_GREY = 0.5f
        const val DARK_CORNER = 0.15f

        /**
         * A white wall pulls the orange towards amber by ~21° and the blue towards cyan by ~11°:
         * still the same family, never pastel. (Graded per channel in Rec.709, the model put the
         * blue's drift at ~22°.)
         */
        const val MAX_HUE_DRIFT = 25f

        /** `LAID_DOWN` in `ar_mesh_tint.mat`: the colour laid down over black. */
        const val LAID_DOWN = 0.1f

        /** `GAIN` in `ar_mesh_tint.mat`. */
        const val GAIN = 2.5f

        /**
         * Hues, in degrees, that read as orange: below is red, above is amber and yellow. Graded in
         * Rec.2020, as Filament does, the orange over a black camera pixel shows as `#7D2100`, 15.7°
         * (17.2° in the per-channel Rec.709 model this test used to grade with): a dark burnt orange,
         * still clear of red (0–10°).
         */
        val ORANGE_HUES = 15f..50f
    }
}
