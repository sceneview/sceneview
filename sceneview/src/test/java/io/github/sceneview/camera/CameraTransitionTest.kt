package io.github.sceneview.camera

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CameraTransition]: exact ends, the fly-over bump, and frame-rate independence. */
class CameraTransitionTest {

    private val home = CameraShot(Position(0f, 0f, 0f), 35f, 32f, 3f)
    private val plan = home.copy(elevationDegrees = 84f)
    private val nextRoom = CameraShot(Position(4f, 0f, 3f), 35f, 32f, 3f)

    @Test
    fun endsAreExact() {
        val move = CameraTransition(home, plan, durationSeconds = 0.7f)
        assertEquals(home, move.shotAt(0f))
        assertEquals(plan, move.shotAt(0.7f))
        assertEquals(plan, move.shotAt(5f))
        assertEquals(home, move.shotAt(-1f))
        assertTrue(move.isFinishedAt(0.7f))
        assertTrue(!move.isFinishedAt(0.69f))
    }

    @Test
    fun zeroDurationIsAlreadyThere() {
        val move = CameraTransition(home, plan, durationSeconds = 0f)
        assertEquals(plan, move.shotAt(0f))
        assertTrue(move.isFinishedAt(0f))
    }

    @Test
    fun sameSubjectMovesOnTheArcWithoutPullingBack() {
        val move = CameraTransition(home, plan, durationSeconds = 1f)
        for (i in 1..9) {
            val shot = move.shotAt(i / 10f)
            assertEquals(home.distance, shot.distance, 1e-5f)
            assertEquals(home.azimuthDegrees, shot.azimuthDegrees, 1e-5f)
            assertTrue(shot.elevationDegrees in home.elevationDegrees..plan.elevationDegrees)
        }
    }

    @Test
    fun anotherSubjectFliesOverAtMidFlight() {
        val move = CameraTransition(home, nextRoom, durationSeconds = 1f, easing = CameraEasing.Linear)
        val mid = move.shotAt(0.5f)
        // Targets are 5 m apart: half of it is added at the top of the bump.
        assertEquals(home.distance + 0.5f * 5f, mid.distance, 1e-4f)
        assertEquals(Position(2f, 0f, 1.5f).x, mid.target.x, 1e-5f)
        assertEquals(home, move.shotAt(0f))
        assertEquals(nextRoom, move.shotAt(1f))
        // The bump is monotonic up to the middle and back down.
        var previous = move.shotAt(0f).distance
        for (i in 1..5) {
            val d = move.shotAt(i / 10f).distance
            assertTrue(d >= previous)
            previous = d
        }
        for (i in 6..10) {
            val d = move.shotAt(i / 10f).distance
            assertTrue(d <= previous)
            previous = d
        }
    }

    @Test
    fun flyOverZeroIsAStraightCarry() {
        val mid = cameraTransition(home, nextRoom, 0.5f, CameraEasing.Linear, flyOver = 0f)
        assertEquals(home.distance, mid.distance, 1e-5f)
    }

    @Test
    fun expressiveEasingLeavesGentlyAndLandsSoftly() {
        val e = CameraEasing.Expressive
        assertEquals(0f, e(0f), 1e-4f)
        assertEquals(1f, e(1f), 1e-4f)
        // Front-loaded: past half-way by the middle of the time.
        assertTrue(e(0.5f) > 0.7f)
        // And monotonic.
        var previous = 0f
        for (i in 1..100) {
            val v = e(i / 100f)
            assertTrue(v >= previous)
            previous = v
        }
    }

    @Test
    fun frameRateDoesNotChangeTheMove() {
        val move = CameraTransition(home, nextRoom, durationSeconds = 0.7f)
        // Stepping at 30 Hz and at 120 Hz, the shot at any common instant is the same, because
        // the move is a function of time, not of frame count.
        val at30 = move.shotAt(6 / 30f)
        val at120 = move.shotAt(24 / 120f)
        assertEquals(at30.azimuthDegrees, at120.azimuthDegrees, 1e-5f)
        assertEquals(at30.distance, at120.distance, 1e-4f)
        assertEquals(at30.target.x, at120.target.x, 1e-5f)
    }
}
