package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeelHapticsTest {
    @Test fun rigidMaterialsHaveOneContactAndNoTimedStopClick() {
        for ((material, scale) in listOf(FeelMaterial.Oak to 0.45f, FeelMaterial.Steel to 0.90f)) {
            var state = HapticSchedule()
            val first = scheduleHaptic(state, material, 0.12f, 0)
            assertEquals(FeelPulse(FeelPulseKind.Click, scale), first.pulse)
            state = first.schedule
            for (time in 5L..3000L step 5) {
                val step = scheduleHaptic(state, material, 1f, time)
                assertNull(step.pulse)
                state = step.schedule
            }
        }
    }

    @Test fun foamStartsOneContinuousEnvelopeAndNeverTicks() {
        var state = HapticSchedule()
        var count = 0
        for (time in 0L..4000L step 5) {
            val step = scheduleHaptic(state, FeelMaterial.Foam, 1f, time)
            state = step.schedule
            step.pulse?.let {
                count++
                assertEquals(FeelPulseKind.FoamEnvelope, it.kind)
            }
        }
        assertEquals(1, count)
    }

    @Test fun foamEnvelopeMatchesEveryAuthoredPointAndInterpolatesWithoutOvershoot() {
        for ((time, intensity) in listOf(0L to 0f, 60L to 0.08f, 300L to 0.14f,
            850L to 0.20f, 1200L to 0.12f, 1400L to 0f)) {
            assertEquals(intensity, foamIntensity(time), 0.00001f)
        }
        assertEquals(0.11f, foamIntensity(180), 0.00001f)
        assertEquals(0f, foamIntensity(-1), 0f)
        assertEquals(0f, foamIntensity(Long.MAX_VALUE), 0f)
        assertTrue((0L..2000L).all { foamIntensity(it) in 0f..0.20f })
    }

    @Test fun oakGrainNeedsSixMillimetresAndDiscardsExcessCrossings() {
        var state = scheduleHaptic(HapticSchedule(), FeelMaterial.Oak, 0f, 0).schedule
        assertNull(scheduleHaptic(state, FeelMaterial.Oak, 1f, 200, 5.9f).pulse)
        val grain = scheduleHaptic(state, FeelMaterial.Oak, 1f, 200, 6f)
        assertEquals(FeelPulseKind.LowTick, grain.pulse?.kind)
        assertEquals(0.20f, grain.pulse!!.scale, 0.00001f)
        state = grain.schedule
        val tooFast = scheduleHaptic(state, FeelMaterial.Oak, 1f, 250, 60f)
        assertNull(tooFast.pulse)
        state = tooFast.schedule
        assertNull(scheduleHaptic(state, FeelMaterial.Oak, 1f, 1000, 60f).pulse)
        assertEquals(FeelPulseKind.LowTick,
            scheduleHaptic(state, FeelMaterial.Oak, 1f, 1000, 66f).pulse?.kind)
    }

    @Test fun rapidReleaseAndMaterialSwitchCannotBypassActuatorThrottle() {
        var state = HapticSchedule()
        val emitted = mutableListOf<Long>()
        for (time in 0L..300L step 5) {
            state = scheduleHaptic(state, null, 0f, time).schedule
            val step = scheduleHaptic(state, FeelMaterial.Steel, 1f, time)
            state = step.schedule
            step.pulse?.let { emitted += time }
        }
        assertTrue(emitted.size > 1)
        assertTrue(emitted.zipWithNext().all { (a, b) -> b - a >= 30 })
    }

    @Test fun releaseSilencesTheSchedulerAndRearmsNextContact() {
        val press = scheduleHaptic(HapticSchedule(), FeelMaterial.Foam, 1f, 0)
        val release = scheduleHaptic(press.schedule, null, 0f, 100)
        assertNull(release.pulse)
        assertEquals(FeelPulseKind.FoamEnvelope,
            scheduleHaptic(release.schedule, FeelMaterial.Foam, 0.12f, 200).pulse?.kind)
    }
}
