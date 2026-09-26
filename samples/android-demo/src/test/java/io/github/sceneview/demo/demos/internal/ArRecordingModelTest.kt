package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * JVM tests for the AR Recording demo's pure logic (#3831). The demo cannot run on the
 * emulator (no camera HAL, #2754), so these pin what the user sees and what gets written
 * into a recording without a device.
 */
class ArRecordingModelTest {

    private val placement = RecordedPlacement(
        index = 2,
        cameraRelative = RigidPose(0.25f, -1.4f, -0.8f, 0f, 0.7071f, 0f, 0.7071f),
    )

    private fun RecordedPlacement.withPose(block: RigidPose.() -> RigidPose) =
        copy(cameraRelative = cameraRelative.block())

    private fun assertPoseEquals(expected: RigidPose, actual: RigidPose, delta: Float = 1e-4f) {
        assertEquals(expected.tx, actual.tx, delta)
        assertEquals(expected.ty, actual.ty, delta)
        assertEquals(expected.tz, actual.tz, delta)
        // q and -q are the same rotation.
        val sign = if (expected.qw * actual.qw + expected.qx * actual.qx +
            expected.qy * actual.qy + expected.qz * actual.qz < 0f
        ) -1f else 1f
        assertEquals(expected.qx, sign * actual.qx, delta)
        assertEquals(expected.qy, sign * actual.qy, delta)
        assertEquals(expected.qz, sign * actual.qz, delta)
        assertEquals(expected.qw, sign * actual.qw, delta)
    }

    /** A camera turned 90 degrees to the left (about +Y), 1.5 m up and 2 m to the side. */
    private val camera = RigidPose(2f, 1.5f, 0f, 0f, 0.70710677f, 0f, 0.70710677f)
    private val identity = RigidPose(0f, 0f, 0f, 0f, 0f, 0f, 1f)

    // ── Placement track ──────────────────────────────────────────────────────────────────

    @Test
    fun `a placement survives an encode then decode round trip`() {
        val bytes = PlacementTrack.encode(placement)
        assertEquals(PlacementTrack.PACKET_BYTES, bytes.size)
        assertEquals(placement, PlacementTrack.decode(bytes))
    }

    @Test
    fun `the packet is little endian and starts with the format version`() {
        val bytes = PlacementTrack.encode(placement)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(PlacementTrack.FORMAT_VERSION, b.getInt())
        assertEquals(2, b.getInt())
        assertEquals(0.25f, b.getFloat(), 0f)
    }

    @Test
    fun `decoding a buffer leaves the caller's position untouched`() {
        val buffer = ByteBuffer.wrap(PlacementTrack.encode(placement))
        assertEquals(placement, PlacementTrack.decode(buffer))
        assertEquals(0, buffer.position())
    }

    @Test
    fun `malformed packets decode to null`() {
        assertNull(PlacementTrack.decode(ByteArray(PlacementTrack.PACKET_BYTES - 1)))
        val wrongVersion = PlacementTrack.encode(placement).also { it[0] = 9 }
        assertNull(PlacementTrack.decode(wrongVersion))
        assertNull(PlacementTrack.decode(PlacementTrack.encode(placement.copy(index = -1))))
        assertNull(PlacementTrack.decode(PlacementTrack.encode(placement.withPose { copy(tx = Float.NaN) })))
        assertNull(
            PlacementTrack.decode(PlacementTrack.encode(placement.withPose { copy(qw = Float.POSITIVE_INFINITY) })),
        )
    }

    @Test
    fun `the track id and MIME type never change between app versions`() {
        assertEquals("7f3c2a54-9d1e-4c8b-a6f0-38310a5e0c01", PlacementTrack.TRACK_ID.toString())
        assertEquals("application/vnd.sceneview.placement", PlacementTrack.MIME_TYPE)
    }

    // ── Pose math ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a pose composed with its inverse is the identity`() {
        assertPoseEquals(identity, camera.compose(camera.inverse()))
        assertPoseEquals(identity, camera.inverse().compose(camera))
        assertPoseEquals(placement.cameraRelative, placement.cameraRelative.inverse().inverse())
    }

    @Test
    fun `composing rotates the second translation by the first rotation`() {
        // Turned 90 degrees left about +Y, "1 m ahead" (-Z) lands 1 m to the left (-X).
        val ahead = RigidPose(0f, 0f, -1f, 0f, 0f, 0f, 1f)
        val world = camera.compose(ahead)
        assertPoseEquals(RigidPose(1f, 1.5f, 0f, 0f, 0.70710677f, 0f, 0.70710677f), world)
    }

    @Test
    fun `a placement recorded relative to the camera comes back at the same world spot`() {
        val anchor = RigidPose(0.3f, 0f, -1.2f, 0f, 0f, 0f, 1f)
        val recorded = placementOf(index = 0, cameraWorld = camera, anchorWorld = anchor)
        assertPoseEquals(anchor, placementWorldPose(recorded, camera))
    }

    @Test
    fun `a replay whose world starts elsewhere still puts the object in front of the same view`() {
        // The replay's world origin is offset and turned relative to the live one: the same
        // physical camera has a different world pose, and so must the restored anchor.
        val originShift = RigidPose(-4f, 0.2f, 7f, 0f, 0.38268343f, 0f, 0.9238795f)
        val anchor = RigidPose(0.3f, 0f, -1.2f, 0f, 0f, 0f, 1f)
        val recorded = placementOf(index = 0, cameraWorld = camera, anchorWorld = anchor)

        val replayCamera = originShift.compose(camera)
        val restored = placementWorldPose(recorded, replayCamera)

        assertPoseEquals(originShift.compose(anchor), restored)
        // Seen from the camera, the object sits exactly where it sat while recording.
        assertPoseEquals(recorded.cameraRelative, replayCamera.inverse().compose(restored))
    }

    // ── Replay queue ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a placement read many times is restored once, and only while the camera holds`() {
        val queue = PlacementReplayQueue()
        assertFalse(queue.shouldRestore(placement, tracking = false))
        assertEquals(1, queue.seenCount)
        assertEquals(0, queue.restoredCount)
        assertTrue(queue.shouldRestore(placement, tracking = true))
        assertFalse(queue.shouldRestore(placement.withPose { copy(tx = 9f) }, tracking = true))
        assertEquals(1, queue.restoredCount)
        assertTrue(queue.shouldRestore(placement.copy(index = 0), tracking = true))
        assertEquals(2, queue.seenCount)
        assertEquals(2, queue.restoredCount)
    }

    @Test
    fun `a corrupt pose is never restored and reset starts over`() {
        val queue = PlacementReplayQueue()
        assertFalse(queue.shouldRestore(placement.withPose { copy(qx = Float.NaN) }, tracking = true))
        assertTrue(queue.shouldRestore(placement, tracking = true))
        queue.reset()
        assertEquals(0, queue.seenCount)
        assertEquals(0, queue.restoredCount)
        assertTrue(queue.shouldRestore(placement, tracking = true))
    }

    @Test
    fun `each placement is written again about once a second`() {
        val schedule = PlacementWriteSchedule()
        val second = PLACEMENT_REWRITE_NANOS
        assertTrue(schedule.isDue(0, 5 * second))
        schedule.markWritten(0, 5 * second)
        assertFalse(schedule.isDue(0, 5 * second + second / 2))
        assertTrue(schedule.isDue(1, 5 * second + second / 2))
        assertTrue(schedule.isDue(0, 6 * second))
        // A clock that jumps back (a new session) writes again at once.
        assertTrue(schedule.isDue(0, second))
        schedule.reset()
        assertTrue(schedule.isDue(0, 5 * second + 1))
    }

    // ── Frame gate ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a repeated camera frame is counted once`() {
        val gate = NewFrameGate()
        assertTrue(gate.isNew(1_000L))
        assertFalse(gate.isNew(1_000L))
        assertFalse(gate.isNew(1_000L))
        assertTrue(gate.isNew(34_000_000L))
        assertFalse(gate.isNew(0L))
        gate.reset()
        assertTrue(gate.isNew(34_000_000L))
    }

    // ── Rotation ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a portrait capture on a 90 degree sensor is tagged 90 degrees`() {
        assertEquals(90, recordingRotationDegrees(sensorOrientationDegrees = 90, displayRotationDegrees = 0))
        assertEquals(0, recordingRotationDegrees(90, 90))
        assertEquals(180, recordingRotationDegrees(90, 270))
        assertEquals(270, recordingRotationDegrees(270, 0))
    }

    @Test
    fun `rotation degrees and surface constants convert both ways`() {
        assertEquals(1, surfaceRotationOf(90))
        assertEquals(0, surfaceRotationOf(360))
        assertEquals(3, surfaceRotationOf(-90))
        for (r in 0..3) assertEquals(r, surfaceRotationOf(degreesOfSurfaceRotation(r)))
    }

    // ── Names and titles ─────────────────────────────────────────────────────────────────

    @Test
    fun `a new recording is named after its start time`() {
        assertEquals(
            "ar-session-20260925-140512.mp4",
            recordingFileName(LocalDateTime.of(2026, 9, 25, 14, 5, 12)),
        )
    }

    @Test
    fun `titles are dates, never raw file names`() {
        val today = LocalDate.of(2026, 9, 26)
        fun title(name: String) = formatRecordingTitle(recordingTitleOf(name), today)
        assertEquals("Today, 14:05", title("ar-session-20260926-140512.mp4"))
        assertEquals("Yesterday, 09:30", title("ar-session-20260925-093000.mp4"))
        assertEquals("Sep 3, 18:45", title("ar-session-20260903-184501.mp4"))
        assertEquals("Dec 24, 2025", title("ar-session-20251224-101010.mp4"))
        assertEquals("Sample recording", title("bundled-pixel9-sample.mp4"))
        assertEquals("kitchen-take", title("kitchen-take.mp4"))
        assertEquals("ar-session-garbage", title("ar-session-garbage.mp4"))
    }

    // ── Numbers ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `clock reads like a media player`() {
        assertEquals("0:00", formatClock(-5))
        assertEquals("0:07", formatClock(7_900))
        assertEquals("0:18", formatClock(18_330))
        assertEquals("12:05", formatClock(725_000))
        assertEquals("1:02:03", formatClock(3_723_000))
    }

    @Test
    fun `sizes and distances are short and plain`() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("45 kB", formatFileSize(45_300))
        assertEquals("4.1 MB", formatFileSize(4_100_000))
        assertEquals("1.2 GB", formatFileSize(1_230_000_000))
        assertEquals("0 cm", formatDistance(0f))
        assertEquals("35 cm", formatDistance(0.349f))
        assertEquals("1.2 m", formatDistance(1.24f))
        assertEquals("0 cm", formatDistance(Float.NaN))
    }

    // ── Contents ─────────────────────────────────────────────────────────────────────────

    private val sampleMimes = listOf(
        "video/avc",
        "application/arcore-video-0",
        "application/arcore-accel-0",
        "application/arcore-gyro-0",
        "application/arcore-custom-event",
    )

    @Test
    fun `an ARCore recording is read as video, motion sensors and camera data`() {
        val contents = recordingContentsOf(sampleMimes)
        assertTrue(contents.hasVideo)
        assertEquals(2, contents.motionSensorTracks)
        assertTrue(contents.hasCameraData)
        assertFalse(contents.hasPlacements)
        assertEquals(1, contents.otherDataTracks)
        assertEquals("Camera video · Motion sensors", recordingContentsLine(contents, null))
    }

    @Test
    fun `the placement track is recognised and counted`() {
        val contents = recordingContentsOf(sampleMimes + PlacementTrack.MIME_TYPE)
        assertTrue(contents.hasPlacements)
        assertEquals("Camera video · Motion sensors · 2 placements", recordingContentsLine(contents, 2))
        assertEquals("Camera video · Motion sensors · 1 placement", recordingContentsLine(contents, 1))
        assertEquals("Camera video · Motion sensors · Your placements", recordingContentsLine(contents, null))
        // A take recorded without placing anything does not claim placements.
        assertEquals("Camera video · Motion sensors", recordingContentsLine(contents, 0))
    }

    @Test
    fun `where the camera lost its place is listed worst first in plain words`() {
        val lines = lostReasonLines(
            mapOf("INSUFFICIENT_FEATURES" to 12, "EXCESSIVE_MOTION" to 26, "INSUFFICIENT_LIGHT" to 0, "BAD_STATE" to 1),
            totalFrames = 550,
        )
        assertEquals(listOf("Moved too fast · 5%", "Not enough detail · 2%", "Restarting · 1%"), lines)
        assertTrue(lostReasonLines(mapOf("EXCESSIVE_MOTION" to 3), totalFrames = 0).isEmpty())
        assertEquals(1, lostReasonLines(mapOf("A" to 1, "B" to 2), totalFrames = 10, limit = 1).size)
    }

    @Test
    fun `a saved take is summed up as length, size and placements`() {
        assertEquals("0:18 · 4.1 MB · 2 placements", savedTakeSummary(18_300, 4_100_000, 2))
        assertEquals("0:05 · 900 kB · 1 placement", savedTakeSummary(5_000, 900_000, 1))
        assertEquals("1:02 · 38.4 MB", savedTakeSummary(62_000, 38_400_000, 0))
    }

    @Test
    fun `before recording the screen says what a take will keep`() {
        assertEquals("Tap a surface to place a fox, then record.", readyToRecordLine(0))
        assertTrue(readyToRecordLine(1).endsWith("your fox."))
        assertTrue(readyToRecordLine(3).contains("camera video, the motion sensors and your 3 foxes"))
    }

    @Test
    fun `recorder failures are explained without the developer message`() {
        val lines = listOf(recorderErrorLine(storageFailed = true), recorderErrorLine(storageFailed = false))
        assertTrue(lines[0].contains("space"))
        assertTrue(lines[1].contains("try again"))
        for (text in lines) {
            for (jargon in listOf("session", "ARCore", "recordFrame", "I/O", "exception")) {
                assertFalse("'$text' contains '$jargon'", text.contains(jargon, ignoreCase = true))
            }
        }
    }

    @Test
    fun `a file with nothing recognisable still names its video`() {
        assertEquals("Camera video", recordingContentsLine(recordingContentsOf(emptyList()), null))
    }

    // ── Live stats ───────────────────────────────────────────────────────────────────────

    @Test
    fun `live stats group thousands and pick singular labels`() {
        assertEquals(
            listOf(
                LiveStat("1,204", "Frames"),
                LiveStat("2.3 m", "Moved"),
                LiveStat("1", "Surface"),
                LiveStat("3", "Placed"),
            ),
            liveCaptureStats(frames = 1204, metersMoved = 2.3f, surfaces = 1, placements = 3),
        )
        assertEquals("Restored", liveCaptureStats(1, 0f, 2, 0, placementsLabel = "Restored")[3].label)
        assertEquals("Frame", liveCaptureStats(1, 0f, 2, 0)[0].label)
    }

    // ── Take quality ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a take is steady from 70 percent of held frames`() {
        assertEquals(TakeQuality.Empty, takeQualityOf(0, 0))
        assertEquals(TakeQuality.Steady, takeQualityOf(70, 100))
        assertEquals(TakeQuality.Unsteady, takeQualityOf(69, 100))
        assertEquals(94, steadyPercent(94, 100))
        assertEquals(100, steadyPercent(120, 100))
    }

    @Test
    fun `quality copy never uses AR jargon`() {
        val lines = listOf(takeQualityLine(94, 100), takeQualityLine(41, 100), takeQualityLine(0, 0))
        assertTrue(lines[0].contains("94%"))
        assertTrue(lines[1].contains("may not replay"))
        val onScreen = lines + listOf(
            "INSUFFICIENT_LIGHT", "EXCESSIVE_MOTION", "INSUFFICIENT_FEATURES",
            "CAMERA_UNAVAILABLE", "BAD_STATE", "NONE",
        ).map(::lostReasonLabel)
        for (text in onScreen) {
            for (jargon in listOf("tracking", "ARCore", "plane", "_")) {
                assertFalse("'$text' contains '$jargon'", text.contains(jargon, ignoreCase = true))
            }
        }
    }

    // ── Replay ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `replay phase follows selection, first frame and end of file`() {
        assertEquals(ReplayPhase.Choosing, replayPhaseOf(hasSelection = false, firstFrameSeen = true, finished = true))
        assertEquals(ReplayPhase.Loading, replayPhaseOf(hasSelection = true, firstFrameSeen = false, finished = false))
        assertEquals(ReplayPhase.Playing, replayPhaseOf(hasSelection = true, firstFrameSeen = true, finished = false))
        assertEquals(ReplayPhase.Finished, replayPhaseOf(hasSelection = true, firstFrameSeen = true, finished = true))
    }

    @Test
    fun `replay progress is clamped and zero while the length is unknown`() {
        assertEquals(0f, replayProgress(5_000, 0), 0f)
        assertEquals(0.5f, replayProgress(9_000, 18_000), 0.0001f)
        assertEquals(1f, replayProgress(20_000, 18_000), 0f)
        assertEquals(0f, replayProgress(-1, 18_000), 0f)
    }

    @Test
    fun `QA state ids resolve and unknown ids are ignored`() {
        assertEquals(RecordingQaState.Idle, RecordingQaState.of("idle"))
        assertEquals(RecordingQaState.Recording, RecordingQaState.of("recording"))
        assertEquals(RecordingQaState.Recordings, RecordingQaState.of("recordings"))
        assertEquals(RecordingQaState.Replaying, RecordingQaState.of("replaying"))
        assertEquals(RecordingQaState.ReplayFinished, RecordingQaState.of("replay-finished"))
        assertNull(RecordingQaState.of("host"))
        assertNull(RecordingQaState.of(null))
    }
}
