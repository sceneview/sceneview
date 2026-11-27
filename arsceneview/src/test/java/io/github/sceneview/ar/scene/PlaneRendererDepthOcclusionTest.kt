package io.github.sceneview.ar.scene

import com.google.ar.core.Config
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaneRendererDepthOcclusionTest {
    @Test fun `automatic depth occludes plane marks`() {
        assertTrue(shouldOccludePlanes(Config.DepthMode.AUTOMATIC))
    }

    @Test fun `raw depth also supports the camera occlusion pass`() {
        assertTrue(shouldOccludePlanes(Config.DepthMode.RAW_DEPTH_ONLY))
    }

    @Test fun `unsupported or explicitly disabled depth uses the flat camera`() {
        // ARSession.configure downgrades unsupported modes to DISABLED before ARCore runs.
        assertFalse(shouldOccludePlanes(Config.DepthMode.DISABLED))
    }

    @Test fun `changing configuration turns off stale depth then restores occlusion`() {
        assertTrue(shouldOccludePlanes(Config.DepthMode.AUTOMATIC))
        assertFalse(shouldOccludePlanes(Config.DepthMode.DISABLED))
        assertTrue(shouldOccludePlanes(Config.DepthMode.AUTOMATIC))
    }
}
