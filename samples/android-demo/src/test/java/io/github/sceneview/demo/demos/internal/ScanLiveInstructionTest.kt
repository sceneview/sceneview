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
    }

    @Test
    fun `a room looks complete once the phone has been all the way round`() {
        // A real 75 s room scan (2026-11-14) never covered 85 % of its floor's hull: nothing ever
        // said it was finished. Its turn was full at 37 s.
        assertFalse(ScanCompletion.complete(ScanScope.Room, seconds = 40f, floorShare = 0.5f, turnShare = 11f / 12f))
        assertTrue(ScanCompletion.complete(ScanScope.Room, seconds = 40f, floorShare = 0.5f, turnShare = 1f))
        assertTrue(ScanCompletion.complete(ScanScope.Home, seconds = 40f, floorShare = 0.5f, turnShare = 1f))
        // A spin on the spot in ten seconds is not a scan yet.
        assertFalse(ScanCompletion.complete(ScanScope.Room, seconds = 10f, floorShare = 0.5f, turnShare = 1f))
    }

    @Test
    fun `an object looks complete once it was walked round, never by the clock alone`() {
        assertFalse(ScanCompletion.complete(ScanScope.Object, seconds = 31f, floorShare = 0f))
        assertFalse(ScanCompletion.complete(ScanScope.Object, seconds = 5f, floorShare = 0f, turnShare = 1f))
        assertTrue(ScanCompletion.complete(ScanScope.Object, seconds = 19f, floorShare = 0f, turnShare = 1f))
    }

    @Test
    fun `an outdoor area never looks complete by itself, it asks`() {
        assertFalse(ScanCompletion.complete(ScanScope.Area, seconds = 200f, floorShare = 1f, turnShare = 1f))
        assertFalse(ScanCompletion.whenReady(ScanScope.Area, seconds = 29f))
        assertTrue(ScanCompletion.whenReady(ScanScope.Area, seconds = 30f))
        // An object against a wall cannot be walked round: it asks too. A room waits for its turn.
        assertTrue(ScanCompletion.whenReady(ScanScope.Object, seconds = 30f))
        assertFalse(ScanCompletion.whenReady(ScanScope.Room, seconds = 300f))
        assertFalse(ScanCompletion.whenReady(ScanScope.Home, seconds = 300f))
    }

    @Test
    fun `the circle is the turn made, full once complete, and absent outdoors`() {
        assertEquals(0.5f, ScanCompletion.progress(ScanScope.Room, turnShare = 0.5f)!!, 0f)
        assertEquals(0.25f, ScanCompletion.progress(ScanScope.Object, turnShare = 0.25f)!!, 0f)
        // A room complete by its floor shows a full circle: the line and the circle say the same.
        assertEquals(1f, ScanCompletion.progress(ScanScope.Room, turnShare = 0.5f, complete = true)!!, 0f)
        assertEquals(null, ScanCompletion.progress(ScanScope.Area, turnShare = 1f))
    }

    @Test
    fun `a space has no turn to make, never completes by itself, and asks sooner`() {
        // Filmed from its opening: whatever the phone turned, there is no circle to fill.
        assertEquals(null, ScanCompletion.progress(ScanScope.Space, turnShare = 1f))
        assertFalse(ScanCompletion.complete(ScanScope.Space, seconds = 120f, floorShare = 1f, turnShare = 1f))
        assertFalse(ScanCompletion.whenReady(ScanScope.Space, seconds = 14f))
        assertTrue(ScanCompletion.whenReady(ScanScope.Space, seconds = 15f))
        // An object is still given its half minute.
        assertFalse(ScanCompletion.whenReady(ScanScope.Object, seconds = 15f))
    }

    @Test
    fun `the red button finishes nothing in the first three seconds`() {
        // Two scans of a terrace were saved after 0.8 s and 6.5 s (2026-11-14), one photo in the first.
        assertFalse(ScanCompletion.canFinish(0.8f))
        assertFalse(ScanCompletion.canFinish(2.9f))
        assertTrue(ScanCompletion.canFinish(3f))
        // No clock (a QA state): nothing to wait for.
        assertTrue(ScanCompletion.canFinish(null))
    }

    @Test
    fun `a scan with no turn to wait for asks whether it has everything`() {
        val line = ScanLiveInstruction()
        line.update(nowMs = 0)
        assertEquals(ScanInstruction.KeepGoing, line.update(nowMs = 10_000))
        assertEquals(ScanInstruction.WhenReady, line.update(nowMs = 30_000, whenReady = true))
        // A problem still takes the line, and a full turn outranks the question.
        assertEquals(ScanInstruction.Slower, line.update(nowMs = 33_000, whenReady = true, tooFast = true))
        assertEquals(ScanInstruction.Complete, line.update(nowMs = 36_000, whenReady = true, complete = true))
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
    fun `the walls are asked for only once the phone has stayed off them`() {
        val line = ScanLiveInstruction()
        line.update(nowMs = 0)
        val t = ScanLiveInstruction.INTRO_MS + 1
        // A sweep down to the floor and up again: the line does not move.
        assertEquals(ScanInstruction.KeepGoing, line.update(nowMs = t, floorOnly = true))
        assertEquals(ScanInstruction.KeepGoing, line.update(nowMs = t + 3_000, floorOnly = true))
        assertEquals(ScanInstruction.KeepGoing, line.update(nowMs = t + 4_000))
        assertEquals(ScanInstruction.KeepGoing, line.update(nowMs = t + 6_000, floorOnly = true))
        // Five seconds on the floor: now it asks.
        val asked = t + 6_000 + ScanLiveInstruction.OFF_WALLS_MS
        assertEquals(ScanInstruction.Walls, line.update(nowMs = asked, floorOnly = true))
        // Back on the walls, the line goes with its usual hold.
        assertEquals(ScanInstruction.Walls, line.update(nowMs = asked + 500))
        assertEquals(ScanInstruction.KeepGoing, line.update(nowMs = asked + ScanLiveInstruction.HOLD_MS))
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
