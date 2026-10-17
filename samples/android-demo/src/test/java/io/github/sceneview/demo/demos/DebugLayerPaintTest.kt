package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.DebugLayer
import io.github.sceneview.demo.theme.SceneViewTokens.DebugView
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A theme switch repaints the 3D view's flat layers in place (#4330): each keeps the material it
 * was created on, the opaque unlit one or the translucent one. That only holds while a layer is
 * on the same side of that line in every palette — a layer solid in one theme and see-through in
 * the other would be drawn with the wrong material after a switch.
 */
class DebugLayerPaintTest {
    @Test
    fun `every layer is opaque in both palettes or translucent in both`() {
        for (layer in DebugLayer.entries) {
            val dark = paintOf(layer, DebugView.Dark).color.alpha
            val light = paintOf(layer, DebugView.Light).color.alpha
            assertEquals("$layer: alpha $dark on the dark stage, $light on the light one", dark >= 1f, light >= 1f)
        }
    }

    @Test
    fun `a layer keeps its draw priority across palettes`() {
        // The nodes take their priority once, from the dark palette: it must not be a theme's choice.
        for (layer in DebugLayer.entries) {
            assertEquals("$layer", paintOf(layer, DebugView.Dark).priority, paintOf(layer, DebugView.Light).priority)
        }
    }
}
