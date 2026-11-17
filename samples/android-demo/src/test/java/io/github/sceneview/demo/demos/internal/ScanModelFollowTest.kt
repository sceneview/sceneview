package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 3D Scan's live model is seen from behind the phone, eased: a turn swings the view round over
 * seconds and never moves it nearer or further, and the view steps back as the model grows
 * (2026-11-13, 2026-11-14).
 */
class ScanModelFollowTest {

    /** A phone at ([x], 1.4, [z]) turned [yawDegrees] about +Y, level. */
    private fun phone(yawDegrees: Float, x: Float = 0f, z: Float = 0f): DebugPose {
        val half = Math.toRadians(yawDegrees / 2.0)
        return DebugPose(x, 1.4f, z, qy = sin(half).toFloat(), qw = cos(half).toFloat())
    }

    /** A 4 × 4 m room about the origin, 2.5 m high. */
    private val box = floatArrayOf(-2f, 0f, -2f, 2f, 2.5f, 2f)

    /** A fit at [distance], looking at the framed circle's middle. */
    private fun room(distance: Float): (ScanModelView, Float) -> OrbitPose? = { v, heading ->
        OrbitPose(Position(v.x, (v.minY + v.maxY) / 2f, v.z), heading, 45f, distance)
    }

    /** The real fit, on a phone's tall screen. */
    private val fitted: (ScanModelView, Float) -> OrbitPose? = { v, heading ->
        ArDebugFraming.home(v.axis(), heading, 45.0, 0.5f, elevationDegrees = 45f, also = v.rings())
    }

    @Test
    fun startsBehindThePhone() {
        val pose = ScanModelFollow().poseFor(phone(60f), 0f, 35f, box, fit = room(5f))!!
        assertEquals(60f, pose.azimuthDegrees, 0.01f)
    }

    @Test
    fun keepsTheFallbackWithoutAPhone() {
        val pose = ScanModelFollow().poseFor(null, 0f, 35f, box, fit = room(5f))!!
        assertEquals(35f, pose.azimuthDegrees, 0.01f)
    }

    @Test
    fun aTurnEasesRoundOverSeconds() {
        val follow = ScanModelFollow()
        follow.poseFor(phone(0f), 0f, 0f, box, fit = room(5f))
        val after = follow.poseFor(phone(90f), 0.1f, 0f, box, fit = room(5f))!!
        // A tenth of a second turns it a few degrees, not the whole quarter turn.
        assertTrue(after.azimuthDegrees in 1f..10f)
        var pose = after
        var t = 0.1f
        while (t < 15f) {
            t += 0.1f
            pose = follow.poseFor(phone(90f), t, 0f, box, fit = room(5f))!!
        }
        assertEquals(90f, pose.azimuthDegrees, 0.5f)
    }

    @Test
    fun aStalledFrameNeverSwingsAtOnce() {
        val follow = ScanModelFollow()
        follow.poseFor(phone(0f), 0f, 0f, box, fit = room(5f))
        val after = follow.poseFor(phone(180f - 1f), 30f, 0f, box, fit = room(5f))!!
        assertTrue(after.azimuthDegrees < 60f)
    }

    @Test
    fun stepsBackAtOnceAndComesCloserSlowly() {
        val follow = ScanModelFollow()
        val far = follow.poseFor(phone(0f), 0f, 0f, box, fit = room(6f))!!
        assertEquals(6f, far.distance, 1e-4f)
        // A tenth of a second later the fit is twice as near: the view has barely moved.
        val near = follow.poseFor(phone(0f), 0.1f, 0f, box, fit = room(3f))!!
        assertTrue(near.distance > 5.9f)
        var pose = near
        var t = 0.1f
        while (t < 60f) {
            t += 0.1f
            pose = follow.poseFor(phone(0f), t, 0f, box, fit = room(3f))!!
        }
        assertEquals(3f, pose.distance, 0.01f)
        // And back out at once.
        assertEquals(7f, follow.poseFor(phone(0f), t + 0.1f, 0f, box, fit = room(7f))!!.distance, 1e-4f)
    }

    @Test
    fun aTurnAloneNeverMovesTheViewNearerOrFurther() {
        // The 4 × 4 m room seen from its side and from its corner: the same distance.
        val side = ScanModelFollow().poseFor(phone(0f), 0f, 0f, box, fit = fitted)!!
        val corner = ScanModelFollow().poseFor(phone(45f), 0f, 0f, box, fit = fitted)!!
        assertEquals(side.distance, corner.distance, side.distance * 0.01f)
    }

    @Test
    fun aPhoneInsideTheModelLeavesTheViewOnTheModel() {
        val view = ScanModelView.of(box, null, phone(0f, x = 1.5f, z = -1f))
        assertEquals(0f, view.x, 1e-4f)
        assertEquals(0f, view.z, 1e-4f)
        assertEquals(hypot(2f, 2f), view.radius, 1e-4f)
    }

    @Test
    fun theViewHoldsTheModelAndThePhoneWhereverItStands() {
        // A phone two metres past the room's edge (Codex's review of u26: a corner left the screen).
        val at = phone(90f, x = 4f, z = 0.5f)
        val v = ScanModelView.of(box, null, at)
        for ((x, z) in listOf(-2f to -2f, 2f to 2f, -2f to 2f, 2f to -2f)) {
            assertTrue(hypot(x - v.x, z - v.z) <= v.radius + 1e-3f)
        }
        // The phone with room for its view cone, the view pulled towards it.
        assertTrue(hypot(at.x - v.x, at.z - v.z) + ScanModelView.PHONE_MARGIN_M <= v.radius + 1e-3f)
        assertTrue(v.x > 0.3f)
        assertTrue(at.y >= v.minY && at.y <= v.maxY)
    }

    @Test
    fun aModelNarrowerThanItsBoxIsFramedCloser() {
        // The model reaches 2 m from the middle of a box whose corners lie 2.8 m away.
        val loose = ScanModelFollow().poseFor(phone(0f), 0f, 0f, box, fit = fitted)!!
        val tight = ScanModelFollow().poseFor(phone(0f), 0f, 0f, box, radius = 2f, fit = fitted)!!
        assertTrue(tight.distance < loose.distance * 0.8f)
    }

    @Test
    fun noRoomNoPose() {
        assertNull(ScanModelFollow().poseFor(phone(0f), 0f, 0f, box) { _, _ -> null })
        assertNull(ScanModelFollow().poseFor(phone(0f), 0f, 0f, null, fit = room(5f)))
    }
}
