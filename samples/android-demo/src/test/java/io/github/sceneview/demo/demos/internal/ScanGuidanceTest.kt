package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pins the scan's coach: the speed it says "Slow down" at, the hysteresis that keeps its line
 * from flickering, the order its reasons rank in, and how long a line stays.
 */
class ScanGuidanceTest {
    private fun yaw(degrees: Float, x: Float = 0f): DebugPose {
        val half = Math.toRadians(degrees / 2.0)
        return DebugPose(x, 1.4f, 0f, qy = sin(half).toFloat(), qw = cos(half).toFloat())
    }

    private fun ms(value: Long) = value * 1_000_000L

    @Test
    fun `motion reads the turning and moving speed of the camera`() {
        assertEquals(10f, ScanMotion.angleDegrees(yaw(0f), yaw(10f)), 1e-3f)
        val motion = ScanMotion()
        for (i in 0..20) motion.update(ms(100L * i), yaw(10f * i, x = 0.05f * i))
        assertEquals(100f, motion.degreesPerSecond, 2f)
        assertEquals(0.5f, motion.metresPerSecond, 0.02f)
        assertEquals(ScanMotion.BLURRED_FACTOR, motion.lookFactor, 1e-4f)
    }

    @Test
    fun `a gap in the camera's poses is not a motion`() {
        val motion = ScanMotion()
        motion.update(0L, yaw(0f))
        motion.update(ms(2_000L), yaw(90f, x = 1f))
        assertEquals(0f, motion.degreesPerSecond, 1e-4f)
        assertEquals(1f, motion.lookFactor, 1e-4f)
    }

    @Test
    fun `a latch waits for its condition to hold`() {
        val latch = ScanLatch(enterNs = 100L, exitNs = 200L)
        assertFalse(latch.update(0L, enter = true, exit = false))
        assertFalse("interrupted", latch.update(50L, enter = false, exit = false))
        assertFalse(latch.update(60L, enter = true, exit = false))
        assertTrue(latch.update(160L, enter = true, exit = false))
        assertTrue("between the thresholds", latch.update(1_000L, enter = false, exit = false))
        assertTrue(latch.update(1_100L, enter = false, exit = true))
        assertFalse(latch.update(1_300L, enter = false, exit = true))
    }

    @Test
    fun `too fast says slow down, and the paint grows again as soon as the camera calms`() {
        val coach = ScanCoach()
        assertNull(coach.update(0L, ScanCoach.Reading(60f, 0f)))
        assertEquals(ScanAdvice.SlowDown, coach.update(ms(300L), ScanCoach.Reading(60f, 0f)))
        assertTrue(coach.isTooFast)

        coach.update(ms(400L), ScanCoach.Reading(20f, 0f))
        assertEquals(ScanAdvice.SlowDown, coach.update(ms(1_000L), ScanCoach.Reading(20f, 0f)))
        assertFalse("calm again", coach.isTooFast)
        val calm = ScanCoach.Reading(20f, 0f)
        assertEquals("the line stays its minimum", ScanAdvice.SlowDown, coach.update(ms(1_700L), calm))
        assertNull(coach.update(ms(1_800L), ScanCoach.Reading(20f, 0f)))
    }

    @Test
    fun `a speed hovering under the threshold never raises the line`() {
        val coach = ScanCoach()
        for (i in 0..40) {
            val speed = if (i % 2 == 0) 45f else 35f
            assertNull(coach.update(ms(50L * i), ScanCoach.Reading(speed, 0.3f)))
        }
        val flick = ScanCoach()
        flick.update(0L, ScanCoach.Reading(80f, 0f))
        assertNull("a flick shorter than the latch", flick.update(ms(200L), ScanCoach.Reading(10f, 0f)))
        assertNull(flick.update(ms(300L), ScanCoach.Reading(10f, 0f)))
    }

    @Test
    fun `ARCore's own motion and light reasons count`() {
        val coach = ScanCoach()
        coach.update(0L, ScanCoach.Reading(0f, 0f, excessiveMotion = true))
        assertEquals(ScanAdvice.SlowDown, coach.update(ms(300L), ScanCoach.Reading(0f, 0f, excessiveMotion = true)))

        val dark = ScanCoach()
        dark.update(0L, ScanCoach.Reading(0f, 0f, luma = 0.05f))
        assertEquals(ScanAdvice.MoreLight, dark.update(ms(1_100L), ScanCoach.Reading(0f, 0f, luma = 0.05f)))
    }

