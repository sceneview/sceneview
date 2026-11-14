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
    fun `an empty area is named on its side, over the walls line, under complete`() {
        val line = ScanLiveInstruction()
        line.update(nowMs = 0)
        val after = ScanLiveInstruction.INTRO_MS + 1
        assertEquals(ScanInstruction.HoleLeft, line.update(nowMs = after, floorOnly = true, hole = ScanTurn.Left))
        assertEquals(
            ScanInstruction.HoleBehind,
            line.update(nowMs = after + ScanLiveInstruction.HOLD_MS, hole = ScanTurn.Behind),
        )
        assertEquals(
            ScanInstruction.Complete,
            line.update(nowMs = after + 2 * ScanLiveInstruction.HOLD_MS, complete = true, hole = ScanTurn.Right),
        )
    }

    @Test
    fun `a line naming an area follows another area at once`() {
        val line = ScanLiveInstruction()
        line.update(nowMs = 0)
        val after = ScanLiveInstruction.INTRO_MS + 1
        assertEquals(ScanInstruction.HoleLeft, line.update(nowMs = after, hole = ScanTurn.Left, holeId = 1))
        assertEquals(1, line.namedHole)
        // The same area on another side waits for the hold.
        assertEquals(ScanInstruction.HoleLeft, line.update(nowMs = after + 100, hole = ScanTurn.Behind, holeId = 1))
        // Another area does not.
        assertEquals(ScanInstruction.HoleRight, line.update(nowMs = after + 200, hole = ScanTurn.Right, holeId = 2))
        assertEquals(2, line.namedHole)
        // Nor does no area at all.
        assertEquals(ScanInstruction.KeepGoing, line.update(nowMs = after + 300))
        assertEquals(null, line.namedHole)
    }

    @Test
    fun `a pose that stays slipped stops asking to go back and says the ways out`() {
        // Codex's review of u30: « Go back to where you were » repeated for ever, and going back
        // slowly cannot undo a slipped pose.
        val line = ScanLiveInstruction()
        line.update(nowMs = 0)
        val t = ScanLiveInstruction.HOLD_MS
        assertEquals(ScanInstruction.GoBack, line.update(nowMs = t, trackingLost = true, poseSlipped = true))
        assertEquals(
            ScanInstruction.Stuck,
            line.update(nowMs = 2 * t, trackingLost = true, poseSlipped = true, poseStuck = true),
        )
        // The pose came back after all: the scan goes on.
        assertEquals(ScanInstruction.KeepGoing, line.update(nowMs = ScanLiveInstruction.INTRO_MS + 3 * t))
    }

    @Test
    fun `a normal slow walk is not too fast`() {
        // Below the raised threshold (1.5 m/s), the ~1 m/s of a scan walk raises no warning.
        assertTrue(ScanCoach.MAX_SPEED_M_PER_S > 1f)
    }
}
