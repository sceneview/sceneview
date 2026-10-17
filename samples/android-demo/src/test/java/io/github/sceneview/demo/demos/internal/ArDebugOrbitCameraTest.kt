package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins the free camera of the Rerun demo's in-app 3D view (#3950): it frames the session by
 * itself until touched, a touch hands it to the finger, and a double-tap hands it back.
 */
class ArDebugOrbitCameraTest {

    @Test
    fun `home frames a bigger session from further away`() {
        val small = ArDebugFraming.home(floatArrayOf(0f, 0f, 0f, 2f, 0.5f, 2f), 35f, 45.0, 0.5f)
        val large = ArDebugFraming.home(floatArrayOf(0f, 0f, 0f, 8f, 0.5f, 8f), 35f, 45.0, 0.5f)

        assertTrue(large.distance > small.distance)
        assertEquals(1f, small.target.x, 1e-4f)
        assertEquals(1f, small.target.z, 1e-4f)
        assertEquals(ArDebugFraming.HOME_ELEVATION, small.elevationDegrees, 1e-4f)
    }

    @Test
    fun `a portrait view stands further back than a landscape one, so the width fits too`() {
        val bounds = floatArrayOf(0f, 0f, 0f, 4f, 0.5f, 4f)
        val portrait = ArDebugFraming.home(bounds, 0f, 45.0, 0.46f)
        val landscape = ArDebugFraming.home(bounds, 0f, 45.0, 2.1f)

        assertTrue(portrait.distance > landscape.distance)
    }

    @Test
    fun `a bundled recording is framed tighter than a live session`() {
        val bounds = floatArrayOf(0f, 0f, 0f, 4f, 0.5f, 4f)
        val live = ArDebugFraming.home(bounds, 0f, 45.0, 0.46f)
        val replay = ArDebugFraming.home(bounds, 0f, 45.0, 0.46f, margin = ArDebugFraming.REPLAY_MARGIN)

        assertEquals(
            ArDebugFraming.REPLAY_MARGIN / ArDebugFraming.HOME_MARGIN,
            replay.distance / live.distance,
            1e-4f,
        )
    }

    @Test
    fun `an empty session opens on a room-sized view`() {
        val pose = ArDebugFraming.home(null, 12f, 45.0, 0.5f)

        assertEquals(ArDebugFraming.DEFAULT_POSE.distance, pose.distance, 1e-4f)
        assertEquals(12f, pose.azimuthDegrees, 1e-4f)
    }

    @Test
    fun `clamp keeps the camera above the floor and within zoom range`() {
        val pose = ArDebugFraming.clamp(OrbitPose(elevationDegrees = -80f, distance = 1_000f))

        assertEquals(ArDebugFraming.MIN_ELEVATION, pose.elevationDegrees, 1e-4f)
        assertEquals(ArDebugFraming.MAX_DISTANCE, pose.distance, 1e-4f)
        val clamped = ArDebugFraming.clamp(OrbitPose(distance = Float.NaN))
        assertEquals(ArDebugFraming.DEFAULT_POSE.distance, clamped.distance, 1e-4f)
    }

    @Test
    fun `the approach reaches home, at the same pace whatever the frame rate`() {
        val home = OrbitPose(azimuthDegrees = 90f, elevationDegrees = 30f, distance = 4f)
        var at60 = OrbitPose(distance = 2f)
        var at120 = OrbitPose(distance = 2f)
        repeat(30) { at60 = ArDebugFraming.approach(at60, home, 1f / 60f) }
        repeat(60) { at120 = ArDebugFraming.approach(at120, home, 1f / 120f) }
        assertEquals(at60.distance, at120.distance, 1e-3f)

        var pose = at60
        repeat(600) { pose = ArDebugFraming.approach(pose, home, 1f / 60f) }
        assertEquals(home, pose)
    }