    @Test
    fun `a more urgent line takes the place at once, a less urgent one waits`() {
        val coach = ScanCoach()
        val plain = ScanCoach.Reading(0f, 0f, points = 10)
        coach.update(0L, plain)
        assertEquals(ScanAdvice.MoreDetail, coach.update(ms(1_600L), plain))
        val fast = ScanCoach.Reading(70f, 0f, points = 10)
        coach.update(ms(1_700L), fast)
        assertEquals("urgent", ScanAdvice.SlowDown, coach.update(ms(1_960L), fast))

        val far = ScanCoach()
        far.update(0L, ScanCoach.Reading(0f, 0f, distanceM = 4f))
        assertEquals(ScanAdvice.MoveCloser, far.update(ms(1_300L), ScanCoach.Reading(0f, 0f, distanceM = 4f)))
        val close = ScanCoach.Reading(0f, 0f, distanceM = 0.3f)
        far.update(ms(1_400L), close)
        assertEquals("more urgent", ScanAdvice.StepBack, far.update(ms(2_300L), close))
        val back = ScanCoach.Reading(0f, 0f, distanceM = 4f)
        far.update(ms(2_400L), back)
        assertEquals("a less urgent one waits", ScanAdvice.StepBack, far.update(ms(3_600L), back))
        assertEquals(ScanAdvice.MoveCloser, far.update(ms(3_800L), back))
    }

    @Test
    fun `hold here shows over a thin patch, at most four seconds`() {
        val coach = ScanCoach()
        val steady = ScanCoach.Reading(5f, 0.05f, distanceM = 1.5f, points = 200, poorShare = 0.8f)
        coach.update(0L, steady)
        assertEquals(ScanAdvice.HoldHere, coach.update(ms(800L), steady))
        assertEquals(ScanAdvice.HoldHere, coach.update(ms(4_500L), steady))
        assertNull("spent", coach.update(ms(4_900L), steady))
        assertNull(coach.update(ms(6_000L), steady))

        coach.update(ms(6_100L), ScanCoach.Reading(25f, 0f))
        coach.update(ms(6_200L), steady)
        assertEquals("again once the camera moved on", ScanAdvice.HoldHere, coach.update(ms(7_000L), steady))

        val filled = ScanCoach()
        val dense = ScanCoach.Reading(5f, 0.05f, poorShare = 0.1f)
        filled.update(0L, dense)
        assertNull("nothing to hold for", filled.update(ms(2_000L), dense))
    }

    @Test
    fun `nothing measured raises nothing, a covered room says so last`() {
        val coach = ScanCoach()
        for (i in 0..50) assertNull(coach.update(ms(100L * i), ScanCoach.Reading(0f, 0f)))
        val covered = ScanCoach.Reading(0f, 0f, covered = true)
        assertEquals(ScanAdvice.Covered, coach.update(ms(5_100L), covered))
        val fast = ScanCoach.Reading(70f, 0f, covered = true)
        coach.update(ms(5_200L), fast)
        assertEquals(ScanAdvice.SlowDown, coach.update(ms(5_500L), fast))
    }

    @Test
    fun `a fresh scan forgets the previous advice and motion pause`() {
        val coach = ScanCoach()
        val fast = ScanCoach.Reading(70f, 0f, covered = true)
        coach.update(0L, fast)
        assertEquals(ScanAdvice.SlowDown, coach.update(ms(300L), fast))
        coach.reset()
        assertNull(coach.shown)
        assertFalse(coach.isTooFast)
        assertNull(coach.update(ms(400L), ScanCoach.Reading(0f, 0f)))
    }

    @Test
    fun `darkness takes priority over a covered room without ARCore failure flags`() {
        val coach = ScanCoach()
        val dark = ScanCoach.Reading(0f, 0f, luma = 0.05f, covered = true)
        assertEquals(ScanAdvice.Covered, coach.update(0L, dark))
        assertEquals(ScanAdvice.MoreLight, coach.update(ms(1_100L), dark))
    }

    @Test
    fun `light is the mean brightness of the image`() {
        val grey = YuvFrame(
            width = 16,
            height = 12,
            y = ByteBuffer.wrap(ByteArray(16 * 12) { 128.toByte() }),
            yRowStride = 16,
            u = ByteBuffer.wrap(ByteArray(8 * 6) { 128.toByte() }),
            v = ByteBuffer.wrap(ByteArray(8 * 6) { 128.toByte() }),
            uvRowStride = 8,
            uvPixelStride = 1,
        )
        assertEquals(128f / 255f, ScanLight.meanLuma(grey), 1e-4f)
        val half = YuvFrame(
            width = 16,
            height = 12,
            y = ByteBuffer.wrap(ByteArray(16 * 12) { i -> if (i / 16 < 6) 255.toByte() else 0 }),
            yRowStride = 16,
            u = ByteBuffer.wrap(ByteArray(8 * 6) { 128.toByte() }),
            v = ByteBuffer.wrap(ByteArray(8 * 6) { 128.toByte() }),
            uvRowStride = 8,
            uvPixelStride = 1,
        )
        assertEquals(0.5f, ScanLight.meanLuma(half), 1e-4f)
    }
}
