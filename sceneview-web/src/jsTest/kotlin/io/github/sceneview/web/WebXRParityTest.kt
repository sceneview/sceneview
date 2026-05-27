@file:Suppress("MaxLineLength") // test assertion strings with shader expressions are legitimately long

package io.github.sceneview.web

import io.github.sceneview.web.xr.DepthOcclusionShader
import io.github.sceneview.web.xr.XRDepthFormat
import io.github.sceneview.web.xr.XRDepthInfo
import io.github.sceneview.web.xr.XRDepthUsage
import io.github.sceneview.web.xr.XRFeature
import io.github.sceneview.web.xr.XRHandJoint
import io.github.sceneview.web.xr.XRHandNode
import io.github.sceneview.web.xr.XRImageTrackingNode
import io.github.sceneview.web.xr.XRImageTrackingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the WebXR parity composables introduced by #1778
 * (depth-sensing, hand-tracking, image-tracking, anchors).
 *
 * Only pure logic is covered here — browser-side WebXR calls require a real
 * XR-capable device.
 */

class XRDepthFormatTest {
    @Test
    fun usageConstants() {
        assertEquals("cpu-optimized", XRDepthUsage.CPU_OPTIMIZED)
        assertEquals("gpu-optimized", XRDepthUsage.GPU_OPTIMIZED)
    }

    @Test
    fun formatConstants() {
        assertEquals("luminance-alpha", XRDepthFormat.LUMINANCE_ALPHA)
        assertEquals("float32", XRDepthFormat.FLOAT32)
    }
}

class XRDepthInfoTest {

    @Test
    fun fromNullReturnsNull() {
        assertNull(XRDepthInfo.from(null))
    }

    @Test
    fun fromZeroSizeReturnsNull() {
        val payload = js("{ width: 0, height: 0, rawValueToMeters: 0.001 }")
        @Suppress("UnsafeCastFromDynamic")
        val info = payload.unsafeCast<io.github.sceneview.web.xr.XRDepthInformation>()
        assertNull(XRDepthInfo.from(info))
    }

    @Test
    fun fromNonPositiveRawScaleReturnsNull() {
        val payload = js("{ width: 160, height: 90, rawValueToMeters: 0.0 }")
        @Suppress("UnsafeCastFromDynamic")
        val info = payload.unsafeCast<io.github.sceneview.web.xr.XRDepthInformation>()
        assertNull(XRDepthInfo.from(info))
    }

    @Test
    fun fromValidPayloadProducesInstance() {
        val payload =
            js("{ width: 160, height: 90, rawValueToMeters: 0.001, getDepthInMeters: function(){return 0.5;} }")
        @Suppress("UnsafeCastFromDynamic")
        val info = payload.unsafeCast<io.github.sceneview.web.xr.XRDepthInformation>()
        val depth = XRDepthInfo.from(info)
        assertNotNull(depth)
        assertEquals(160, depth.width)
        assertEquals(90, depth.height)
        assertEquals(0.001, depth.rawValueToMeters)
        assertTrue(depth.isCpuReadable)
        assertEquals(0.5, depth.atNormalized(0.5, 0.5))
    }

    @Test
    fun atNormalizedClampsOutOfRangeInputs() {
        val payload = js(
            "{ width: 160, height: 90, rawValueToMeters: 0.001, " +
                "getDepthInMeters: function(x, y){ if(x>1.0||x<0.0||y>1.0||y<0.0) return -1; return 0.42; } }"
        )
        @Suppress("UnsafeCastFromDynamic")
        val info = payload.unsafeCast<io.github.sceneview.web.xr.XRDepthInformation>()
        val depth = XRDepthInfo.from(info)
        assertNotNull(depth)
        // Out-of-range inputs are clamped before forwarding, so the JS function
        // sees x,y in [0,1] and returns 0.42 rather than -1.
        assertEquals(0.42, depth.atNormalized(-2.0, 5.0))
    }

    @Test
    fun atNormalizedReturnsInfinityWhenGpuOnly() {
        val payload = js("{ width: 160, height: 90, rawValueToMeters: 0.001 }")
        @Suppress("UnsafeCastFromDynamic")
        val info = payload.unsafeCast<io.github.sceneview.web.xr.XRDepthInformation>()
        val depth = XRDepthInfo.from(info)
        assertNotNull(depth)
        assertFalse(depth.isCpuReadable)
        assertEquals(Double.POSITIVE_INFINITY, depth.atNormalized(0.5, 0.5))
    }
}

