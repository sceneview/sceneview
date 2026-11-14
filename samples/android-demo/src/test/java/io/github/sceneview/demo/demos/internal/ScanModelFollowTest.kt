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

    /** A 4 × 4 m room about the origin, 2.5 m high. */
    private val box = floatArrayOf(-2f, 0f, -2f, 2f, 2.5f, 2f)

    /** A fit at [distance], looking at the framed box's centre. */
    private fun room(distance: Float): (FloatArray, Float) -> OrbitPose? = { b, heading ->
        OrbitPose(Position((b[0] + b[3]) / 2f, (b[1] + b[4]) / 2f, (b[2] + b[5]) / 2f), heading, 45f, distance)
    }

    @Test
    fun startsBehindThePhone() {
        val pose = ScanModelFollow().poseFor(phone(60f), 0f, 35f, box, room(5f))!!
        assertEquals(60f, pose.azimuthDegrees, 0.01f)
    }

    @Test
    fun keepsTheFallbackWithoutAPhone() {
        val pose = ScanModelFollow().poseFor(null, 0f, 35f, box, room(5f))!!
        assertEquals(35f, pose.azimuthDegrees, 0.01f)
    }

    @Test
    fun aTurnEasesRoundOverSeconds() {
        val follow = ScanModelFollow()
        follow.poseFor(phone(0f), 0f, 0f, box, room(5f))
        val after = follow.poseFor(phone(90f), 0.1f, 0f, box, room(5f))!!
        // A tenth of a second turns it a few degrees, not the whole quarter turn.
        assertTrue(after.azimuthDegrees in 1f..10f)
        var pose = after
        var t = 0.1f
        while (t < 15f) {
            t += 0.1f
            pose = follow.poseFor(phone(90f), t, 0f, box, room(5f))!!
        }
        assertEquals(90f, pose.azimuthDegrees, 0.5f)
    }

    @Test
    fun aStalledFrameNeverSwingsAtOnce() {
        val follow = ScanModelFollow()
        follow.poseFor(phone(0f), 0f, 0f, box, room(5f))
        val after = follow.poseFor(phone(180f - 1f), 30f, 0f, box, room(5f))!!
        assertTrue(after.azimuthDegrees < 60f)
    }

    @Test
    fun theReachOnlyGrows() {
        val follow = ScanModelFollow()
        val far = follow.poseFor(phone(0f), 0f, 0f, box, room(6f))!!
        val near = follow.poseFor(phone(0f), 0.1f, 0f, box, room(3f))!!
        assertEquals(far.distance, near.distance, 1e-4f)
        assertEquals(6f * ScanModelFollow.REACH_MARGIN, far.distance, 1e-4f)
    }

    @Test
    fun leansTowardsThePhone() {
        val pose = ScanModelFollow().poseFor(phone(0f, x = 2f, z = -1f), 0f, 0f, box, room(5f))!!
        assertEquals(2f * ScanModelFollow.PHONE_PULL, pose.target.x, 1e-4f)
        assertEquals(-1f * ScanModelFollow.PHONE_PULL, pose.target.z, 1e-4f)
    }

    @Test
    fun theFramedBoxHoldsTheRoomAndThePhoneWhereverItStands() {
        // A phone a metre past the room's edge (Codex's review of u26).
        val at = phone(90f, x = 3f, z = 0.5f)
        val v = ScanModelFollow.viewBox(box, at)
        for ((x, z) in listOf(-2f to -2f, 2f to 2f, -2f to 2f, 2f to -2f, at.x to at.z)) {
            assertTrue(x >= v[0] && x <= v[3] && z >= v[2] && z <= v[5])
        }
        // Its centre leans towards the phone.
        assertTrue((v[0] + v[3]) / 2f > 0.5f)
    }

    @Test
    fun noRoomNoPose() {
        assertNull(ScanModelFollow().poseFor(phone(0f), 0f, 0f, box) { _, _ -> null })
        assertNull(ScanModelFollow().poseFor(phone(0f), 0f, 0f, null, room(5f)))
    }
}
