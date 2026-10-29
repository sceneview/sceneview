package io.github.sceneview.demo.demos.internal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Pins the 3D Scan capture lifecycle (#4627): Pause and Finish freeze a capture, the review
 * Finish opens goes back to the same capture, and nothing returns once Save scan has built it.
 */
class ScanLifecycleTest {
    // The copy under test is the English one, whatever the machine running it says.
    private val locale = Locale.getDefault()

    @Before
    fun englishLocale() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(locale)
    }


    // ── Phase ─────────────────────────────────────────────────────────────────

    @Test
    fun `only scanning integrates new frames`() {
        assertTrue(ScanPhase.Scanning.integrates)
        for (phase in ScanPhase.entries - ScanPhase.Scanning) assertFalse(phase.name, phase.integrates)
    }

    @Test
    fun `pause and resume toggle between scanning and paused only`() {
        assertEquals(ScanPhase.Paused, ScanPhase.Scanning.togglePause())
        assertEquals(ScanPhase.Scanning, ScanPhase.Paused.togglePause())
        assertEquals(ScanPhase.Reviewing, ScanPhase.Reviewing.togglePause())
        assertEquals(ScanPhase.Saving, ScanPhase.Saving.togglePause())
    }

    @Test
    fun `finish opens a review that keep scanning leaves for the same capture`() {
        val reviewing = ScanPhase.Scanning.finish()
        assertEquals(ScanPhase.Reviewing, reviewing)
        assertEquals(ScanPhase.Reviewing, ScanPhase.Paused.finish())
        assertEquals(ScanPhase.Scanning, reviewing.keepScanning())
    }

    @Test
    fun `once saving has built the scan there is no way back to scanning`() {
        assertFalse(ScanPhase.Saving.canKeepScanning)
        assertFalse(ScanPhase.SaveFailed.canKeepScanning)
        assertEquals(ScanPhase.Saving, ScanPhase.Saving.keepScanning())
        assertEquals(ScanPhase.SaveFailed, ScanPhase.SaveFailed.keepScanning())
        assertEquals(ScanPhase.SaveFailed, ScanPhase.SaveFailed.finish())
    }

    // ── Clock ─────────────────────────────────────────────────────────────────

    @Test
    fun `the clock leaves out the time spent paused`() {
        val clock = ScanClock(startedNanos = 0L)
        assertEquals(10f, clock.elapsedSeconds(10 * S), EPS)
        clock.stop(10 * S)
        assertTrue(clock.isStopped)
        assertEquals(10f, clock.elapsedSeconds(25 * S), EPS)
        clock.resume(25 * S)
        assertFalse(clock.isStopped)
        assertEquals(15f, clock.elapsedSeconds(30 * S), EPS)
    }

    @Test
    fun `a second stop or a stray resume changes nothing`() {
        val clock = ScanClock(startedNanos = 0L)
        clock.resume(5 * S)
        clock.stop(5 * S)
        clock.stop(8 * S)
        clock.resume(9 * S)
        assertEquals(6f, clock.elapsedSeconds(10 * S), EPS)
    }

    // ── Copy ──────────────────────────────────────────────────────────────────

    @Test
    fun `the start screen speaks of 3D Scan, never of rooms or sessions`() {
        assertEquals("3D Scan", ScanCopy.LANDING_TITLE)
        assertEquals("Move around a space and watch it take shape.", ScanCopy.LANDING_BODY)
        assertEquals("Start scanning", ScanCopy.RECORD)
        assertEquals("Your scans", ScanCopy.SESSIONS_TITLE)
        val visible = listOf(
            ScanCopy.IDLE_TITLE, ScanCopy.IDLE_DETAIL, ScanCopy.LANDING_TITLE, ScanCopy.LANDING_BODY,
            ScanCopy.RECORD, ScanCopy.WATCH_SAMPLE, ScanCopy.OPEN_FILE, ScanCopy.SESSIONS_TITLE,
            ScanCopy.SESSIONS_EMPTY_TITLE, ScanCopy.SESSIONS_EMPTY,
        )
        for (line in visible) {
            assertFalse(line, line.contains("Room Scan"))
            assertFalse(line, line.contains("Record your room"))
            assertFalse(line, line.contains("session", ignoreCase = true))
        }
    }

    @Test
    fun `the status reads scanning or paused, with the clock`() {
        assertEquals("Scanning · 0:42", ScanCopy.status(ScanPhase.Scanning, "0:42"))
        assertEquals("Paused · 0:42", ScanCopy.status(ScanPhase.Paused, "0:42"))
        assertEquals("Paused · 1:05", ScanCopy.status(ScanPhase.Reviewing, "1:05"))
    }

    @Test
    fun `the counter starts at zero and never names a cap`() {
        assertEquals("0 points captured", ScanCopy.pointsCaptured(0))
        assertEquals("1 point captured", ScanCopy.pointsCaptured(1))
        assertEquals("124,300 points captured", ScanCopy.pointsCaptured(124_300))
        assertEquals("0 points captured", ScanCopy.pointsCaptured(-3))
        for (n in listOf(0, 499_999, 500_000, 2_000_000)) {
            val line = ScanCopy.pointsCaptured(n)
            assertFalse(line, line.contains("/"))
            assertFalse(line, line.contains("500K", ignoreCase = true))
        }
    }

    @Test
    fun `the review figures skip photos when there are none`() {
        assertEquals("124,300 points · 42 photos · 0:42", ScanCopy.reviewFigures(124_300, 42, "0:42"))
        assertEquals("1 point · 1 photo · 0:03", ScanCopy.reviewFigures(1, 1, "0:03"))
        assertEquals("0 points · 0:00", ScanCopy.reviewFigures(0, 0, "0:00"))
    }

    @Test
    fun `one coaching line at a time, tracking loss first`() {
        assertEquals(ScanCopy.COACH_SCANNING, ScanCopy.coaching(ScanPhase.Scanning, trackingLost = false, false))
        assertEquals(ScanCopy.TRACKING_LOST, ScanCopy.coaching(ScanPhase.Scanning, trackingLost = true, true))
        assertEquals(ScanCopy.FULL, ScanCopy.coaching(ScanPhase.Scanning, trackingLost = false, true))
        assertEquals(ScanCopy.COACH_PAUSED, ScanCopy.coaching(ScanPhase.Paused, trackingLost = true, true))
        assertNull(ScanCopy.coaching(ScanPhase.Reviewing, trackingLost = false, false))
        assertNull(ScanCopy.coaching(ScanPhase.SaveFailed, trackingLost = false, false))
        assertEquals("Scan paused. Point at an area you already scanned.", ScanCopy.TRACKING_LOST)
    }

    @Test
    fun `a failed save never claims the scan was saved`() {
        assertEquals("Couldn't save your scan", ScanCopy.SAVE_FAILED)
        assertFalse(ScanCopy.SAVE_FAILED_DETAIL.contains("Saved"))
        assertTrue(ScanCopy.SAVE_FAILED_DETAIL.contains("not saved"))
    }

    @Test
    fun `the clock runs only while scanning with tracking held`() {
        assertTrue(ScanPhase.Scanning.clockRuns(trackingLost = false))
        assertFalse(ScanPhase.Scanning.clockRuns(trackingLost = true))
        for (phase in ScanPhase.entries - ScanPhase.Scanning) {
            assertFalse(phase.name, phase.clockRuns(trackingLost = false))
            assertFalse(phase.name, phase.clockRuns(trackingLost = true))
        }
    }

    @Test
    fun `a lost track stops the clock and it runs on when tracking returns`() {
        val clock = ScanClock(startedNanos = 0L)
        // 2 s scanning, 3 s with tracking lost, 1 s scanning again: 3 s captured.
        clock.follow(ScanPhase.Scanning.clockRuns(trackingLost = false), 0L)
        clock.follow(ScanPhase.Scanning.clockRuns(trackingLost = true), 2 * S)
        assertTrue(clock.isStopped)
        assertEquals(2f, clock.elapsedSeconds(4 * S), EPS)
        clock.follow(ScanPhase.Scanning.clockRuns(trackingLost = true), 4 * S) // still lost: no-op
        clock.follow(ScanPhase.Scanning.clockRuns(trackingLost = false), 5 * S)
        assertFalse(clock.isStopped)
        assertEquals(3f, clock.elapsedSeconds(6 * S), EPS)
    }

    @Test
    fun `tracking back while paused leaves the clock stopped`() {
        val clock = ScanClock(startedNanos = 0L)
        clock.follow(ScanPhase.Scanning.clockRuns(trackingLost = true), 1 * S)
        clock.follow(ScanPhase.Paused.clockRuns(trackingLost = false), 3 * S)
        assertTrue(clock.isStopped)
        assertEquals(1f, clock.elapsedSeconds(10 * S), EPS)
    }

    @Test
    fun `Back pauses a capture and its Keep scanning resumes it from anywhere still open`() {
        // The Back dialog pauses a scanning capture; Keep scanning then scans again, whether the
        // dialog was opened from Scanning, Paused or the review.
        for (opened in listOf(ScanPhase.Scanning, ScanPhase.Paused, ScanPhase.Reviewing)) {
            val behind = opened.leaveAsked()
            assertFalse(behind.name, behind.integrates)
            assertFalse(behind.name, behind.clockRuns(trackingLost = false))
            assertEquals(opened.name, ScanPhase.Scanning, behind.keepScanning())
        }
        assertEquals(ScanPhase.Paused, ScanPhase.Scanning.leaveAsked())
        assertEquals(ScanPhase.SaveFailed, ScanPhase.SaveFailed.leaveAsked())
        assertEquals(ScanPhase.SaveFailed, ScanPhase.SaveFailed.keepScanning())
    }

    @Test
    fun `Back while a scan is being built leaves and never drops it`() {
        // #4500: Save scan was tapped, so the scan is kept whatever Back does — a room of a
        // whole home included, between rooms or not.
        assertEquals(ScanBack.LeaveWhileSaving, ScanBack.of(recording = true, betweenRooms = false, ScanPhase.Saving))
        assertEquals(ScanBack.LeaveWhileSaving, ScanBack.of(recording = true, betweenRooms = true, ScanPhase.Saving))
    }

    @Test
    fun `Back asks first for a capture not built yet, and leaves when there is nothing to lose`() {
        for (phase in listOf(ScanPhase.Scanning, ScanPhase.Paused, ScanPhase.Reviewing, ScanPhase.SaveFailed)) {
            assertEquals(phase.name, ScanBack.AskFirst, ScanBack.of(recording = true, betweenRooms = false, phase))
        }
        assertEquals(ScanBack.Leave, ScanBack.of(recording = false, betweenRooms = false, ScanPhase.Scanning))
        assertEquals(ScanBack.Leave, ScanBack.of(recording = true, betweenRooms = true, ScanPhase.Paused))
        assertEquals(ScanBack.AskFirst, ScanBack.of(recording = true, betweenRooms = true, ScanPhase.Scanning))
    }

    @Test
    fun `discarding an unsaved scan asks first, with no claim it was saved`() {
        assertEquals("Discard this scan?", ScanCopy.DISCARD_TITLE)
        assertTrue(ScanCopy.DISCARD_DETAIL.contains("never saved"))
        assertFalse(ScanCopy.LEAVE_EMPTY_DETAIL.contains("Save"))
    }

    private companion object {
        const val S = 1_000_000_000L
        const val EPS = 1e-4f
    }
}
