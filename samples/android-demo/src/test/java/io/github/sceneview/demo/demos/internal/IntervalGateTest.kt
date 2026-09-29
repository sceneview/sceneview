package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder's sampling gate (#4095). ARCore frame timestamps are nanoseconds since boot:
 * ~1e13 an hour or two after a reboot, which is what a real Pixel reports.
 */
class IntervalGateTest {

    private val interval = 200_000_000L // the recorder's 5 Hz points
    private val bootNanos = 12_345_678_901_234L // ~3.4 h of uptime

    @Test
    fun `the first sample is due at a real device timestamp`() {
        assertTrue(IntervalGate(interval).isDue(bootNanos))
    }

    @Test
    fun `the first sample is due at time zero`() {
        assertTrue(IntervalGate(interval).isDue(0L))
    }

    @Test
    fun `the old Long MIN_VALUE sentinel never let the first sample through`() {
        // What ArDebugRecorder computed before #4095: the subtraction overflows negative.
        val last = Long.MIN_VALUE
        assertFalse(bootNanos - last >= interval)
        assertFalse(0L - last >= interval)
        // The gate, from the same "nothing yet" state, opens.
        assertTrue(IntervalGate.isDue(bootNanos, null, interval))
    }

    @Test
    fun `a gate samples at its interval over a session`() {
        val gate = IntervalGate(interval)
        var taken = 0
        // Two seconds of 30 fps camera frames.
        for (frame in 0 until 60) {
            val now = bootNanos + frame * 33_333_333L
            if (gate.isDue(now)) {
                gate.mark(now)
                taken++
            }
        }
        assertEquals(9, taken)
    }

    @Test
    fun `a sample inside the interval is not due`() {
        val gate = IntervalGate(interval)
        gate.mark(bootNanos)
        assertFalse(gate.isDue(bootNanos + interval - 1))
        assertTrue(gate.isDue(bootNanos + interval))
    }

    @Test
    fun `reset makes the next sample due`() {
        val gate = IntervalGate(interval)
        gate.mark(bootNanos)
        gate.reset()
        assertTrue(gate.isDue(bootNanos + 1))
    }

    @Test
    fun `a clock that went back opens the gate`() {
        val gate = IntervalGate(interval)
        gate.mark(bootNanos)
        assertTrue(gate.isDue(bootNanos - 1_000_000_000L))
    }
}
