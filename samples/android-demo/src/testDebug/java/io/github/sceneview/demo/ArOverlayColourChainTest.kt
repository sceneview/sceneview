package io.github.sceneview.demo

import androidx.compose.ui.graphics.Color
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The AR overlay colours through the AR colour chain, on the JVM (#4623).
 *
 * The camera image reaches the HDR buffer as `Inverse_Tonemap_Filmic(sRGB EOTF(c))`
 * ([cameraToBuffer], what `camera_stream_flat.mat` does), an overlay blends with it there, and
 * `ToneMapper.Filmic` + the sRGB OETF bring the result to the screen. The tint material blends
 * as `0.1·S + S·dst` with `S = colour · strength · 2.5` (`ar_mesh_tint.mat`); the overlays it replaced
 * blended as `a·colour + (1 − a)·dst`.
 */
class ArOverlayColourChainTest {

    @Test
    fun `the camera image round-trips through the chain`() {
        for (code in listOf(0, 16, 64, 128, 200, 235, 255)) {
            val s = code / 255f
            assertEquals("code $code", s, screen(cameraToBuffer(s)), 1.5f / 255f)
        }
    }

    @Test
    fun `the old translucent overlays wash out over a bright wall`() {
        val wall = cameraToBuffer(BRIGHT_WALL)
        for (old in listOf(SceneViewColors.Primary, SceneViewColors.TintSoft)) {
            val out = old.channels().map { screen(OLD_ALPHA * linear(it) + (1 - OLD_ALPHA) * wall) }
            assertTrue("$old kept saturation ${saturation(out)}", saturation(out) < 0.3f)
        }
    }

    @Test
    fun `the tint keeps each overlay colour saturated and on its hue, bright wall to dark corner`() {
        for (token in listOf(SceneViewTokens.ArOverlay.meshGround, SceneViewTokens.ArOverlay.meshStructure)) {
            for (camera in listOf(BRIGHT_WALL, MID_GREY, DARK_CORNER)) {
                val dst = cameraToBuffer(camera)
                val out = token.channels().map {
                    val s = linear(it) * SceneViewTokens.ArOverlay.MESH_FILL_ALPHA * GAIN
                    screen(LAID_DOWN * s + s * dst)
                }
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
            val dst = cameraToBuffer(camera)
            val out = SceneViewTokens.ArOverlay.meshStructure.channels().map {
                val s = linear(it) * SceneViewTokens.ArOverlay.MESH_FILL_ALPHA * GAIN
                screen(LAID_DOWN * s + s * dst)
            }
            assertTrue("over $camera: hue ${hue(out)}", hue(out) in ORANGE_HUES)
        }
    }

    private fun Color.channels() = listOf(red, green, blue)

    private fun linear(s: Float) = if (s < 0.04045f) s / 12.92f else ((s + 0.055f) / 1.055f).pow(2.4f)

    /** `ToneMapper.Filmic` then the exact sRGB OETF. */
    private fun screen(x: Float): Float {
        val t = ((x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f)).coerceIn(0f, 1f)
        return if (t < 0.0031308f) t * 12.92f else 1.055f * t.pow(1f / 2.4f) - 0.055f
    }

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
        const val OLD_ALPHA = 0.55f
        const val BRIGHT_WALL = 0.95f
        const val MID_GREY = 0.5f
        const val DARK_CORNER = 0.15f

        /** A white wall pulls the blue towards cyan by ~22°: still the same family, never pastel. */
        const val MAX_HUE_DRIFT = 25f

        /** `LAID_DOWN` in `ar_mesh_tint.mat`: the colour laid down over black. */
        const val LAID_DOWN = 0.1f

        /** `GAIN` in `ar_mesh_tint.mat`. */
        const val GAIN = 2.5f

        /** Hues, in degrees, that read as orange: below is red, above is amber and yellow. */
        val ORANGE_HUES = 17f..50f
    }
}
