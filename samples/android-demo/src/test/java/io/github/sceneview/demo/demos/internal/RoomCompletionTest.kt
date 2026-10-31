package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RoomCompletion]: a room reads complete once, and stays so, when its walls close it over a floor
 * covered — or covered as far as the room goes, the floor map's reference being a convex hull that
 * an L-shaped room never fills. And what the chrome says and plays around it.
 */
class RoomCompletionTest {

    @Test
    fun `an L-shaped room whose floor tops out under the threshold completes once the floor stops growing`() {
        // A 6 x 6 m room short of a 4 x 4 m corner: 20 m² of floor, a convex hull of 28 m².
        val lShare = (6f * 6f - 4f * 4f) / (6f * 6f - 4f * 4f / 2f)
        assertEquals(0.714f, lShare, 0.001f)

        val room = RoomCompletion()
        var t = 0L
        listOf(0.2f, 0.4f, 0.6f, 0.7f, lShare).forEach { share ->
            assertNull(room.update(wallsDone = true, floorShare = share, scanning = true, nowMs = t))
            t += 2_000L
        }
        // The floor no longer grows: short of the stall, still not complete...
        assertNull(room.update(true, lShare, scanning = true, nowMs = t + CaptureChrome.FLOOR_STALL_MS - 2_001L))
        // ...past it, complete as far as the floor goes.
        assertEquals(RoomDone.Plateau, room.update(true, lShare, scanning = true, nowMs = t + CaptureChrome.FLOOR_STALL_MS))
        assertEquals(CaptureChrome.FLOOR_MOSTLY, CaptureChrome.roomLine(ScanCopy.ROOM_DONE, RoomDone.Plateau))
    }

    @Test
    fun `a floor stalled far from covered, or a stall while not scanning, does not complete the room`() {
        val low = RoomCompletion()
        low.update(true, 0.3f, scanning = true, nowMs = 0L)
        assertNull(low.update(true, 0.3f, scanning = true, nowMs = 60_000L))

        val paused = RoomCompletion()
        paused.update(true, 0.7f, scanning = true, nowMs = 0L)
        assertNull(paused.update(true, 0.7f, scanning = false, nowMs = 60_000L))
        // Scanning again: the stall counts from there.
        assertNull(paused.update(true, 0.7f, scanning = true, nowMs = 61_000L))
        assertEquals(RoomDone.Plateau, paused.update(true, 0.7f, scanning = true, nowMs = 60_000L + CaptureChrome.FLOOR_STALL_MS))
    }

    @Test
    fun `complete is held once reached, so a floor share wobbling round the threshold does not flip it`() {
        val room = RoomCompletion()
        assertNull(room.update(true, 0.79f, scanning = true, nowMs = 0L))
        assertEquals(RoomDone.Covered, room.update(true, 0.8f, scanning = true, nowMs = 100L))
        assertEquals(RoomDone.Covered, room.update(true, 0.78f, scanning = true, nowMs = 200L))
        assertEquals(RoomDone.Covered, room.update(false, 0.5f, scanning = true, nowMs = 300L))
        assertEquals(RoomDone.Covered, room.done)
        // No walls closed, no completion, however covered the floor.
        assertNull(RoomCompletion().update(false, 1f, scanning = true, nowMs = 0L))
    }

    @Test
    fun `the room line changes only for a room complete as far as its floor goes`() {
        assertEquals(ScanCopy.ROOM_DONE, CaptureChrome.roomLine(ScanCopy.ROOM_DONE, RoomDone.Covered))
        assertEquals(ScanCopy.ROOM_DONE, CaptureChrome.roomLine(ScanCopy.ROOM_DONE, null))
        assertEquals(ScanCopy.FLOOR_LEFT, CaptureChrome.roomLine(ScanCopy.FLOOR_LEFT, RoomDone.Plateau))
        assertNull(CaptureChrome.roomLine(null, RoomDone.Plateau))
    }

    @Test
    fun `a lost track reads as such at the top, not as a pause`() {
        assertEquals(
            "Room · Tracking lost",
            CaptureChrome.headline("Room", ScanPhase.Paused, ScanActivity.Paused, roomShare = 0.4f, trackingLost = true),
        )
        assertEquals("Room · Paused", CaptureChrome.headline("Room", ScanPhase.Paused, ScanActivity.Paused, roomShare = 0.4f))
        // Past the capture, what happens wins.
        assertEquals("Room · In review", CaptureChrome.headline("Room", ScanPhase.Reviewing, ScanActivity.Paused, trackingLost = true))
    }

    @Test
    fun `every haptic waits a second after the last, the room's completion included, and none plays in QA mode`() {
        val clear = CaptureChromeState(null, complete = false)
        val blocked = CaptureChromeState(ScanBlocker.TooFast, complete = false)
        val done = CaptureChromeState(null, complete = true)
        val gap = CaptureChrome.HAPTIC_GAP_MS

        assertEquals(ScanHaptic.Blocked, CaptureChrome.hapticToPlay(clear, blocked, nowMs = 5_000L, playedAtMs = 0L, qaMode = false))
        assertNull(CaptureChrome.hapticToPlay(blocked, done, nowMs = 5_000L + gap - 1, playedAtMs = 5_000L, qaMode = false))
        assertEquals(ScanHaptic.Cleared, CaptureChrome.hapticToPlay(blocked, done, nowMs = 5_000L + gap, playedAtMs = 5_000L, qaMode = false))
        assertEquals(
            ScanHaptic.Complete,
            CaptureChrome.hapticToPlay(clear, done, nowMs = 5_000L + gap, playedAtMs = 5_000L, qaMode = false),
        )
        assertNull(CaptureChrome.hapticToPlay(clear, done, nowMs = 5_000L + gap - 1, playedAtMs = 5_000L, qaMode = false))
        assertNull(CaptureChrome.hapticToPlay(clear, blocked, nowMs = 60_000L, playedAtMs = 0L, qaMode = true))
        assertNull(CaptureChrome.hapticToPlay(clear, clear, nowMs = 60_000L, playedAtMs = 0L, qaMode = false))
    }

    @Test
    fun `a haptic due within the gap waits out the rest of it`() {
        val gap = CaptureChrome.HAPTIC_GAP_MS
        assertEquals(gap - 300L, CaptureChrome.hapticWaitMs(nowMs = 5_300L, playedAtMs = 5_000L))
        assertEquals(0L, CaptureChrome.hapticWaitMs(nowMs = 5_000L + gap, playedAtMs = 5_000L))
        assertEquals(0L, CaptureChrome.hapticWaitMs(nowMs = 60_000L, playedAtMs = Long.MIN_VALUE / 2))
    }
}
