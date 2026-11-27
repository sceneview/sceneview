package io.github.sceneview.ar.scene

import com.google.ar.core.Config
import io.github.sceneview.ar.PlaneRevealAnimation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaneRendererDepthOcclusionTest {
    @Test fun `plane depth runs through fade-out then stops until marks return`() {
        val animation = PlaneRevealAnimation()
        assertFalse(animation.hasVisibleMarks(show = false))
        // First visible frame must acquire depth even before opacity advances from zero.
        assertTrue(animation.hasVisibleMarks(show = true))
        animation.advance(dtSeconds = 1f, show = true, focusTarget = 0f)
        animation.advance(dtSeconds = 0.01f, show = false, focusTarget = 0f)
        assertTrue("Fading marks still need depth", animation.hasVisibleMarks(show = false))
        animation.advance(dtSeconds = 1f, show = false, focusTarget = 0f)
        repeat(120) {
            assertFalse("Placed state must not process plane depth", animation.hasVisibleMarks(false))
            animation.advance(dtSeconds = 1f / 60f, show = false, focusTarget = 0f)
        }
        assertTrue("Showing marks again restarts plane depth", animation.hasVisibleMarks(true))
    }

    @Test fun `plane depth stops only after the last visible plane finishes fading`() {
        val planes = listOf(PlaneRevealAnimation(), PlaneRevealAnimation())
        planes.forEach { it.advance(dtSeconds = 1f, show = true, focusTarget = 0f) }
        planes[0].advance(dtSeconds = 1f, show = false, focusTarget = 0f)
        planes[1].advance(dtSeconds = 0.01f, show = false, focusTarget = 0f)
        assertTrue(planes.any { it.hasVisibleMarks(false) })
        planes[1].advance(dtSeconds = 1f, show = false, focusTarget = 0f)
        assertFalse(planes.any { it.hasVisibleMarks(false) })
    }

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
        org.junit.Assert.assertEquals(listOf("enabled"), writes)
        assertTrue(source.contains("applyModelDepthOcclusion(sceneUnderstanding) { enabled ->"))
        assertTrue(source.contains("arPlaneRenderer.isDepthOcclusionEnabled = planeDepthOcclusion"))
        val publicSignature = source.substringAfter("fun ARSceneView(").substringBefore("\n) {")
        assertTrue(publicSignature.contains("depthMode: Config.DepthMode? = null"))
        assertTrue(publicSignature.contains("planeDepthOcclusion: Boolean = true"))
        val alias = source.substringAfter("fun ARScene(").substringBefore("\n) = ARSceneView(")
        assertTrue(alias.contains("planeDepthOcclusion: Boolean = true"))
    }

    @Test fun `placement scenes never infer model occlusion from depth availability`() {
        listOf("PlacementScene.kt", "AutoPlacementScene.kt").forEach { name ->
            val source = File("src/main/java/io/github/sceneview/ar/$name").readText()
            assertFalse("$name must leave the default camera stream under app control",
                source.contains("isDepthOcclusionEnabled"))
            assertFalse("$name must not opt in to grouped model occlusion",
                source.contains("sceneUnderstanding ="))
            assertFalse("$name must use ARSceneView's unchanged default camera stream",
                source.contains("cameraStream ="))
        }
    }
}
