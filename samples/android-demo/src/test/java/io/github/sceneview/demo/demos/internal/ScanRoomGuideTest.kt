package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Live Room's guide: one short message by priority, and the chevron towards the largest gap. */
class ScanRoomGuideTest {

    @Test
    fun `the first cause that holds is the one message, in the order tracking, dark, speed, distance`() {
        for (lost in listOf(false, true)) {
            for (dark in listOf(false, true)) {
                for (fast in listOf(false, true)) {
                    for (closer in listOf(false, true)) {
                        for (full in listOf(false, true)) {
                            val expected = when {
                                lost && !dark -> LiveRoomMessage.TrackingLost
                                dark -> LiveRoomMessage.TooDark
                                fast -> LiveRoomMessage.SlowDown
                                closer -> LiveRoomMessage.MoveCloser
                                full -> LiveRoomMessage.Full
                                else -> null
                            }
                            assertEquals(
                                "lost=$lost dark=$dark fast=$fast closer=$closer full=$full",
                                expected,
                                LiveRoomGuide.message(lost, dark, fast, closer, full),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `tracking lost beats every other cause, and too dark says what to do about a dark loss`() {
        assertEquals(LiveRoomMessage.TrackingLost, LiveRoomGuide.message(true, false, true, true, true))
        assertEquals(LiveRoomMessage.TooDark, LiveRoomGuide.message(true, true, true, true))
        assertEquals(LiveRoomMessage.SlowDown, LiveRoomGuide.message(false, false, true, true))
        assertEquals(LiveRoomMessage.MoveCloser, LiveRoomGuide.message(false, false, false, true))
        assertNull(LiveRoomGuide.message(false, false, false, false))
    }

    @Test
    fun `each message reads as one short word`() {
        assertEquals("Tracking lost", LiveRoomMessage.TrackingLost.blocker.short)
        assertEquals("Too dark", LiveRoomMessage.TooDark.blocker.short)
        assertEquals("Slow down", LiveRoomMessage.SlowDown.blocker.short)
        assertEquals("Move closer", LiveRoomMessage.MoveCloser.blocker.short)
        LiveRoomMessage.entries.forEach { assertTrue(it.name, it.blocker.short.split(' ').size <= 2) }
    }

    @Test
    fun `the cue is the message alone while scanning, the stop line once done, nothing otherwise`() {
        assertEquals(
            CaptureCue.Blocked(ScanBlocker.TooFast, brief = true),
            LiveRoomGuide.cue(ScanPhase.Scanning, LiveRoomMessage.SlowDown, RoomDone.Covered),
        )
        assertEquals(
            CaptureCue.Next(ScanCopy.ROOM_DONE),
            LiveRoomGuide.cue(ScanPhase.Scanning, null, RoomDone.Covered),
        )
        assertEquals(
            CaptureCue.Next(CaptureChrome.FLOOR_MOSTLY),
            LiveRoomGuide.cue(ScanPhase.Scanning, null, RoomDone.Plateau),
        )
        assertNull(LiveRoomGuide.cue(ScanPhase.Scanning, null, null))
        assertEquals(CaptureCue.Next(CaptureChrome.PAUSED_NEXT), LiveRoomGuide.cue(ScanPhase.Paused, LiveRoomMessage.TooDark, null))
        assertNull(LiveRoomGuide.cue(ScanPhase.Reviewing, LiveRoomMessage.TooDark, null))
    }

    @Test
    fun `a gap on screen shows no chevron`() {
        assertNull(GapChevron.place(EYE, LENS, 0f, 0f, -2f))
        assertNull(GapChevron.place(EYE, LENS, 0.5f, 0.3f, -2f))
    }

    @Test
    fun `a gap off to the right puts the chevron on the right edge, pointing right`() {
        val place = requireNotNull(GapChevron.place(EYE, LENS, 3f, 0f, -1f))
        assertEquals(1f, place.x, TOLERANCE)
        assertEquals(0f, place.y, TOLERANCE)
        assertEquals(0f, place.degrees, TOLERANCE)
    }

    @Test
    fun `a gap off to the left puts the chevron on the left edge, pointing left`() {
        val place = requireNotNull(GapChevron.place(EYE, LENS, -3f, 0f, -1f))
        assertEquals(-1f, place.x, TOLERANCE)
        assertEquals(180f, kotlin.math.abs(place.degrees), TOLERANCE)
    }

    @Test
    fun `a gap behind and to the right turns the phone right`() {
        val place = requireNotNull(GapChevron.place(EYE, LENS, 1f, 0f, 2f))
        assertEquals(1f, place.x, TOLERANCE)
        assertTrue("points ${place.degrees}", kotlin.math.abs(place.degrees) < 90f)
    }

    @Test
    fun `a gap behind a phone tilted down still sends the chevron to the side`() {
        // The eye pitched 30 degrees down: column-major, the camera's y and z axes turned about x.
        val c = kotlin.math.cos(Math.toRadians(-30.0)).toFloat()
        val s = kotlin.math.sin(Math.toRadians(-30.0)).toFloat()
        val tilted = floatArrayOf(1f, 0f, 0f, 0f, 0f, c, s, 0f, 0f, -s, c, 0f, 0f, 0f, 0f, 1f)
        val place = requireNotNull(GapChevron.place(tilted, LENS, 0.05f, 0f, 3f))
        assertEquals(1f, place.x, TOLERANCE)
        assertEquals(0f, place.y, TOLERANCE)
        assertEquals(0f, place.degrees, TOLERANCE)
    }

    @Test
    fun `no depth message in the Live Room, where it read as a false alarm`() {
        assertTrue(LiveRoomMessage.entries.none { it.blocker == ScanBlocker.NoDepth || it.blocker == ScanBlocker.DepthLost })
    }

    @Test
    fun `a part fill line gives way to a blocker, the one message`() {
        assertFalse(LiveRoomGuide.partLineShown(CaptureCue.Blocked(ScanBlocker.TooFast, brief = true)))
        assertFalse(LiveRoomGuide.partLineShown(CaptureCue.Blocked(ScanBlocker.Full, brief = true)))
        assertTrue(LiveRoomGuide.partLineShown(null))
        assertTrue(LiveRoomGuide.partLineShown(CaptureCue.Next(ScanCopy.ROOM_DONE)))
    }

    @Test
    fun `the chevron leads to the part being fixed or reviewed, before any gap and after the room is done`() {
        val gap = floatArrayOf(0f, 0f, 2f, -4f)
        val part = floatArrayOf(3f, -1f)
        assertEquals(Triple(3f, 1.4f, -1f), GapChevron.aim(gap, part, done = false, height = 1.4f))
        assertEquals(Triple(3f, 1.4f, -1f), GapChevron.aim(null, part, done = true, height = 1.4f))
        assertEquals(Triple(1f, 1.4f, -2f), GapChevron.aim(gap, null, done = false, height = 1.4f))
        assertNull(GapChevron.aim(gap, null, done = true, height = 1.4f))
        assertNull(GapChevron.aim(null, null, done = false, height = 1.4f))
    }

    @Test
    fun `a part being fixed, off screen, gets the chevron, which goes once the part is well inside`() {
        // Fix now's part to the right of a phone looking down -z: the chevron on the right edge.
        val (x, y, z) = requireNotNull(GapChevron.aim(null, floatArrayOf(3f, -1f), done = false, height = 0f))
        val place = requireNotNull(GapChevron.place(EYE, LENS, x, y, z))
        assertEquals(1f, place.x, TOLERANCE)
        val (cx, cy, cz) = requireNotNull(GapChevron.aim(null, floatArrayOf(0.2f, -2f), done = false, height = 0f))
        assertNull(GapChevron.place(EYE, LENS, cx, cy, cz, shown = true))
    }

    @Test
    fun `the chevron's band keeps the whole disc clear of the measured status card and controls`() {
        // A 2,400 px stage, the card's bottom edge at 24 %, the controls from 70 %: a 160 px chevron.
        val height = 2400f
        val chevron = 160f
        val band = GapChevron.band(height, topCover = 0.24f * height, bottomCover = 0.30f * height, chevron = chevron)
        // BiasAlignment puts the chevron's top at (height - chevron) * (1 + bias) / 2.
        val topEdge = (height - chevron) * (1f + band.start) / 2f
        val bottomEdge = (height - chevron) * (1f + band.endInclusive) / 2f + chevron
        assertEquals(0.24f * height, topEdge, 0.5f)
        assertEquals(0.70f * height, bottomEdge, 0.5f)
        // A gap straight up stops at the band's top, under the card.
        val place = requireNotNull(GapChevron.place(EYE, LENS, 0f, 5f, -1f, top = band.start, bottom = band.endInclusive))
        assertEquals(band.start, place.y, TOLERANCE)
        // Chrome that leaves no room round the middle falls back to the guess.
        assertEquals(-GapChevron.MAX_Y..GapChevron.MAX_Y, GapChevron.band(height, height * 0.6f, height * 0.6f, chevron))
    }

    @Test
    fun `a gap high above keeps the chevron clear of the status card`() {
        val place = requireNotNull(GapChevron.place(EYE, LENS, 0f, 5f, -1f))
        assertEquals(-GapChevron.MAX_Y, place.y, TOLERANCE)
        assertEquals(-90f, place.degrees, TOLERANCE)
    }

    @Test
    fun `a chevron shown stays until its gap is well inside the picture`() {
        // 0.9 across the screen: inside it, not well inside.
        val x = 0.9f
        assertNull(GapChevron.place(EYE, LENS, x, 0f, -2f, shown = false))
        requireNotNull(GapChevron.place(EYE, LENS, x, 0f, -2f, shown = true))
        assertNull(GapChevron.place(EYE, LENS, 0.5f, 0f, -2f, shown = true))
    }

    @Test
    fun `the chevron aims at the middle of the gap, at the eye's height`() {
        val (x, y, z) = GapChevron.target(floatArrayOf(0f, 0f, 2f, -4f), height = 1.4f)
        assertEquals(1f, x, TOLERANCE)
        assertEquals(1.4f, y, TOLERANCE)
        assertEquals(-2f, z, TOLERANCE)
    }

    @Test
    fun `the room model fits what was seen, north up, within its zoom bounds`() {
        val frame = requireNotNull(MiniatureFrame.of(floatArrayOf(-2f, 2f), floatArrayOf(-1f, 3f), 100f, 100f, 10f))
        assertEquals(0f, frame.centreX, TOLERANCE)
        assertEquals(1f, frame.centreZ, TOLERANCE)
        assertEquals(80f / 4f, frame.pxPerMetre, TOLERANCE)
        // A single point zooms no closer than a metre and a half either side.
        val point = requireNotNull(MiniatureFrame.of(floatArrayOf(1f), floatArrayOf(1f), 100f, 100f, 10f))
        assertEquals(50f / MiniatureFrame.MIN_HALF_RANGE_M, point.pxPerMetre, TOLERANCE)
        // A hall no further than fifteen metres either side.
        val hall = requireNotNull(MiniatureFrame.of(floatArrayOf(-100f, 100f), floatArrayOf(0f, 0f), 100f, 100f, 10f))
        assertEquals(50f / MiniatureFrame.MAX_HALF_RANGE_M, hall.pxPerMetre, TOLERANCE)
        assertNull(MiniatureFrame.of(FloatArray(0), FloatArray(0), 100f, 100f, 10f))
    }

    private companion object {
        const val TOLERANCE = 1e-3f

        /** The phone at the origin, looking down -z, y up: ARCore's camera frame. */
        val EYE = FloatArray(16).also {
            it[0] = 1f
            it[5] = 1f
            it[10] = 1f
            it[15] = 1f
        }

        /** A lens whose screen edge is at x = -z / 2: normalised x is 2x / -z. */
        val LENS = FloatArray(16).also {
            it[0] = 2f
            it[5] = 2f
            it[10] = -1f
            it[11] = -1f
            it[14] = -0.1f
        }
    }
}
