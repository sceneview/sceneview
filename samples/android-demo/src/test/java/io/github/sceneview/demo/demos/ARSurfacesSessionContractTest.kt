package io.github.sceneview.demo.demos

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Configuration contracts for the live plane showcase (#4228). ARCore and Filament require
 * native sessions, so these guard the wiring, not the appearance on a camera feed.
 */
class ARSurfacesSessionContractTest {

    @Test
    fun `the showcase requests depth so the camera pass can occlude plane marks`() {
        val source = File("src/main/java/io/github/sceneview/demo/demos/ARSurfacesDemo.kt").readText()
        assertTrue(
            "Occlusion cannot hide furniture without a depth-producing ARCore session",
            source.contains("depthMode = Config.DepthMode.AUTOMATIC"),
        )
        assertTrue(
            "Producing depth alone does not enable the camera's occlusion pass",
            Regex("sceneUnderstanding\\s*=\\s*SceneUnderstanding\\(\\s*occlusion\\s*=\\s*true,")
                .containsMatchIn(source),
        )
        assertTrue(
            "Grouped scene understanding must preserve the demo's hide and drag controls",
            source.contains("planeVisualization = showcase.planesVisible"),
        )
        assertFalse(source.contains("isDepthOcclusionEnabled = false"))
    }

    @Test
    fun `the ripple wraps time before mobile fragment precision is lost`() {
        val source = File("../../arsceneview/src/main/materials/plane_renderer.mat").readText()
        assertTrue(source.contains("highp float ripplePhase = getUserTimeMod("))
        assertFalse("Unbounded engine uptime loses subsecond precision", source.contains("getUserTime()"))
    }
}
