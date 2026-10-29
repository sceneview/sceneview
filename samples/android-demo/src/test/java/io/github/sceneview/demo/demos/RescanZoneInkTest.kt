package io.github.sceneview.demo.demos

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.theme.StageChrome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The frame round a part "not scanned yet" reads on both stages (#4735, WCAG 1.4.11): 3:1 or more
 * against everything it is drawn on — the stage's ground (the 3D model, the dollhouse), the glass
 * of the plan's card, the pill's card — and against its own veil over each of them.
 */
class RescanZoneInkTest {

    @Test
    fun `on the light stage the frame stands out of the ground, the plan's glass and its own veil`() =
        assertReads(StageChrome.Light, "light")

    @Test
    fun `on the dark stage the frame stands out of the ground, the plan's glass and its own veil`() =
        assertReads(StageChrome.Media, "dark")

    @Test
    fun `the veil stays the warning amber on both stages`() =
        assertEquals(SceneViewTokens.ArOverlay.accentGuidance.copy(alpha = VEIL_ALPHA), ZoneInk.veil)

    @Test
    fun `on the dark stage the frame keeps the warning amber the live map marks still to scan with`() =
        assertEquals(SceneViewTokens.ArOverlay.accentGuidance, ZoneInk.frame(StageChrome.Media))

    private fun assertReads(chrome: StageChrome, name: String) {
        val frame = ZoneInk.frame(chrome)
        val surfaces = listOf(
            "ground" to chrome.ground,
            "plan's glass" to chrome.glass.compositeOver(chrome.ground),
            "pill's card" to chrome.card.compositeOver(chrome.ground),
        )
        for ((what, surface) in surfaces) {
            for ((label, under) in listOf(what to surface, "veil over the $what" to ZoneInk.veil.compositeOver(surface))) {
                val ratio = contrast(frame, under)
                assertTrue(
                    "$name: the frame is ${String.format(Locale.ROOT, "%.2f", ratio)}:1 on the $label",
                    ratio >= MIN_GRAPHIC_CONTRAST,
                )
            }
        }
    }

    /** The WCAG 2 contrast ratio of two opaque colours. */
    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + FLARE) / (min(la, lb) + FLARE)
    }

    /** WCAG 2 relative luminance of an sRGB colour. */
    private fun luminance(c: Color): Double {
        fun linear(v: Float): Double = if (v <= SRGB_KNEE) v / SRGB_SLOPE else ((v + SRGB_OFFSET) / (1 + SRGB_OFFSET)).pow(SRGB_GAMMA)
        return LUMA_R * linear(c.red) + LUMA_G * linear(c.green) + LUMA_B * linear(c.blue)
    }

    private companion object {
        /** WCAG 1.4.11, non-text contrast: a graphic needed to understand the content. */
        const val MIN_GRAPHIC_CONTRAST = 3.0
        const val FLARE = 0.05
        const val SRGB_KNEE = 0.04045f
        const val SRGB_SLOPE = 12.92
        const val SRGB_OFFSET = 0.055
        const val SRGB_GAMMA = 2.4
        const val LUMA_R = 0.2126
        const val LUMA_G = 0.7152
        const val LUMA_B = 0.0722
    }
}
