package io.github.sceneview.demo.common.placement

import io.github.sceneview.ar.PlacementPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #4957: the depth, people and stabilisation comparisons drew a large jagged dark patch on the
 * floor around the helmet — its hard cast shadow, from a low estimated light, caught by a
 * [ShadowReceiverPlane][io.github.sceneview.ar.ARSceneScope.ShadowReceiverPlane] spread over each
 * plane's whole rectangle. The comparisons keep the contact shadow only.
 */
class FeaturePlacementGroundShadowsTest {

    @Test
    fun `catchers only stand with ground shadows on, under a placed or moving object`() {
        PlacementPhase.entries.forEach { phase ->
            val standing = phase == PlacementPhase.PLACED || phase == PlacementPhase.ADJUSTING
            assertEquals("$phase", standing, catchesGroundShadows(groundShadows = true, phase = phase))
            assertFalse("$phase", catchesGroundShadows(groundShadows = false, phase = phase))
        }
    }

    @Test
    fun `the feature comparisons mount no plane-wide shadow catcher`() {
        val source = File(
            "src/main/java/io/github/sceneview/demo/common/placement/ARFeatureComparison.kt",
        ).readText()
        assertTrue(source.contains("groundShadows = false"))
        assertFalse(source.contains("ShadowReceiverPlane("))
        assertFalse(
            "The helmet keeps the soft contact shadow that grounds it",
            source.contains("showsContactShadow = false"),
        )
    }

    @Test
    fun `the scene's catchers follow its groundShadows flag`() {
        val source = File(
            "src/main/java/io/github/sceneview/demo/common/placement/FeaturePlacementScene.kt",
        ).readText()
        assertTrue(source.contains("if (catchesGroundShadows(groundShadows, state.phase))"))
    }
}
