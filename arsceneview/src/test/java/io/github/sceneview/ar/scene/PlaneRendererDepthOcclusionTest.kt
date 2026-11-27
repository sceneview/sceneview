package io.github.sceneview.ar.scene

import com.google.ar.core.Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaneRendererDepthOcclusionTest {
    @Test fun `automatic depth occludes plane marks`() {
        assertTrue(shouldOccludePlanes(Config.DepthMode.AUTOMATIC))
    }

    @Test fun `raw depth also occludes plane marks by default`() {
        assertTrue(shouldOccludePlanes(Config.DepthMode.RAW_DEPTH_ONLY))
    }

    @Test fun `unsupported or explicitly disabled depth does not occlude marks`() {
        // ARSession.configure downgrades unsupported modes to DISABLED before ARCore runs.
        assertFalse(shouldOccludePlanes(Config.DepthMode.DISABLED))
    }

    @Test fun `changing configuration turns off stale depth then restores occlusion`() {
        assertTrue(shouldOccludePlanes(Config.DepthMode.AUTOMATIC))
        assertFalse(shouldOccludePlanes(Config.DepthMode.DISABLED))
        assertTrue(shouldOccludePlanes(Config.DepthMode.AUTOMATIC))
    }

    @Test fun `plane opt-out wins for every configured depth mode`() {
        Config.DepthMode.values().forEach { mode ->
            assertFalse(shouldOccludePlanes(mode, enabled = false))
        }
    }

    @Test fun `plane opt-out can be reversed while depth stays enabled`() {
        assertTrue(shouldOccludePlanes(Config.DepthMode.AUTOMATIC))
        assertFalse(shouldOccludePlanes(Config.DepthMode.AUTOMATIC, enabled = false))
        assertTrue(shouldOccludePlanes(Config.DepthMode.AUTOMATIC, enabled = true))
    }

    @Test fun `plain scene leaves model occlusion under explicit app control`() {
        val source = File("src/main/java/io/github/sceneview/ar/ARSceneView.kt").readText()
        val writes = Regex("""cameraStream\??\.isDepthOcclusionEnabled\s*=\s*([^\n]+)""")
            .findAll(source).map { it.groupValues[1].trim() }.toList()
        org.junit.Assert.assertEquals(listOf("su.occlusion"), writes)
        assertTrue(source.contains("arPlaneRenderer.isDepthOcclusionEnabled = planeDepthOcclusion"))
        val publicSignature = source.substringAfter("fun ARSceneView(").substringBefore("\n) {")
        assertTrue(publicSignature.contains("depthMode: Config.DepthMode? = null"))
        assertTrue(publicSignature.contains("planeDepthOcclusion: Boolean = true"))
        val alias = source.substringAfter("fun ARScene(").substringBefore("\n) = ARSceneView(")
        assertTrue(alias.contains("planeDepthOcclusion: Boolean = true"))
    }
}
