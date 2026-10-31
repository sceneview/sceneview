package io.github.sceneview.demo.demos

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * The capture's chrome stays legible in sunlight: every fill behind its text is solid enough that
 * the camera under it no longer matters, measured on the brightest grounds a phone films (a white
 * wall in sun, a beige plaster, a mid wood floor) as well as the dark ones. Critical text holds
 * 7:1, secondary text 4.5:1, the shutter and the icons 3:1, and no line of the chrome is under
 * 16 sp.
 */
class CaptureChromeContrastTest {

    @Test
    fun `critical text holds 7 to 1 over any camera ground`() {
        for (fill in listOf(ScanControlFill, ScanBannerFill)) for ((name, ground) in GROUNDS) {
            val ratio = contrast(ArOverlay.onScrim, fill.compositeOver(ground))
            assertTrue("text on $fill over $name: $ratio", ratio >= 7f)
        }
    }

    @Test
    fun `secondary text holds 4,5 to 1 over any camera ground`() {
        for (fill in listOf(ScanControlFill, ScanBannerFill)) for ((name, ground) in GROUNDS) {
            val behind = fill.compositeOver(ground)
            val ratio = contrast(ArOverlay.onScrimMuted.compositeOver(behind), behind)
            assertTrue("muted text over $name: $ratio", ratio >= 4.5f)
        }
    }

    @Test
    fun `the shutter and the warning sign hold 3 to 1 over any camera ground`() {
        for ((name, ground) in GROUNDS) {
            val disc = ScanControlFill.compositeOver(ground)
            val ring = contrast(ArOverlay.onScrim, disc)
            val stop = contrast(ArOverlay.accentRecord, disc)
            val sign = contrast(ArOverlay.accentGuidance, ScanBannerFill.compositeOver(ground))
            assertTrue("shutter ring over $name: $ring", ring >= 3f)
            assertTrue("stop square over $name: $stop", stop >= 3f)
            assertTrue("warning sign over $name: $sign", sign >= 3f)
        }
    }

    @Test
    fun `no line of the chrome is under 16 sp`() {
        assertTrue(ScanLineStyle.fontSize.value >= 16f)
        assertTrue(ScanDetailStyle.fontSize.value >= 16f)
    }

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return max(la, lb) / min(la, lb)
    }

    private companion object {
        /** Camera grounds, brightest first: sunlit white wall, beige plaster, oak floor, dusk, night. */
        val GROUNDS = listOf(
            "sunlit white wall #F5F5F5" to Color(0xFFF5F5F5),
            "beige plaster #CFC8BD" to Color(0xFFCFC8BD),
            "wood floor #8A6F55" to Color(0xFF8A6F55),
            "dim room #1E1B18" to Color(0xFF1E1B18),
            "night #050505" to Color(0xFF050505),
        )
    }
}