class DepthOcclusionShaderTest {
    @Test
    fun shadersDeclareGlslEs300() {
        assertTrue(DepthOcclusionShader.VERTEX_SHADER.startsWith("#version 300 es"))
        assertTrue(DepthOcclusionShader.FRAGMENT_SHADER.startsWith("#version 300 es"))
    }

    @Test
    fun fragmentShaderReferencesRequiredUniforms() {
        val src = DepthOcclusionShader.FRAGMENT_SHADER
        assertTrue("uniform sampler2D uDepthTexture;" in src)
        assertTrue("uniform float uRawValueToMeters;" in src)
        assertTrue("uniform mat4 uNormDepthBufferFromNormView;" in src)
        assertTrue("uniform vec4 uColor;" in src)
        assertTrue("uniform float uFragmentDepthMeters;" in src)
    }

    @Test
    fun fragmentShaderDiscardsOccludedPixels() {
        val src = DepthOcclusionShader.FRAGMENT_SHADER
        assertTrue("discard;" in src, "Fragment shader must discard occluded pixels")
        assertTrue("realDepth < uFragmentDepthMeters" in src, "Fragment shader must compare real depth against fragment depth")
    }
}

class XRHandNodeTest {

    @Test
    fun handednessAliasesMatchSpec() {
        assertEquals("left", XRHandNode.Handedness.LEFT)
        assertEquals("right", XRHandNode.Handedness.RIGHT)
        assertEquals("none", XRHandNode.Handedness.NONE)
    }

    @Test
    fun jointAliasesResolveToSpecStrings() {
        assertEquals(XRHandJoint.INDEX_FINGER_TIP, XRHandNode.Joint.INDEX_TIP)
        assertEquals(XRHandJoint.THUMB_TIP, XRHandNode.Joint.THUMB_TIP)
        assertEquals(XRHandJoint.WRIST, XRHandNode.Joint.WRIST)
        assertEquals(XRHandJoint.MIDDLE_FINGER_PHALANX_INTERMEDIATE, XRHandNode.Joint.MIDDLE_INTERMEDIATE)
    }

    @Test
    fun jointAllIs25Unique() {
        val joints = XRHandNode.Joint.ALL
        assertEquals(25, joints.size)
        assertEquals(25, joints.toSet().size)
    }

    @Test
    fun jointDslRegistersCallbacks() {
        val node = XRHandNode(XRHandNode.Handedness.LEFT)
            .joint(XRHandNode.Joint.INDEX_TIP) { _ -> }
            .joint(XRHandNode.Joint.THUMB_TIP) { _ -> }
        assertEquals(2, node.jointCount)
        assertTrue(node.isObserving(XRHandNode.Joint.INDEX_TIP))
        assertFalse(node.isObserving(XRHandNode.Joint.WRIST))
    }

    @Test
    fun removeJointDropsCallback() {
        val node = XRHandNode(XRHandNode.Handedness.RIGHT)
            .joint(XRHandNode.Joint.INDEX_TIP) { _ -> }
        assertTrue(node.removeJoint(XRHandNode.Joint.INDEX_TIP))
        assertFalse(node.removeJoint(XRHandNode.Joint.INDEX_TIP))
        assertEquals(0, node.jointCount)
    }

    @Test
    fun clearDropsAll() {
        val node = XRHandNode(XRHandNode.Handedness.LEFT)
            .joint(XRHandNode.Joint.INDEX_TIP) { _ -> }
            .joint(XRHandNode.Joint.THUMB_TIP) { _ -> }
        node.clear()
        assertEquals(0, node.jointCount)
    }
}

class XRImageTrackingStateTest {
    @Test
    fun stateConstants() {
        assertEquals("tracked", XRImageTrackingState.TRACKED)
        assertEquals("emulated", XRImageTrackingState.EMULATED)
        assertEquals("untracked", XRImageTrackingState.UNTRACKED)
    }
}

class XRImageTrackingNodeTest {
    @Test
    fun storesIndexAndDefaults() {
        val node = XRImageTrackingNode(index = 2)
        assertEquals(2, node.index)
        assertNull(node.onFound)
        assertNull(node.onLost)
    }
}
