package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture's one instruction line: at most one message, never changing faster than every two
 * seconds, the sweep hint only for the first six seconds, then silence unless something is wrong.
 */
class ScanLiveInstructionTest {

    @Test
    fun `before ARCore starts the line asks to move slowly`() {
        assertEquals(ScanInstruction.Starting, ScanLiveInstruction().update(nowMs = 0, started = false))
    }

    @Test
    fun `the sweep hint shows at the start, then keep going, then tap to finish once complete`() {
        val line = ScanLiveInstruction()
        assertEquals(ScanInstruction.Sweep, line.update(nowMs = 0))
        assertEquals(ScanInstruction.Sweep, line.update(nowMs = 3_000))
        assertEquals(ScanInstruction.KeepGoing, line.update(nowMs = ScanLiveInstruction.INTRO_MS + 1))
        assertEquals(ScanInstruction.KeepGoing, line.update(nowMs = 20_000))
        assertEquals(ScanInstruction.Complete, line.update(nowMs = 30_000, complete = true))
    }

    @Test
    fun `a room looks complete once most of its floor is covered after twenty seconds`() {
        assertFalse(ScanCompletion.complete(ScanScope.Room, seconds = 10f, floorShare = 1f))
        assertFalse(ScanCompletion.complete(ScanScope.Room, seconds = 40f, floorShare = 0.5f))
        assertTrue(ScanCompletion.complete(ScanScope.Room, seconds = 40f, floorShare = 0.9f))
        assertTrue(ScanCompletion.complete(ScanScope.Object, seconds = 31f, floorShare = 0f))
    }

    @Test
    fun `a problem replaces the hint, but never sooner than the hold`() {
        val line = ScanLiveInstruction()
        line.update(nowMs = 0)
        // Too fast half a second in: the sweep hint is still held.
        assertEquals(ScanInstruction.Sweep, line.update(nowMs = 500, tooFast = true))
        assertEquals(ScanInstruction.Slower, line.update(nowMs = ScanLiveInstruction.HOLD_MS, tooFast = true))
    }

    @Test
    fun `losing tracking asks to go back, and outranks speed and light`() {
        val line = ScanLiveInstruction()
        line.update(nowMs = 0)
        val now = ScanLiveInstruction.INTRO_MS + 1
        assertEquals(
            ScanInstruction.GoBack,
            line.update(nowMs = now, trackingLost = true, tooFast = true, tooDark = true),
        )
        assertEquals(
            ScanInstruction.GoBack,
            ScanLiveInstruction().apply { update(nowMs = 0) }.update(nowMs = now, poseSlipped = true),
        )
    }

    @Test
    fun `too dark asks for light when nothing else is wrong`() {
        val line = ScanLiveInstruction()
        line.update(nowMs = 0)
        assertEquals(ScanInstruction.MoreLight, line.update(nowMs = ScanLiveInstruction.INTRO_MS + 1, tooDark = true))
    }

    @Test
    fun `saving outranks every coaching line`() {
        val line = ScanLiveInstruction()
        line.update(nowMs = 0)
        assertEquals(
            ScanInstruction.Saving,
            line.update(nowMs = ScanLiveInstruction.HOLD_MS, saving = true, trackingLost = true, tooFast = true),
        )
    }

    @Test
    fun `a normal slow walk is not too fast`() {
        // Below the raised threshold (1.5 m/s), the ~1 m/s of a scan walk raises no warning.
        assertTrue(ScanCoach.MAX_SPEED_M_PER_S > 1f)
    }
}
