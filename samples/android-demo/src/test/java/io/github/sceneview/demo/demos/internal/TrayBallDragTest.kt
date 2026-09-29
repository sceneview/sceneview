package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM cover for the `rolling-balls` finger drag (#4180): picking the ball under the finger,
 * sliding it on the tray plane, and measuring the throw.
 */
class TrayBallDragTest {

    private val eps = 1e-3f

    private val eye = Float3(0f, 1f, 2f)

    private fun towards(target: Float3) = Float3(target.x - eye.x, target.y - eye.y, target.z - eye.z)

    @Test
    fun flatTrayFrameIsTheWorldFrame() {
        val v = Float3(0.3f, -0.4f, 0.5f)
        assertEquals(v, TrayBallDrag.toTrayFrame(0f, 0f, v))
    }

    @Test
    fun tiltedTrayFrameKeepsLengths() {
        val v = Float3(0.3f, -0.4f, 0.5f)
        val local = TrayBallDrag.toTrayFrame(15f, -10f, v)
        val length = { f: Float3 -> sqrt(f.x * f.x + f.y * f.y + f.z * f.z) }
        assertEquals(length(v), length(local), eps)
    }

    @Test
    fun pickHitsTheBallUnderTheFinger() {
        val balls = listOf(
            TrayBallDrag.Candidate(1, Float3(-0.4f, -0.42f, 0f), 0.075f),
            TrayBallDrag.Candidate(2, Float3(0.4f, -0.42f, 0f), 0.075f),
        )
        assertEquals(2, TrayBallDrag.pickBall(eye, towards(balls[1].center), balls))
        assertEquals(1, TrayBallDrag.pickBall(eye, towards(balls[0].center), balls))
    }

    @Test
    fun pickForgivesAFingertipJustOffTheEdge() {
        val ball = TrayBallDrag.Candidate(7, Float3(0f, -0.44f, 0f), 0.06f)
        // Aim 2 cm past the ball's silhouette: inside the slop, still a grab.
        val nearMiss = towards(Float3(0.08f, -0.44f, 0f))
        assertEquals(7, TrayBallDrag.pickBall(eye, nearMiss, listOf(ball)))
        // 20 cm off: the felt, not the ball — the drag goes to the tilt or the orbit.
        val clearMiss = towards(Float3(0.2f, -0.44f, 0f))
        assertNull(TrayBallDrag.pickBall(eye, clearMiss, listOf(ball)))
    }

    @Test
    fun pickTakesTheNearestOfTwoBallsInLine() {
        val far = TrayBallDrag.Candidate(1, Float3(0f, -0.42f, -0.4f), 0.075f)
        val near = TrayBallDrag.Candidate(2, Float3(0f, -0.1f, 1f), 0.075f)
        // A ray from the eye through `near` towards `far`.
        val direction = towards(near.center)
        assertEquals(2, TrayBallDrag.pickBall(eye, direction, listOf(far, near)))
    }

    @Test
    fun projectionLandsOnThePlane() {
        val target = Float3(0.25f, -0.43f, -0.1f)
        val hit = TrayBallDrag.projectOnPlane(eye, towards(target), -0.43f)
        assertNotNull(hit)
        assertEquals(target.x, hit!!.x, eps)
        assertEquals(-0.43f, hit.y, eps)
        assertEquals(target.z, hit.z, eps)
    }

    @Test
    fun projectionRejectsARayThatNeverReachesThePlane() {
        assertNull(TrayBallDrag.projectOnPlane(eye, Float3(0f, 1f, -1f), -0.43f))
        assertNull(TrayBallDrag.projectOnPlane(eye, Float3(1f, 0f, 0f), -0.43f))
    }

    @Test
    fun throwVelocityFollowsTheFinger() {
        val tracker = TrayBallDrag.ThrowVelocityTracker()
        // 1 m/s along +X, sampled every 16 ms.
        for (i in 0..10) tracker.add(1_000L + i * 16L, i * 0.016f, 0f)
        val (vx, vz) = tracker.velocity(1_000L + 160L)
        assertEquals(1f, vx, 0.01f)
        assertEquals(0f, vz, eps)
    }

    @Test
    fun throwVelocityIsClamped() {
        val tracker = TrayBallDrag.ThrowVelocityTracker()
        tracker.add(0L, 0f, 0f)
        tracker.add(16L, 0f, 1f) // 62 m/s
        val (vx, vz) = tracker.velocity(16L)
        assertEquals(0f, vx, eps)
        assertEquals(TrayBallDrag.MAX_THROW_SPEED, vz, eps)
    }

    @Test
    fun aFingerThatStoppedLetsTheBallGoGently() {
        val tracker = TrayBallDrag.ThrowVelocityTracker()
        for (i in 0..5) tracker.add(i * 16L, i * 0.05f, 0f)
        // Lifted 300 ms after the last movement.
        val (vx, vz) = tracker.velocity(80L + 300L)
        assertEquals(0f, vx, eps)
        assertEquals(0f, vz, eps)
    }

    @Test
    fun onlyTheRecentWindowCounts() {
        val tracker = TrayBallDrag.ThrowVelocityTracker()
        // A slow start, then a fast flick in the last 64 ms.
        for (i in 0..20) tracker.add(i * 16L, i * 0.001f, 0f)
        var x = 20 * 0.001f
        for (i in 21..24) {
            x += 0.04f
            tracker.add(i * 16L, x, 0f)
        }
        val (vx, _) = tracker.velocity(24 * 16L)
        assertTrue("the flick, not the slow start, sets the throw: $vx", vx > 1.5f)
    }
}
