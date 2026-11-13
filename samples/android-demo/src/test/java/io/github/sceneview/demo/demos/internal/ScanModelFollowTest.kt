package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D Scan's live model is seen from behind the phone, eased: a turn swings the view round over
 * seconds, and the view only steps back as the room grows (2026-11-13).
 */
class ScanModelFollowTest {

    /** A phone at ([x], 1.4, [z]) turned [yawDegrees] about +Y, level. */
    private fun phone(yawDegrees: Float, x: Float = 0f, z: Float = 0f): DebugPose {
        val half = Math.toRadians(yawDegrees / 2.0)
        return DebugPose(x, 1.4f, z, qy = sin(half).toFloat(), qw = cos(half).toFloat())
    }

    private fun room(distance: Float): (Float) -> OrbitPose? = { heading ->
        OrbitPose(Position(0f, 1f, 0f), heading, 45f, distance)
    }

    @Test
    fun startsBehindThePhone() {
        val pose = ScanModelFollow().poseFor(phone(60f), 0f, 35f, room(5f))!!
        assertEquals(60f, pose.azimuthDegrees, 0.01f)
    }

    @Test
    fun keepsTheFallbackWithoutAPhone() {
        val pose = ScanModelFollow().poseFor(null, 0f, 35f, room(5f))!!
        assertEquals(35f, pose.azimuthDegrees, 0.01f)
    }

    @Test
    fun aTurnEasesRoundOverSeconds() {
        val follow = ScanModelFollow()
        follow.poseFor(phone(0f), 0f, 0f, room(5f))
        val after = follow.poseFor(phone(90f), 0.1f, 0f, room(5f))!!
        // A tenth of a second turns it a few degrees, not the whole quarter turn.
        assertTrue(after.azimuthDegrees in 1f..10f)
        var pose = after
        var t = 0.1f
        while (t < 15f) {
            t += 0.1f
            pose = follow.poseFor(phone(90f), t, 0f, room(5f))!!
        }
        assertEquals(90f, pose.azimuthDegrees, 0.5f)
    }

    @Test
    fun aStalledFrameNeverSwingsAtOnce() {
        val follow = ScanModelFollow()
        follow.poseFor(phone(0f), 0f, 0f, room(5f))
        val after = follow.poseFor(phone(180f - 1f), 30f, 0f, room(5f))!!
        assertTrue(after.azimuthDegrees < 60f)
    }

    @Test
    fun theReachOnlyGrows() {
        val follow = ScanModelFollow()
        val far = follow.poseFor(phone(0f), 0f, 0f, room(6f))!!
        val near = follow.poseFor(phone(0f), 0.1f, 0f, room(3f))!!
        assertEquals(far.distance, near.distance, 1e-4f)
        assertEquals(6f * ScanModelFollow.REACH_MARGIN, far.distance, 1e-4f)
    }

    @Test
    fun leansTowardsThePhone() {
        val pose = ScanModelFollow().poseFor(phone(0f, x = 2f, z = -1f), 0f, 0f, room(5f))!!
        assertEquals(2f * ScanModelFollow.PHONE_PULL, pose.target.x, 1e-4f)
        assertEquals(-1f * ScanModelFollow.PHONE_PULL, pose.target.z, 1e-4f)
        assertEquals(1f, pose.target.y, 1e-4f)
    }

    @Test
    fun noRoomNoPose() {
        assertNull(ScanModelFollow().poseFor(phone(0f), 0f, 0f) { null })
    }
}
