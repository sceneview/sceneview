package io.github.sceneview.demo

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Recenter flight-origin fix (#3622): tapping Recenter used to rebuild
 * [EntranceCameraManipulator], discarding wherever the user had orbited to and replaying the
 * cold-open's synthetic swung-off-axis start every time. [EntranceCameraManipulator.beginRecenterFlight]
 * captures the pose [getTransform] is reporting right before the caller resets `progress`, so the
 * flight interpolates from the pose the user actually left the camera at to the resting one —
 * never from a pose the user never saw.
 */
class EntranceCameraManipulatorRecenterTest {

    private val target = Position(0f, 0f, 0f)
    private val restingEye = Position(0f, 0f, 2f)

    /** As if the user had dragged the camera round to the far side and zoomed out. */
    private val orbitedEye = Position(3f, 1.5f, -2f)

    private fun assertPositionEquals(expected: Position, actual: Position, tolerance: Float = 1e-4f) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.z, actual.z, tolerance)
    }

    /**
     * Builds a manipulator standing in for "the camera is currently at [orbitedEye]": progress
     * pinned at 1 (its own entrance long since settled) and `eye` set to the orbited pose, the
     * same way a completed flight or an active [fallback] hand-off would report it through
     * [EntranceCameraManipulator.getTransform] on the real screen.
     */
    private fun orbitedManipulator(liveEye: () -> Position, progress: () -> Float) =
        EntranceCameraManipulator(eye = liveEye, target = { target }, progress = progress)

    @Test
    fun `recenter flight starts exactly at the pose the user was orbiting from`() {
        var eye = orbitedEye
        var progress = 1f
        val manipulator = orbitedManipulator({ eye }, { progress })
        assertPositionEquals(orbitedEye, manipulator.getTransform().position)

        // The dock's Recenter action: capture the current pose, THEN the caller resets progress.
        manipulator.beginRecenterFlight()
        eye = restingEye
        progress = 0f

        assertPositionEquals(orbitedEye, manipulator.getTransform().position)
    }

    @Test
    fun `recenter flight ends exactly at the resting pose`() {
        var eye = orbitedEye
        var progress = 1f
        val manipulator = orbitedManipulator({ eye }, { progress })
        manipulator.beginRecenterFlight()
        eye = restingEye

        progress = 1f
        assertPositionEquals(restingEye, manipulator.getTransform().position)
    }

    @Test
    fun `recenter flight interpolates linearly between the captured and resting poses`() {
        var eye = orbitedEye
        var progress = 1f
        val manipulator = orbitedManipulator({ eye }, { progress })
        manipulator.beginRecenterFlight()
        eye = restingEye

        progress = 0.4f
        val mid = manipulator.getTransform().position
        val expected = Position(
            x = orbitedEye.x + (restingEye.x - orbitedEye.x) * 0.4f,
            y = orbitedEye.y + (restingEye.y - orbitedEye.y) * 0.4f,
            z = orbitedEye.z + (restingEye.z - orbitedEye.z) * 0.4f,
        )
        assertPositionEquals(expected, mid)
    }
}
