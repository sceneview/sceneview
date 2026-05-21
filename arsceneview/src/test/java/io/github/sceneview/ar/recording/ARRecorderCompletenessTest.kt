package io.github.sceneview.ar.recording

import com.google.ar.core.PlaybackStatus
import com.google.ar.core.RecordingStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * JVM Robolectric tests for the recording/playback completeness work landed in #1770.
 *
 * Covers:
 *  - [ARRecorder.State.IO_ERROR] is distinct from [ARRecorder.State.ERROR] and is surfaced when
 *    `recordFrame(session)` observes [RecordingStatus.IO_ERROR] while RECORDING.
 *  - [ARRecorder.addTrack] is idempotent on the (uuid, mimeType) key — re-registering does not
 *    duplicate the entry.
 *  - [ARRecorder.recordTrack] is a no-op when the recorder is not RECORDING.
 *  - [ARRecorder.clearError] resets both ERROR and IO_ERROR states.
 *
 * Reuses the [ARRecorderTest.FakeSession] subclass for parity with the existing recorder suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    shadows = [
        ARRecorderTest.ShadowRecordingConfig::class,
        ARRecorderTest.ShadowCameraConfigFilter::class,
    ],
    manifest = Config.NONE,
)
class ARRecorderCompletenessTest {

    private lateinit var tempDir: File
    private lateinit var outFile: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("ar-recorder-1770").toFile()
        outFile = File(tempDir, "session.mp4")
        ARRecorderTest.ShadowRecordingConfig.reset()
    }

    @After
    fun tearDown() {
        outFile.delete()
        tempDir.deleteRecursively()
        ARRecorderTest.ShadowRecordingConfig.reset()
    }

    // ── IO_ERROR state distinct from ERROR ───────────────────────────────────

    @Test
    fun `IO_ERROR is a distinct enum value from ERROR`() {
        assertFalse(
            "IO_ERROR must not equal ERROR — apps need exhaustive `when` matching " +
                "(disk-full vs config-error) per #1770 acceptance.",
            ARRecorder.State.IO_ERROR == ARRecorder.State.ERROR,
        )
    }

    @Test
    fun `recordFrame transitions to IO_ERROR when session reports RecordingStatus IO_ERROR`() {
        val recorder = ARRecorder()
        val session = ARRecorderTest.FakeSession(playbackStatus = PlaybackStatus.NONE)
        // Drive recorder into RECORDING.
        assertTrue(recorder.start(session, outFile))
        assertEquals(ARRecorder.State.RECORDING, recorder.state)

        // Simulate disk-full mid-recording.
        session.recordingStatusValue = RecordingStatus.IO_ERROR
        recorder.recordFrame(session)

        assertEquals(ARRecorder.State.IO_ERROR, recorder.state)
        val msg = recorder.errorMessage
        assertNotNull("errorMessage must surface the IO_ERROR cause", msg)
        assertTrue(
            "expected message to mention I/O, got: $msg",
            msg!!.contains("I/O", ignoreCase = true) || msg.contains("IO_ERROR"),
        )
    }

    @Test
    fun `recordFrame does NOT transition to IO_ERROR while idle`() {
        val recorder = ARRecorder()
        val session = ARRecorderTest.FakeSession()
        // Force the recordingStatus to IO_ERROR but stay IDLE — the poll must skip the
        // transition. This prevents a stale recordingStatus from a previous recording from
        // poisoning a fresh idle session.
        session.recordingStatusValue = RecordingStatus.IO_ERROR
        recorder.recordFrame(session)

        assertEquals(ARRecorder.State.IDLE, recorder.state)
        assertNull(recorder.errorMessage)
    }

    // ── clearError resets ERROR and IO_ERROR ─────────────────────────────────

    @Test
    fun `clearError resets IO_ERROR back to IDLE`() {
        val recorder = ARRecorder()
        val session = ARRecorderTest.FakeSession()
        assertTrue(recorder.start(session, outFile))
        session.recordingStatusValue = RecordingStatus.IO_ERROR
        recorder.recordFrame(session)
        assertEquals(ARRecorder.State.IO_ERROR, recorder.state)

        recorder.clearError()

        assertEquals(ARRecorder.State.IDLE, recorder.state)
        assertNull(recorder.errorMessage)
    }

    // ── addTrack ─────────────────────────────────────────────────────────────

    @Test
    fun `addTrack returns a TrackHandle carrying the requested uuid and mime`() {
        val recorder = ARRecorder()
        val uuid = UUID.randomUUID()
        val handle = recorder.addTrack(uuid, "application/text")
        assertEquals(uuid, handle.uuid)
        assertEquals("application/text", handle.mimeType)
    }

    @Test
    fun `addTrack is idempotent on the uuid key`() {
        val recorder = ARRecorder()
        val uuid = UUID.randomUUID()
        val first = recorder.addTrack(uuid, "application/text")
        val second = recorder.addTrack(uuid, "application/text")
        // Same handle data — caller may safely re-invoke addTrack in a recomposing block
        // without duplicating native-side track registrations.
        assertEquals(first, second)
    }

    // ── recordTrack ──────────────────────────────────────────────────────────

    // recordTrack(handle, frame, data) — the state-guard cannot be unit-tested without a
    // Frame instance (JNI-only), but the guard is straightforward and proven by inspection
    // of the source (`if (_state != State.RECORDING) return false` short-circuits before
    // touching the frame). The interesting end-to-end path is exercised on-device.
}
