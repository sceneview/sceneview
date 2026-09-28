package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the render-on-demand contract of the `camera-gestures` rig (#4064).
 *
 * `SceneView` parks its frame loop once the camera stops moving, and the rig integrates every
 * motion from `update()`, which only a running loop calls. A preset chip tapped on a parked scene
 * therefore started a flight that never took its first step: the HUD printed "Flying" and the
 * camera stayed put. The rig has to supply both halves itself — a wake-up when a motion starts from
 * outside the scene, and `isFrameActive` for as long as the motion lasts.
 */
class StudioCameraManipulatorTest {

    private var renderRequests = 0

    private fun rig() = StudioCameraManipulator(
        initialPose = OrbitPose(azimuthDegrees = 0f, elevationDegrees = 10f, distance = 2f),
        fitDistance = { 2f },
        requestRender = { renderRequests++ },
    )

    @Test
    fun `an idle rig lets the scene park`() {
        val rig = rig()
        assertFalse(rig.isFrameActive)
        assertEquals(0, renderRequests)
    }

    @Test
    fun `a flight wakes the loop and keeps it awake until it lands`() {
        val rig = rig()
        val destination = OrbitPose(azimuthDegrees = 90f, elevationDegrees = 20f, distance = 1.5f)

        rig.flyTo(destination, durationMillis = 100)

        assertEquals(1, renderRequests)
        assertTrue(rig.isFrameActive)
        assertEquals(RigGesture.Fly, rig.gesture)

        rig.update(0.05f)
        assertTrue("half-way through the flight", rig.isFrameActive)
        assertTrue(rig.pose.azimuthDegrees > 0f && rig.pose.azimuthDegrees < 90f)

        rig.update(0.06f)
        assertFalse("landed", rig.isFrameActive)
        assertNull(rig.gesture)
        assertEquals(90f, rig.pose.azimuthDegrees, 1e-3f)
    }

    @Test
    fun `the turntable wakes the loop and owes frames while it runs`() {
        val rig = rig()

        rig.cinematic = true
        assertEquals(1, renderRequests)
        assertTrue(rig.isFrameActive)

        rig.cinematic = false
        assertFalse(rig.isFrameActive)
    }

    @Test
    fun `a distance change from the slider repaints a parked scene`() {
        val rig = rig()

        rig.setDistance(2.5f)

        assertEquals(1, renderRequests)
        assertEquals(2.5f, rig.pose.distance, 1e-3f)
    }
}
