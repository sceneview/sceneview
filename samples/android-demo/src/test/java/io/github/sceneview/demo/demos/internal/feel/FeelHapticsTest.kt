package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeelHapticsTest {
    @Test fun steelClicksImmediatelyAndOnlyOnceUntilRelease() {
        var state = HapticSchedule()
        var clicks = 0
        for (time in 0L..2000L step 5) {
            val step = scheduleHaptic(state, FeelMaterial.Steel, Indentation(), time / 2000f, time)
            state = step.schedule
            if (step.pulse != null) {
                clicks++
                assertEquals(0L, time)
                assertEquals(FeelPulseKind.Click, step.pulse.kind)
            }
        }
        assertEquals(1, clicks)
        state = scheduleHaptic(state, null, Indentation(), 0f, 2100).schedule
        assertEquals(FeelPulseKind.Click, scheduleHaptic(state, FeelMaterial.Steel, Indentation(), 0.1f, 2200).pulse?.kind)
    }

    @Test fun oakBuildsResistanceThenClicksOnceAtTheStop() {
        var state = HapticSchedule()
        val first = scheduleHaptic(state, FeelMaterial.Oak, Indentation(0.1f, 1f), 0.5f, 0)
        assertEquals(FeelPulseKind.Tick, first.pulse?.kind)
        state = first.schedule
        val stop = scheduleHaptic(state, FeelMaterial.Oak, Indentation(0.35f), 1f, 40)
        assertEquals(FeelPulseKind.Click, stop.pulse?.kind)
        state = stop.schedule
        for (time in 80L..2000L step 40) {
            val step = scheduleHaptic(state, FeelMaterial.Oak, Indentation(0.35f), 1f, time)
            assertNull(step.pulse)
            state = step.schedule
        }
        assertNull(scheduleHaptic(state, FeelMaterial.Oak, Indentation(0.1f), 0.2f, 2100).pulse)
    }

    @Test fun foamTicksContinueAndSpeedIncreasesRateAndScale() {
        fun pulses(velocity: Float): List<Pair<Long, FeelPulse>> {
            var state = HapticSchedule()
            val result = mutableListOf<Pair<Long, FeelPulse>>()
            for (time in 0L..2000L step 5) {
                val step = scheduleHaptic(state, FeelMaterial.Foam, Indentation(10f, velocity), 0.5f, time)
                state = step.schedule
                step.pulse?.let { result += time to it }
            }
            return result
        }
        val slow = pulses(0f)
        val fast = pulses(24f)
        assertTrue(slow.size > 10)
        assertTrue(fast.size > slow.size * 2)
        assertTrue(fast.first().second.scale > slow.first().second.scale)
        assertTrue(fast.all { it.second.kind == FeelPulseKind.LowTick && it.second.scale in 0f..1f })
        assertTrue(fast.zipWithNext().all { (a, b) -> b.first - a.first >= 30 })
    }

    @Test fun rapidReleaseAndMaterialSwitchCannotBypassActuatorThrottle() {
        var state = HapticSchedule()
        val emitted = mutableListOf<Long>()
        for (time in 0L..300L step 5) {
            state = scheduleHaptic(state, null, Indentation(), 0f, time).schedule
            val step = scheduleHaptic(state, FeelMaterial.Steel, Indentation(), 1f, time)
            state = step.schedule
            step.pulse?.let { emitted += time }
        }
        assertTrue(emitted.size > 1)
        assertTrue(emitted.zipWithNext().all { (a, b) -> b - a >= 30 })
    }

    @Test fun releaseSilencesTheScheduler() {
        val press = scheduleHaptic(HapticSchedule(), FeelMaterial.Foam, Indentation(10f, 5f), 1f, 0)
        val release = scheduleHaptic(press.schedule, null, Indentation(10f, -5f), 0f, 100)
        assertNull(release.pulse)
    }
}
