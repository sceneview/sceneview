package io.github.sceneview.web

import io.github.sceneview.web.xr.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for WebXR type declarations.
 *
 * These tests verify that the Kotlin external declarations compile correctly
 * and that constant values are set as expected. Actual WebXR API calls
 * require a browser with XR device support and cannot be tested in headless mode.
 */
class WebXRSessionModeTest {

    @Test
    fun sessionModeConstants() {
        assertEquals("inline", XRSessionMode.INLINE)
        assertEquals("immersive-vr", XRSessionMode.IMMERSIVE_VR)
        assertEquals("immersive-ar", XRSessionMode.IMMERSIVE_AR)
    }

    @Test
    fun sessionModesAreDifferent() {
        assertNotEquals(XRSessionMode.INLINE, XRSessionMode.IMMERSIVE_VR)
        assertNotEquals(XRSessionMode.IMMERSIVE_VR, XRSessionMode.IMMERSIVE_AR)
        assertNotEquals(XRSessionMode.INLINE, XRSessionMode.IMMERSIVE_AR)
    }
}

class WebXRReferenceSpaceTypeTest {

    @Test
    fun referenceSpaceTypeConstants() {
        assertEquals("viewer", XRReferenceSpaceType.VIEWER)
        assertEquals("local", XRReferenceSpaceType.LOCAL)
        assertEquals("local-floor", XRReferenceSpaceType.LOCAL_FLOOR)
        assertEquals("bounded-floor", XRReferenceSpaceType.BOUNDED_FLOOR)
        assertEquals("unbounded", XRReferenceSpaceType.UNBOUNDED)
    }

    @Test
    fun allReferenceSpaceTypesUnique() {
        val types = listOf(
            XRReferenceSpaceType.VIEWER,
            XRReferenceSpaceType.LOCAL,
            XRReferenceSpaceType.LOCAL_FLOOR,
            XRReferenceSpaceType.BOUNDED_FLOOR,
            XRReferenceSpaceType.UNBOUNDED
        )
        assertEquals(types.size, types.toSet().size, "All reference space types should be unique")
    }
}

class WebXRFeatureTest {

    @Test
    fun featureConstants() {
        assertEquals("hit-test", XRFeature.HIT_TEST)
        assertEquals("dom-overlay", XRFeature.DOM_OVERLAY)
        assertEquals("light-estimation", XRFeature.LIGHT_ESTIMATION)
        assertEquals("anchors", XRFeature.ANCHORS)
        assertEquals("plane-detection", XRFeature.PLANE_DETECTION)
        assertEquals("depth-sensing", XRFeature.DEPTH_SENSING)
        assertEquals("hand-tracking", XRFeature.HAND_TRACKING)
        assertEquals("layers", XRFeature.LAYERS)
        assertEquals("mesh-detection", XRFeature.MESH_DETECTION)
        assertEquals("image-tracking", XRFeature.IMAGE_TRACKING)
    }

    @Test
    fun allFeaturesUnique() {
        val features = listOf(
            XRFeature.HIT_TEST,
            XRFeature.DOM_OVERLAY,
            XRFeature.LIGHT_ESTIMATION,
            XRFeature.ANCHORS,
            XRFeature.PLANE_DETECTION,
            XRFeature.DEPTH_SENSING,
            XRFeature.HAND_TRACKING,
            XRFeature.LAYERS,
            XRFeature.MESH_DETECTION,
            XRFeature.IMAGE_TRACKING,
        )
        assertEquals(features.size, features.toSet().size, "All feature strings should be unique")
    }
}

class WebXRHandednessTest {

    @Test
    fun handednessConstants() {
        assertEquals("none", XRHandedness.NONE)
        assertEquals("left", XRHandedness.LEFT)
        assertEquals("right", XRHandedness.RIGHT)
    }
}

class WebXRTargetRayModeTest {

    @Test
    fun targetRayModeConstants() {
        assertEquals("gaze", XRTargetRayMode.GAZE)
        assertEquals("tracked-pointer", XRTargetRayMode.TRACKED_POINTER)
        assertEquals("screen", XRTargetRayMode.SCREEN)
        assertEquals("transient-pointer", XRTargetRayMode.TRANSIENT_POINTER)
    }
}

class WebXRHandJointTest {

    @Test
    fun wristJoint() {
        assertEquals("wrist", XRHandJoint.WRIST)
    }

    @Test
    fun thumbJoints() {
        assertEquals("thumb-metacarpal", XRHandJoint.THUMB_METACARPAL)
        assertEquals("thumb-phalanx-proximal", XRHandJoint.THUMB_PHALANX_PROXIMAL)
        assertEquals("thumb-phalanx-distal", XRHandJoint.THUMB_PHALANX_DISTAL)
        assertEquals("thumb-tip", XRHandJoint.THUMB_TIP)
    }

