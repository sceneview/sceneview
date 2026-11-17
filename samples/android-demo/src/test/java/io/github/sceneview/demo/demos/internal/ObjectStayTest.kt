package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** [ObjectStay]: an object walked round keeps the phone's aim in one place; a room walked does not. */
class ObjectStayTest {
    /** A phone at ([x], [z]), level, looking along ([towardsX], [towardsZ]). */
    private fun phone(x: Float, z: Float, towardsX: Float, towardsZ: Float): DebugPose {
        // A camera looks down its own -Z: turned by yaw about +Y, it looks along (-sin, 0, -cos).
        val yaw = kotlin.math.atan2(-towardsX, -towardsZ)
        return DebugPose(x, EYE_M, z, qy = sin(yaw / 2f), qw = cos(yaw / 2f))
    }

    /** One second in ten frames. */
    private fun frames(seconds: Float) = (0..(seconds * FPS).toInt()).map { it * 1_000L / FPS }

    @Test
    fun `a phone turned by yaw looks where the test means it to`() {
        val ahead = phone(0f, 0f, towardsX = 1f, towardsZ = 0f).rotate(0f, 0f, -1f)
        assertTrue(ahead.x > 0.99f)
        assertTrue(kotlin.math.abs(ahead.z) < 0.01f)
    }

    @Test
    fun `an object walked all the way round is never left`() {
        val stay = ObjectStay()
        // A metre from a chair at the origin, once round it in twenty seconds, always looking at it.
        val left = frames(20f).any { ms ->
            val angle = 2.0 * PI * ms / 20_000.0
            val x = cos(angle).toFloat()
            val z = sin(angle).toFloat()
            stay.strayed(phone(x, z, towardsX = -x, towardsZ = -z), ms)
        }
        assertFalse(left)
    }

    @Test
    fun `a room walked across is said so`() {
        val stay = ObjectStay()
        // Looking ahead, walking 0.5 m a second. The first aim is its mean over the first second
        // and a half, 0.375 m on: the aim is 1.5 m past it at 3.75 s, and has stayed so at 5.25 s.
        val saidAt = frames(8f).firstOrNull { ms -> stay.strayed(phone(0.5f * ms / 1_000f, 0f, 1f, 0f), ms) }
        assertTrue("said between seconds 5 and 5.5, was $saidAt", saidAt != null && saidAt in 5_000L..5_500L)
    }

    @Test
    fun `a glance aside leaves nothing`() {
        val stay = ObjectStay()
        val left = frames(10f).any { ms ->
            // One second looking at the far wall, between seconds 4 and 5.
            val aside = ms in 4_000L..5_000L
            stay.strayed(if (aside) phone(-1f, 0f, -1f, 0f) else phone(-1f, 0f, 1f, 0f), ms)
        }
        assertFalse(left)
    }

    @Test
    fun `back on its object, the phone is no longer away`() {
        val stay = ObjectStay()
        frames(2f).forEach { ms -> stay.strayed(phone(-1f, 0f, 1f, 0f), ms) }
        // Three metres further on, long enough to count.
        assertTrue(frames(3f).map { ms -> stay.strayed(phone(2f, 0f, 1f, 0f), 2_000L + ms) }.last())
        assertFalse(stay.strayed(phone(-1f, 0f, 1f, 0f), 6_000L))
    }

    @Test
    fun `a pose not known keeps what was last said`() {
        val stay = ObjectStay()
        assertFalse(stay.strayed(null, 0L))
        frames(2f).forEach { ms -> stay.strayed(phone(-1f, 0f, 1f, 0f), ms) }
        frames(3f).forEach { ms -> stay.strayed(phone(2f, 0f, 1f, 0f), 2_000L + ms) }
        assertTrue(stay.strayed(null, 6_000L))
    }

    private companion object {
        const val EYE_M = 1.4f
        const val FPS = 10
    }
}
