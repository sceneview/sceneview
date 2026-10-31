package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture's chrome says one thing at a time, and the same thing everywhere: a room is complete
 * only once its walls close it *and* its floor is covered, so the coaching line never says
 * "Room complete" over a floor the map still hatches; a cause that stops the scan is a banner with
 * its way out, never a "Next" line; the primary action names the step it leads to.
 */
class CaptureChromeTest {

    @Test
    fun `walls closed over a floor still hatched is not a complete room`() {
        assertFalse(CaptureChrome.complete(wallsDone = true, floorShare = 0.3f))
        assertFalse(CaptureChrome.complete(wallsDone = false, floorShare = 1f))
        assertTrue(CaptureChrome.complete(wallsDone = true, floorShare = CaptureChrome.FLOOR_DONE_SHARE))
    }

    @Test
    fun `the coach never says room complete while the floor is left to scan`() {
        for (line in listOf(ScanCopy.COACH_SCANNING, ScanCopy.COACH_STILL, ScanCopy.COACH_NOTHING_NEW)) {
            val said = ScanScopeCopy.coaching(ScanScope.Room, line, walls = 4, done = true, floorLeft = true)
            assertNotEquals(line, ScanCopy.ROOM_DONE, said)
            assertEquals(line, ScanCopy.FLOOR_LEFT, said)
        }
        assertEquals(
            ScanCopy.ROOM_DONE,
            ScanScopeCopy.coaching(ScanScope.Room, ScanCopy.COACH_SCANNING, walls = 4, done = true, floorLeft = false),
        )
    }

    @Test
    fun `the floor line says what is left and where to look for it`() {
        assertTrue(ScanCopy.FLOOR_LEFT.contains("hatched", ignoreCase = true))
        assertFalse(ScanCopy.FLOOR_LEFT.contains("complete", ignoreCase = true))
    }

    @Test
    fun `room complete names the stop button by its visible label`() {
        assertTrue(ScanCopy.ROOM_DONE.contains(CaptureChrome.STOP_AND_REVIEW, ignoreCase = true))
    }

    @Test
    fun `the gauge is full only on a complete room`() {
        assertEquals(1f, CaptureChrome.share(completion = 1f, wallsDone = true, floorShare = 0.9f), 0f)
        val wallsOnly = CaptureChrome.share(completion = 1f, wallsDone = true, floorShare = 0.3f)
        assertTrue("walls closed, floor at 30 %: $wallsOnly", wallsOnly < 1f)
        assertTrue(wallsOnly >= ScanGuide.WALLS_SHARE)
        // More floor, more gauge, short of full until the floor is covered.
        assertTrue(CaptureChrome.share(1f, true, 0.6f) > wallsOnly)
        assertTrue(CaptureChrome.share(1f, true, 0.79f) < 1f)
        assertTrue(CaptureChrome.share(completion = 0.97f, wallsDone = false, floorShare = 1f) < 1f)
        assertEquals(0.4f, CaptureChrome.share(completion = 0.4f, wallsDone = false, floorShare = 0.2f), 1e-6f)
    }

    @Test
    fun `what stops the scan is a blocker, with its cause and the way out`() {
        assertEquals(CaptureCue.Blocked(ScanBlocker.TrackingLost), CaptureChrome.cue(ScanCopy.TRACKING_LOST))
        assertEquals(CaptureCue.Blocked(ScanBlocker.TooDark), CaptureChrome.cue(ScanCopy.TRACKING_LOST, dark = true))
        assertEquals(CaptureCue.Blocked(ScanBlocker.TooFast), CaptureChrome.cue(ScanCopy.COACH_SLOWER))
        assertEquals(CaptureCue.Blocked(ScanBlocker.LowTexture), CaptureChrome.cue(ScanCopy.COACH_LOW_TEXTURE))
        assertEquals(CaptureCue.Blocked(ScanBlocker.NoDepth), CaptureChrome.cue(ScanCopy.COACH_NO_DEPTH))
        assertEquals(CaptureCue.Blocked(ScanBlocker.DepthLost), CaptureChrome.cue(ScanCopy.COACH_DEPTH_LOST))
        assertEquals(CaptureCue.Blocked(ScanBlocker.Full), CaptureChrome.cue(ScanCopy.SCAN_FULL))
        for (blocker in ScanBlocker.entries) {
            assertTrue(blocker.name, blocker.cause.isNotBlank() && blocker.wayOut.isNotBlank())
            assertNotEquals(blocker.name, blocker.cause, blocker.wayOut)
        }
    }

    @Test
    fun `a full scan's way out is the stop button, by its label`() {
        assertTrue(ScanBlocker.Full.wayOut.contains(CaptureChrome.STOP_AND_REVIEW, ignoreCase = true))
    }

