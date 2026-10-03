package io.github.sceneview.demo.demos

import io.github.sceneview.demo.SceneViewColors
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins #4031: in Picking & Collision the "lit" colour was [SceneViewColors.TintSoft], which is
 * also a default swatch of the shapes ([SceneViewColors.Ramp4]`[3]`). A tap that picked that
 * shape lit it in the colour it already wore, and the demo read as "tapping does nothing".
 * The highlight must never be one of the swatches a shape can start with.
 */
class PickingHighlightColorTest {

    @Test
    fun `the highlight colour is not a default shape colour`() {
        assertFalse(SceneViewColors.Highlight in SceneViewColors.Ramp4)
    }
}
