package io.github.sceneview.demo.demos.internal

import io.github.sceneview.camera.CameraPath
import io.github.sceneview.camera.CameraShot
import io.github.sceneview.camera.CameraShotVelocity
import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScanResultCinematics] (#4631) over the result screen's frame loop, in `SceneView`'s order:
 * the manipulator is updated, the frame drawn with its transform, then `onFrame` runs.
 */
class ScanResultCinematicsTest {

    private val home = OrbitPose(
        target = Position(0.2f, 0.8f, -0.4f),
        azimuthDegrees = 35f,
        elevationDegrees = 40f,
        distance = 5.5f,
    )
    private val plan = home.copy(elevationDegrees = ArDebugFraming.MAP_ELEVATION, distance = 6.2f)

    private inner class Screen {
        val orbit = ArDebugOrbitCamera(drift = false)
        val cinematics = ScanResultCinematics(orbit)
        val opening = DollhouseOpening()
        var framing = home
        var shownOn = 0
        var frames = 0

        fun frame(dt: Float = 1f / 60f) {
            cinematics.manipulator.update(dt)
            cinematics.manipulator.getTransform()
            frames++
            if (cinematics.frameDrawn(opening, framing, ready = true)) shownOn = frames
        }

        fun run(seconds: Float) = repeat((seconds * 60f).toInt() + 1) { frame() }

        fun reveal() {
            opening.aim(orbit, framing)
            while (!cinematics.revealed) frame()
            run(CameraPath.REVEAL_SECONDS + 0.2f)
        }
    }

    private fun assertShot(expected: OrbitPose, actual: CameraShot, tolerance: Float = 1e-3f) {
        assertEquals(expected.azimuthDegrees, actual.azimuthDegrees, tolerance)
        assertEquals(expected.elevationDegrees, actual.elevationDegrees, tolerance)
        assertEquals(expected.distance, actual.distance, tolerance)
        assertEquals(expected.target.x, actual.target.x, tolerance)
        assertEquals(expected.target.y, actual.target.y, tolerance)
        assertEquals(expected.target.z, actual.target.z, tolerance)
    }

    @Test
    fun `an orbit pose and a camera shot are the same four numbers`() {
        assertEquals(home, home.toShot().toOrbitPose())
        assertEquals(CameraShot(home.target, 35f, 40f, 5.5f), home.toShot())
        // Same convention: the same eye.
        val eye = CameraRig.eye(home)
        val shotEye = home.toShot().eye
        assertEquals(eye.x, shotEye.x, 1e-4f)
        assertEquals(eye.y, shotEye.y, 1e-4f)
        assertEquals(eye.z, shotEye.z, 1e-4f)
    }

    @Test
    fun `the reveal waits for the model on its framing, then plays once`() {
        val screen = Screen()
        screen.opening.aim(screen.orbit, home)
        repeat(DollhouseOpening.STEADY_FRAMES - 1) {
            screen.frame()
            assertFalse(screen.cinematics.revealed)
        }
        screen.frame()
        assertTrue(screen.cinematics.revealed)
        assertTrue(screen.cinematics.animator.isPlaying)
        // Not shown on the still frame before the move: on the move's first frame.
        assertEquals(0, screen.shownOn)
        screen.frame()
        assertEquals(DollhouseOpening.STEADY_FRAMES + 1, screen.shownOn)
        // It opens high, wide and to the side of home, not on it.
        val first = screen.cinematics.animator.shot
        assertTrue(first.elevationDegrees > home.elevationDegrees + 15f)
        assertTrue(first.distance > home.distance * 1.5f)
    }

    @Test
    fun `the reveal lands exactly on the framing, where the orbit camera already is`() {
        val screen = Screen()
        screen.reveal()
        assertFalse(screen.cinematics.animator.isPlaying)
        assertShot(home, screen.cinematics.animator.shot)
        assertEquals(ArDebugFraming.clamp(home), screen.orbit.pose)
        assertTrue(screen.orbit.following)
        // Nothing cuts when the animator lets go: its last picture is the orbit camera's.
        assertEquals(screen.orbit.getTransform(), screen.cinematics.manipulator.getTransform())
    }

    @Test
    fun `a touch during the reveal takes the camera where it is, moving`() {
        val screen = Screen()
        screen.opening.aim(screen.orbit, home)
        while (!screen.cinematics.revealed) screen.frame()
        screen.run(1f)
        val mid = screen.cinematics.animator.shot
        screen.cinematics.manipulator.grabBegin(10, 10, strafe = false)
        assertFalse(screen.cinematics.animator.isPlaying)
        assertFalse(screen.orbit.following)
        assertShot(mid.toOrbitPose(), screen.orbit.pose.toShot())
    }

    @Test
    fun `plan is one move from where the camera is, landing on the plan's framing`() {
        val screen = Screen()
        screen.reveal()
        screen.framing = plan
        screen.cinematics.request(ScanResultCinematics.Request.Recenter)
        screen.frame()
        assertTrue(screen.cinematics.animator.isPlaying)
        // The first frame of the move is still next to the 3D view: no cut to the plan.
        assertTrue(screen.cinematics.animator.shot.elevationDegrees < home.elevationDegrees + 2f)
        screen.run(1f)
        assertFalse(screen.cinematics.animator.isPlaying)
        assertShot(plan, screen.cinematics.animator.shot)
        assertEquals(ArDebugFraming.clamp(plan), screen.orbit.pose)
    }

    @Test
    fun `recenter after a drag starts from the dragged picture`() {
        val screen = Screen()
        screen.reveal()
        val manipulator = screen.cinematics.manipulator
        manipulator.grabBegin(0, 0, strafe = false)
        manipulator.grabUpdate(-300, 0)
        manipulator.grabEnd()
        screen.run(1f)
        val dragged = screen.orbit.pose
        assertTrue(kotlin.math.abs(dragged.azimuthDegrees - home.azimuthDegrees) > 10f)
        screen.cinematics.request(ScanResultCinematics.Request.Recenter)
        screen.frame()
        val start = screen.cinematics.animator.shot
        assertTrue(kotlin.math.abs(start.azimuthDegrees - dragged.azimuthDegrees) < 3f)
        screen.run(1f)
        assertShot(home, screen.cinematics.animator.shot)
    }

    @Test
    fun `the tour plays from home and stops on request, coasting`() {
        val screen = Screen()
        screen.reveal()
        screen.cinematics.request(ScanResultCinematics.Request.ToggleTour)
        screen.frame()
        assertTrue(screen.cinematics.touring)
        screen.run(2f)
        assertTrue(screen.cinematics.animator.isPlaying)
        val turned = screen.cinematics.animator.shot.azimuthDegrees - home.azimuthDegrees
        assertTrue("the tour turns: $turned", turned > 20f)
        screen.cinematics.request(ScanResultCinematics.Request.ToggleTour)
        screen.frame()
        assertFalse(screen.cinematics.touring)
        assertFalse(screen.cinematics.animator.isPlaying)
        assertFalse(screen.orbit.following)
        // The orbit camera picked the tour up where it was and keeps turning a little.
        val stoppedAt = screen.orbit.pose.azimuthDegrees
        screen.frame()
        assertTrue(screen.orbit.pose.azimuthDegrees > stoppedAt)
    }

    @Test
    fun `a tour asked away from home flies there first`() {
        val screen = Screen()
        screen.reveal()
        val manipulator = screen.cinematics.manipulator
        manipulator.grabBegin(0, 0, strafe = false)
        manipulator.grabUpdate(-300, 0)
        manipulator.grabEnd()
        screen.run(1f)
        val dragged = screen.orbit.pose
        screen.cinematics.request(ScanResultCinematics.Request.ToggleTour)
        screen.frame()
        assertTrue(kotlin.math.abs(screen.cinematics.animator.shot.azimuthDegrees - dragged.azimuthDegrees) < 3f)
        screen.run(0.75f)
        assertTrue(screen.cinematics.touring)
        assertTrue(screen.cinematics.animator.isPlaying)
    }

    @Test
    fun `a hand-over coasts at the move's speed, capped like a fling`() {
        val orbit = ArDebugOrbitCamera(drift = false, maxCoastDegrees = 90f)
        orbit.takeOver(home.toShot(), CameraShotVelocity(azimuthDegreesPerSecond = 10_000f))
        assertFalse(orbit.following)
        assertEquals(ArDebugFraming.clamp(home), orbit.pose)
        orbit.update(0.1f)
        val turned = orbit.pose.azimuthDegrees - home.azimuthDegrees
        assertTrue("capped at maxSpin: $turned", turned <= orbit.maxSpin * 0.1f + 1e-3f && turned > 0f)
    }
}
