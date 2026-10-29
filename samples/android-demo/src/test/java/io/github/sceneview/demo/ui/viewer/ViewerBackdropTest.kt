package io.github.sceneview.demo.ui.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerBackdropTest {

    @Test
    fun `inverse PBR Neutral round-trips across the range`() {
        // Scene-linear inputs from deep shadow to three times the compression knee: every colour
        // the forward curve can output, the inverse brings back.
        val samples = listOf(0f, 0.001f, 0.0033f, 0.01f, 0.03f, 0.05f, 0.2f, 0.5f, 0.75f, 1f, 1.6f, 2.5f)
        samples.forEach { a ->
            samples.forEach { b ->
                val scene = floatArrayOf(a, b, (a + b) / 2f)
                val back = ViewerBackdrop.inversePbrNeutral(ViewerBackdrop.pbrNeutral(scene))
                for (i in 0..2) assertEquals("($a, $b) channel $i", scene[i], back[i], 1e-3f * maxOf(1f, scene[i]))
            }
        }
    }

    @Test
    fun `PBR Neutral is the identity in the mid range`() {
        // The property that keeps base colours as authored: no toe offset above 0.08, no
        // compression below 0.76 — the shift is the constant 0.04 only.
        val mid = floatArrayOf(0.3f, 0.5f, 0.7f)
        val out = ViewerBackdrop.pbrNeutral(mid)
        for (i in 0..2) assertEquals(mid[i] - 0.04f, out[i], 1e-6f)
    }

    @Test
    fun `the backdrop reaches the screen as the stage token, light and dark`() {
        listOf(SceneViewTokens.Stage.background, Color(0xFF07090D), Color(0xFFF5F6F8)).forEach { color ->
            val token = color.convert(ColorSpaces.LinearSrgb)
            val drawn = ViewerBackdrop.linearBeforeToneMap(color)
            val shown = ViewerBackdrop.pbrNeutral(drawn)
            assertEquals(token.red, shown[0], 2e-4f)
            assertEquals(token.green, shown[1], 2e-4f)
            assertEquals(token.blue, shown[2], 2e-4f)
            // The toe pulls a channel down: what is drawn must be brighter than the token.
            assertTrue(drawn[2] > token.blue)
        }
    }

    @Test
    fun `PBR Neutral keeps a mid grey minus its toe offset`() {
        val shown = ViewerBackdrop.pbrNeutral(floatArrayOf(0.18f, 0.18f, 0.18f))
        shown.forEach { assertEquals(0.14f, it, 1e-6f) }
    }

    @Test
    fun `inverse PBR Neutral round-trips in the toe, the linear part and the shoulder`() {
        listOf(
            floatArrayOf(0.004f, 0.01f, 0.02f),
            floatArrayOf(0.03f, 0.05f, 0.07f),
            floatArrayOf(0.2f, 0.3f, 0.5f),
            floatArrayOf(0.6f, 0.62f, 0.7f),
            floatArrayOf(0.9f, 1.2f, 2f),
            floatArrayOf(3f, 3f, 3f),
        ).forEach { scene ->
            val back = ViewerBackdrop.inversePbrNeutral(ViewerBackdrop.pbrNeutral(scene))
            scene.indices.forEach { assertEquals(scene[it], back[it], scene[it] * 1e-3f + 1e-5f) }
        }
    }

    @Test
    fun `every showroom token reaches the screen as itself`() {
        listOf(SceneViewTokens.Showroom.Light, SceneViewTokens.Showroom.Dark).forEach { palette ->
            listOf(palette.top, palette.horizon, palette.floor, palette.pool).forEach { color ->
                val token = color.convert(ColorSpaces.LinearSrgb)
                val shown = ViewerBackdrop.pbrNeutral(ViewerBackdrop.linearBeforeNeutral(color))
                assertEquals(token.red, shown[0], 1e-4f)
                assertEquals(token.green, shown[1], 1e-4f)
                assertEquals(token.blue, shown[2], 1e-4f)
            }
        }
    }
}
