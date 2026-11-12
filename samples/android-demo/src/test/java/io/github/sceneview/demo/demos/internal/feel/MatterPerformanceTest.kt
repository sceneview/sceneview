package io.github.sceneview.demo.demos.internal.feel

import org.junit.Assert.*
import org.junit.Test

class MatterPerformanceTest {
    @Test fun frameWindowsIncludeMissedFramesAndResetAcrossAPark() {
        val times = MatterFrameTimes()
        assertNull(times.frame(0L))
        for (frame in 1..19) assertNull(times.frame(frame * 50_000_000L))
        val slow = times.frame(1_000_000_000L)!!
        assertEquals(50.0, slow.averageMillis, .0001)
        assertEquals(20.0, slow.fps, .0001)
        assertEquals(20, slow.intervals)
        times.reset()
        assertNull(times.frame(90_000_000_000L))
        for (frame in 1..59) assertNull(times.frame(90_000_000_000L + frame * 16_666_667L))
        val fast = times.frame(91_000_000_020L)!!
        assertEquals(60.0, fast.fps, .001)
        assertEquals(16.666667, fast.averageMillis, .0001)
    }

    @Test fun stationarySampleCanParkButPressRecoveryAndWavesCannot() {
        val simulation = FeelSimulation()
        assertFalse(simulation.needsFrames(FeelMaterial.MemoryFoam, 0L, true))
        simulation.begin(FeelMaterial.Gel, 0f, 0f, 1f, .1f, 0L)
        for (now in 0L..500L step 16) simulation.advance(now)
        assertTrue(simulation.needsFrames(FeelMaterial.Gel, 500L, true))
        simulation.release(500L)
        assertTrue(simulation.needsFrames(FeelMaterial.Gel, 501L, true))
        for (now in 516L..10000L step 16) simulation.advance(now)
        assertFalse(simulation.needsFrames(FeelMaterial.Gel, 10000L, true))
    }

    @Test fun rigidRippleKeepsFramesUntilItExpiresAndQaCanParkImmediately() {
        val simulation = FeelSimulation()
        simulation.begin(FeelMaterial.Marble, 0f, 0f, 1f, .1f, 100L)
        simulation.release(101L)
        assertTrue(simulation.needsFrames(FeelMaterial.Marble, 200L, true))
        assertFalse(simulation.needsFrames(FeelMaterial.Marble, 200L, false))
        assertFalse(simulation.needsFrames(FeelMaterial.Marble, 450L, true))
    }

    @Test fun orbitHasOneWakeDeadlineAndHoldingATouchCancelsIt() {
        val idle = MatterIdle()
        idle.advance(100L, true)
        assertEquals(4101L, idle.wakeAt)
        assertFalse(idle.advance(4100L, true))
        assertTrue(idle.advance(4200L, true))
        val frozen = idle.yawDegrees
        idle.touch(4300L, true)
        assertNull(idle.wakeAt)
        assertFalse(idle.advance(9000L, true))
        assertEquals(frozen, idle.yawDegrees, 0f)
        idle.touch(9100L, false)
        assertEquals(13101L, idle.wakeAt)
    }
}