    @Test
    fun `following eases to home, a drag takes over, a double-tap gives it back`() {
        val camera = ArDebugOrbitCamera(drift = false)
        camera.setViewport(1080, 2400)
        camera.home = OrbitPose(azimuthDegrees = 60f, elevationDegrees = 30f, distance = 5f)
        repeat(240) { camera.update(1f / 60f) }
        assertEquals(5f, camera.pose.distance, 1e-3f)
        assertTrue(camera.following)

        camera.grabBegin(500, 1000, strafe = false)
        camera.grabUpdate(600, 1000)
        camera.update(1f / 60f)
        camera.grabEnd()
        assertFalse(camera.following)
        val dragged = camera.pose.azimuthDegrees
        assertTrue(abs(dragged - 60f) > 10f)

        // A new home does not move a camera the user holds.
        camera.home = camera.home.copy(distance = 9f)
        repeat(240) { camera.update(1f / 60f) }
        assertTrue(abs(camera.pose.distance - 9f) > 1f)

        camera.doubleTapZoom(0, 0, zoomIn = true)
        assertTrue(camera.following)
        repeat(240) { camera.update(1f / 60f) }
        assertEquals(9f, camera.pose.distance, 1e-2f)
    }

    @Test
    fun `a finger down stops the turntable where it is, as a drag does`() {
        val camera = ArDebugOrbitCamera(drift = true)
        camera.setViewport(1080, 2400)
        camera.home = OrbitPose(azimuthDegrees = 60f, elevationDegrees = 30f, distance = 5f)
        repeat(240) { camera.update(1f / 60f) }
        val turned = camera.pose.azimuthDegrees
        repeat(60) { camera.update(1f / 60f) }
        assertTrue("the room turns by itself", abs(camera.pose.azimuthDegrees - turned) > 3f)

        camera.hold()
        assertFalse(camera.following)
        val held = camera.pose
        repeat(240) { camera.update(1f / 60f) }
        assertEquals(held, camera.pose)
    }

    @Test
    fun `the finger down of a double-tap does not stop the recenter it asked for`() {
        val camera = ArDebugOrbitCamera(drift = true)
        camera.setViewport(1080, 2400)
        camera.home = OrbitPose(azimuthDegrees = 60f, elevationDegrees = 30f, distance = 5f)
        camera.fingerDown()
        assertFalse(camera.following)
        // The second tap: the double-tap is told first, its finger down right after.
        camera.doubleTapZoom(540, 1200, zoomIn = true)
        camera.fingerDown()
        assertTrue(camera.following)
        camera.update(1f / 60f)
        camera.fingerDown()
        assertFalse("the next finger stops it again", camera.following)
    }

    @Test
    fun `steady, a recenter reframes and the room does not start turning again`() {
        val camera = ArDebugOrbitCamera(drift = true)
        camera.setViewport(1080, 2400)
        camera.home = OrbitPose(azimuthDegrees = 60f, elevationDegrees = 30f, distance = 5f)
        camera.hold()
        camera.steady = true
        camera.home = camera.home.copy(distance = 9f)
        camera.recenter()
        assertTrue(camera.following)
        val heading = camera.home.azimuthDegrees
        repeat(480) { camera.update(1f / 60f) }
        assertEquals("reframed", 9f, camera.pose.distance, 1e-2f)
        assertEquals("and still", heading, camera.pose.azimuthDegrees, 1e-2f)

        // Nothing picked any more: the turntable is back.
        camera.steady = false
        repeat(240) { camera.update(1f / 60f) }
        assertTrue(abs(camera.pose.azimuthDegrees - heading) > 3f)
    }

    @Test
    fun `a pinch zooms within range`() {
        val camera = ArDebugOrbitCamera(drift = false)
        camera.scrollBegin(0, 0, 100f)
        repeat(50) { camera.scrollUpdate(0, 0, 100f, 400f) }

        assertEquals(ArDebugFraming.MIN_DISTANCE, camera.pose.distance, 1e-3f)
        assertFalse(camera.following)
    }

    @Test
    fun `the viewport aspect follows the surface size`() {
        val camera = ArDebugOrbitCamera()
        camera.setViewport(300, 400)
        assertEquals(0.75f, camera.aspect, 1e-4f)

        camera.setViewport(0, 0) // a surface being torn down
        assertEquals(1f, camera.aspect, 1e-4f)
    }
}
