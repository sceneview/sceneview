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
            source.contains("isDepthOcclusionEnabled = true") && source.contains("cameraStream = cameraStream"),
        )
        assertTrue(
            "The demo's hide and drag controls drive the plane renderer",
            source.contains("planeRenderer = showcase.planesVisible"),
        )
        assertFalse(
            "The showcase keeps one depth switch, on the camera stream; planeRenderer is its plane toggle",
            source.contains("sceneUnderstanding ="),
        )
        assertFalse(source.contains("isDepthOcclusionEnabled = false"))
    }
}