    @Test
    fun indexFingerJoints() {
        assertEquals("index-finger-metacarpal", XRHandJoint.INDEX_FINGER_METACARPAL)
        assertEquals("index-finger-phalanx-proximal", XRHandJoint.INDEX_FINGER_PHALANX_PROXIMAL)
        assertEquals("index-finger-phalanx-intermediate", XRHandJoint.INDEX_FINGER_PHALANX_INTERMEDIATE)
        assertEquals("index-finger-phalanx-distal", XRHandJoint.INDEX_FINGER_PHALANX_DISTAL)
        assertEquals("index-finger-tip", XRHandJoint.INDEX_FINGER_TIP)
    }

    @Test
    fun fingerTips() {
        assertTrue(XRHandJoint.THUMB_TIP.endsWith("-tip"))
        assertTrue(XRHandJoint.INDEX_FINGER_TIP.endsWith("-tip"))
        assertTrue(XRHandJoint.MIDDLE_FINGER_TIP.endsWith("-tip"))
        assertTrue(XRHandJoint.RING_FINGER_TIP.endsWith("-tip"))
        assertTrue(XRHandJoint.PINKY_FINGER_TIP.endsWith("-tip"))
    }

    @Test
    fun allJointsCount25() {
        val joints = listOf(
            XRHandJoint.WRIST,
            XRHandJoint.THUMB_METACARPAL, XRHandJoint.THUMB_PHALANX_PROXIMAL,
            XRHandJoint.THUMB_PHALANX_DISTAL, XRHandJoint.THUMB_TIP,
            XRHandJoint.INDEX_FINGER_METACARPAL, XRHandJoint.INDEX_FINGER_PHALANX_PROXIMAL,
            XRHandJoint.INDEX_FINGER_PHALANX_INTERMEDIATE, XRHandJoint.INDEX_FINGER_PHALANX_DISTAL,
            XRHandJoint.INDEX_FINGER_TIP,
            XRHandJoint.MIDDLE_FINGER_METACARPAL, XRHandJoint.MIDDLE_FINGER_PHALANX_PROXIMAL,
            XRHandJoint.MIDDLE_FINGER_PHALANX_INTERMEDIATE, XRHandJoint.MIDDLE_FINGER_PHALANX_DISTAL,
            XRHandJoint.MIDDLE_FINGER_TIP,
            XRHandJoint.RING_FINGER_METACARPAL, XRHandJoint.RING_FINGER_PHALANX_PROXIMAL,
            XRHandJoint.RING_FINGER_PHALANX_INTERMEDIATE, XRHandJoint.RING_FINGER_PHALANX_DISTAL,
            XRHandJoint.RING_FINGER_TIP,
            XRHandJoint.PINKY_FINGER_METACARPAL, XRHandJoint.PINKY_FINGER_PHALANX_PROXIMAL,
            XRHandJoint.PINKY_FINGER_PHALANX_INTERMEDIATE, XRHandJoint.PINKY_FINGER_PHALANX_DISTAL,
            XRHandJoint.PINKY_FINGER_TIP,
        )
        assertEquals(25, joints.size, "WebXR spec defines exactly 25 hand joints")
        assertEquals(25, joints.toSet().size, "All joint names should be unique")
    }
}

class WebXRSessionFeaturesTest {

    @Test
    fun defaultFeaturesEmpty() {
        val features = WebXRSession.Features()
        assertTrue(features.required.isEmpty())
        assertTrue(features.optional.isEmpty())
    }

    @Test
    fun customFeatures() {
        val features = WebXRSession.Features(
            required = arrayOf(XRFeature.HIT_TEST),
            optional = arrayOf(XRFeature.LIGHT_ESTIMATION, XRFeature.DOM_OVERLAY)
        )
        assertEquals(1, features.required.size)
        assertEquals(XRFeature.HIT_TEST, features.required[0])
        assertEquals(2, features.optional.size)
    }
}

class NavigatorXRTest {

    @Test
    fun navigatorXrIsNullInHeadlessEnvironment() {
        // In a headless test environment (Karma + ChromeHeadless), navigator.xr
        // may or may not be defined. We just verify the accessor doesn't throw.
        val xr = Navigator.xr
        // xr is null in most headless environments; non-null is also acceptable
        // This test ensures the external declaration compiles and the accessor works.
        println("Navigator.xr available: ${xr != null}")
    }
}