    @Test
    fun `everything else is the next step`() {
        assertEquals(CaptureCue.Next(ScanCopy.ROOM_DONE), CaptureChrome.cue(ScanCopy.ROOM_DONE))
        assertEquals(CaptureCue.Next(ScanCopy.FLOOR_LEFT), CaptureChrome.cue(ScanCopy.FLOOR_LEFT))
        val walk = ScanScopeCopy.scanning(ScanScope.Area)
        assertEquals(CaptureCue.Next(walk), CaptureChrome.cue(walk))
        assertEquals(CaptureCue.Next(CaptureChrome.PAUSED_NEXT), CaptureChrome.cue(ScanCopy.COACH_PAUSED))
        assertNull(CaptureChrome.cue(null))
        assertEquals("Next: $walk", CaptureChrome.nextLine(walk))
    }

    @Test
    fun `the steps follow the capture, scan then review then model`() {
        assertEquals(listOf("Scan", "Review", "Model"), ScanStep.entries.map { it.label })
        assertEquals(ScanStep.Scan, ScanStep.of(ScanPhase.Scanning))
        assertEquals(ScanStep.Scan, ScanStep.of(ScanPhase.Paused))
        assertEquals(ScanStep.Review, ScanStep.of(ScanPhase.Reviewing))
        assertEquals(ScanStep.Model, ScanStep.of(ScanPhase.Saving))
        assertEquals(ScanStep.Model, ScanStep.of(ScanPhase.SaveFailed))
        assertEquals("Step 2 of 3: Review", CaptureChrome.stepDescription(ScanStep.Review))
    }

    @Test
    fun `the primary actions name the step they lead to`() {
        assertEquals("Stop and review", CaptureChrome.STOP_AND_REVIEW)
        assertFalse(CaptureChrome.STOP_AND_REVIEW.contains("save", ignoreCase = true))
        assertTrue(CaptureChrome.BUILD_MODEL.contains("model", ignoreCase = true))
        assertTrue(CaptureChrome.REVIEW_DETAIL.contains("model", ignoreCase = true))
    }

    @Test
    fun `the top line is the useful state, not the word scanning`() {
        val working = ScanActivity.Working
        assertEquals("Room · 72 % scanned", CaptureChrome.headline("Room", ScanPhase.Scanning, working, roomShare = 0.72f))
        assertEquals("Area · 12 m² covered", CaptureChrome.headline("Area", ScanPhase.Scanning, working, covered = 12.2f))
        assertEquals("Area · Nothing covered yet", CaptureChrome.headline("Area", ScanPhase.Scanning, working, covered = 0f))
        assertEquals("Object · Scanning", CaptureChrome.headline("Object", ScanPhase.Scanning, working))
        assertTrue(CaptureChrome.headline("Room", ScanPhase.Scanning, ScanActivity.Stuck, 0.5f).contains(ScanCopy.STUCK))
        assertTrue(CaptureChrome.headline("Room", ScanPhase.Paused, ScanActivity.Paused, 0.5f).contains(ScanCopy.PAUSED))
        // Past the capture the card says what is happening, never "Paused" over the build.
        assertEquals("Room · In review", CaptureChrome.headline("Room", ScanPhase.Reviewing, ScanActivity.Paused, 0.5f))
        assertEquals("Room · Building the model", CaptureChrome.headline("Room", ScanPhase.Saving, ScanActivity.Paused, 0.5f))
    }

    @Test
    fun `the second line counts walls against the room, then the clock`() {
        assertEquals("3 of 4 walls · 0:42", CaptureChrome.detail("0:42", walls = 3, points = 1000))
        assertEquals("No walls yet · 0:42", CaptureChrome.detail("0:42", walls = 0, points = 1000))
        assertEquals("5 walls · 0:42", CaptureChrome.detail("0:42", walls = 5, points = 1000))
        assertEquals("0:42 · ${ScanCopy.pointsCaptured(1234)}", CaptureChrome.detail("0:42", walls = null, points = 1234))
    }

    @Test
    fun `a haptic marks a blocker appearing, clearing, and the room completing, never every line`() {
        val scanning = CaptureChromeState(blocker = null, complete = false)
        val blocked = CaptureChromeState(blocker = ScanBlocker.TooFast, complete = false)
        val other = CaptureChromeState(blocker = ScanBlocker.TrackingLost, complete = false)
        val done = CaptureChromeState(blocker = null, complete = true)
        assertEquals(ScanHaptic.Blocked, CaptureChrome.haptic(scanning, blocked))
        assertEquals(ScanHaptic.Blocked, CaptureChrome.haptic(blocked, other))
        assertEquals(ScanHaptic.Cleared, CaptureChrome.haptic(blocked, scanning))
        assertEquals(ScanHaptic.Complete, CaptureChrome.haptic(scanning, done))
        assertNull(CaptureChrome.haptic(scanning, scanning))
        assertNull(CaptureChrome.haptic(blocked, blocked))
        assertNull(CaptureChrome.haptic(done, done))
        assertNull(CaptureChrome.haptic(done, scanning))
        assertEquals(CaptureChromeState(ScanBlocker.Full, false), CaptureChromeState.of(CaptureCue.Blocked(ScanBlocker.Full), false))
        assertEquals(CaptureChromeState(null, true), CaptureChromeState.of(CaptureCue.Next(ScanCopy.ROOM_DONE), true))
    }
}
