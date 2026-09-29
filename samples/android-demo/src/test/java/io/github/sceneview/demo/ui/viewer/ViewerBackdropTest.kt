package io.github.sceneview.demo.ui.viewer

import androidx.compose.ui.graphics.colorspace.ColorSpaces
import io.github.sceneview.demo.theme.SceneViewTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerBackdropTest {

    @Test
    fun `inverse filmic round-trips across the range`() {
        listOf(0.001f, 0.0033f, 0.01f, 0.05f, 0.2f, 0.5f, 0.8f).forEach { y ->
            assertEquals(y, ViewerBackdrop.filmic(ViewerBackdrop.inverseFilmic(y)), 1e-5f)
        }
    }

    @Test
    fun `the backdrop reaches the screen as the stage token`() {
        val token = SceneViewTokens.Stage.background.convert(ColorSpaces.LinearSrgb)
        val drawn = ViewerBackdrop.linearBeforeFilmic(SceneViewTokens.Stage.background)
        val shown = drawn.map(ViewerBackdrop::filmic)
        assertEquals(token.red, shown[0], 1e-5f)
        assertEquals(token.green, shown[1], 1e-5f)
        assertEquals(token.blue, shown[2], 1e-5f)
        // The toe crushes a colour this dark: what is drawn must be brighter than the token.
        assertTrue(drawn[2] > token.blue * 2f)
    }
}
