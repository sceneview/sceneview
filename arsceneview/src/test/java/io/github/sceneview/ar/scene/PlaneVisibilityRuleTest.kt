package io.github.sceneview.ar.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `ARSceneView` has two plane knobs, `planeRenderer` and `SceneUnderstanding.planeVisualization`.
 * [resolvePlaneVisibility] is the only rule that writes `PlaneRenderer.isEnabled` (#5134):
 * planes are drawn only when both say yes.
 */
class PlaneVisibilityRuleTest {

    @Test
    fun `occlusion alone does not turn planes back on over planeRenderer false`() {
        // The llms.txt sample: turning occlusion on through the grouped knob used to force
        // planes on, because SceneUnderstanding.planeVisualization defaults to true.
        assertFalse(
            resolvePlaneVisibility(
                planeRenderer = false,
                sceneUnderstanding = SceneUnderstanding(occlusion = true),
            ),
        )
    }

    @Test
    fun `planeRenderer false hides planes with every named preset`() {
        listOf(SceneUnderstanding.Full, SceneUnderstanding.Minimal, SceneUnderstanding.None).forEach {
            assertFalse("$it", resolvePlaneVisibility(planeRenderer = false, sceneUnderstanding = it))
        }
    }

    @Test
    fun `without sceneUnderstanding planeRenderer decides alone`() {
        assertTrue(resolvePlaneVisibility(planeRenderer = true, sceneUnderstanding = null))
        assertFalse(resolvePlaneVisibility(planeRenderer = false, sceneUnderstanding = null))
    }

    @Test
    fun `planeVisualization false hides planes even with the planeRenderer default`() {
        // Existing apps that hide planes with Minimal or None, leaving planeRenderer at its
        // default `true`, keep hidden planes.
        assertFalse(resolvePlaneVisibility(planeRenderer = true, sceneUnderstanding = SceneUnderstanding.Minimal))
        assertFalse(resolvePlaneVisibility(planeRenderer = true, sceneUnderstanding = SceneUnderstanding.None))
        assertFalse(
            resolvePlaneVisibility(
                planeRenderer = true,
                sceneUnderstanding = SceneUnderstanding(occlusion = true, planeVisualization = false),
            ),
        )
    }

    @Test
    fun `both knobs at their defaults draw planes`() {
        assertTrue(resolvePlaneVisibility(planeRenderer = true, sceneUnderstanding = SceneUnderstanding()))
        assertTrue(resolvePlaneVisibility(planeRenderer = true, sceneUnderstanding = SceneUnderstanding.Full))
        assertTrue(
            resolvePlaneVisibility(planeRenderer = true, sceneUnderstanding = SceneUnderstanding(occlusion = true)),
        )
    }

    @Test
    fun `occlusion and lighting never affect plane visibility`() {
        for (planeRenderer in listOf(true, false)) {
            for (planeVisualization in listOf(true, false)) {
                val expected = planeRenderer && planeVisualization
                for (occlusion in listOf(true, false)) {
                    for (lighting in listOf(true, false)) {
                        val su = SceneUnderstanding(
                            occlusion = occlusion,
                            lighting = lighting,
                            planeVisualization = planeVisualization,
                        )
                        assertEquals("$planeRenderer / $su", expected, resolvePlaneVisibility(planeRenderer, su))
                    }
                }
            }
        }
    }

    @Test
    fun `ARSceneView writes plane visibility once, through the rule`() {
        // Source contract: a second `arPlaneRenderer.isEnabled =` write (as before #5134)
        // would let one knob silently override the other again.
        val source = File("src/main/java/io/github/sceneview/ar/ARSceneView.kt").readText()
        val writes = Regex("""arPlaneRenderer\.isEnabled\s*=(?!=)""").findAll(source).toList()
        assertEquals("exactly one arPlaneRenderer.isEnabled write in ARSceneView.kt", 1, writes.size)
        assertTrue(
            "the one write goes through resolvePlaneVisibility",
            source.contains("arPlaneRenderer.isEnabled = resolvePlaneVisibility(planeRenderer, sceneUnderstanding)"),
        )
    }
}
