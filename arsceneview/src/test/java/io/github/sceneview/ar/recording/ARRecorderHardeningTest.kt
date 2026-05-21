package io.github.sceneview.ar.recording

import com.google.ar.core.Frame
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.Session
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.UUID

/**
 * JVM Robolectric tests for the ARRecorder hardening landed in #1845.
 *
 * Covers, in order of the issue items:
 *  3. [ARRecorder.recordFrame] IO_ERROR write order — `state` and `errorMessage` flip
 *     together (Snapshot-mutation commit); a reader after the transition never observes
 *     `state == RECORDING` paired with a non-null `errorMessage`.
 *  4. [ARRecorder.recordTrack] handle-ownership validation — calls with a handle not
 *     registered via [ARRecorder.addTrack] short-circuit to `false` BEFORE touching the
 *     Frame's `recordTrackData`. Also covers cross-recorder handle reuse.
 *  5. [ARRecorder.addTrack] bounded cap — past [ARRecorder.MAX_PENDING_TRACKS] distinct
 *     UUIDs, addTrack raises [IllegalStateException]. [ARRecorder.clearTracks] resets the
 *     registry. (Items 1 — playbackDatasetUri scheme allowlist — and 2 — cameraConfigFilter
 *     emptySet target FPS — live in their respective test files
 *     `ARSceneViewPlaybackUriTest` and `CameraConfigFilterBuilderTest`.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    shadows = [
        ARRecorderTest.ShadowRecordingConfig::class,
        ARRecorderTest.ShadowCameraConfigFilter::class,
    ],
    manifest = Config.NONE,
)
class ARRecorderHardeningTest {

    private lateinit var tempDir: File
    private lateinit var outFile: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("ar-recorder-1845").toFile()
        outFile = File(tempDir, "session.mp4")
        ARRecorderTest.ShadowRecordingConfig.reset()
    }

    @After
    fun tearDown() {
        outFile.delete()
        tempDir.deleteRecursively()
        ARRecorderTest.ShadowRecordingConfig.reset()
    }

    // ── Item 3: IO_ERROR write order (#1845) ─────────────────────────────────

    @Test
    fun `IO_ERROR transition leaves state and errorMessage in a consistent pair`() {
        // The prior code wrote `_errorMessage` first and `_state` second outside a Snapshot
        // mutation. A reader interleaving between the two writes could observe
        // `state == RECORDING` with a populated `errorMessage`. With the Snapshot.withMutableSnapshot
        // commit, the pair becomes atomic to any Compose snapshot reader, so the final
        // visible state is always the new (IO_ERROR, populated) tuple.
        val recorder = ARRecorder()
        val session = ARRecorderTest.FakeSession(playbackStatus = PlaybackStatus.NONE)
        assertTrue(recorder.start(session, outFile))
        assertEquals(ARRecorder.State.RECORDING, recorder.state)

        session.recordingStatusValue = com.google.ar.core.RecordingStatus.IO_ERROR
        recorder.recordFrame(session)

        // Post-condition: the pair is consistent.
        assertEquals(ARRecorder.State.IO_ERROR, recorder.state)
        assertNotNull("errorMessage must be set whenever state == IO_ERROR", recorder.errorMessage)
        // Negative: the previous bug-state ("RECORDING" + populated message) must NOT hold.
        assertFalse(
            "state must not stay RECORDING after an IO_ERROR transition",
            recorder.state == ARRecorder.State.RECORDING && recorder.errorMessage != null,
        )
    }

    // ── Item 4: recordTrack handle ownership (#1845) ─────────────────────────

    @Test
    fun `recordTrack returns false when handle was not registered via addTrack`() {
        val recorder = ARRecorder()
        val session = ARRecorderTest.FakeSession(playbackStatus = PlaybackStatus.NONE)
        assertTrue(recorder.start(session, outFile))
        assertEquals(ARRecorder.State.RECORDING, recorder.state)

        // Build a handle without calling addTrack — simulates a caller who hand-rolled a
        // TrackHandle (the data class has a public constructor) or carried a handle over
        // from a different recorder instance.
        val orphanHandle = ARRecorder.TrackHandle(UUID.randomUUID(), "application/text")

        val frame = ThrowingFrame()  // would throw if recordTrackData were called
        val result = recorder.recordTrack(orphanHandle, frame, ByteBuffer.allocate(1))

        assertFalse("recordTrack must short-circuit on an unregistered handle", result)
        assertFalse("Frame.recordTrackData must not be reached", frame.recordTrackDataCalled)
    }

    @Test
    fun `recordTrack rejects a handle minted by a different recorder instance`() {
        // Cross-recorder reuse — recorder A registered the handle, recorder B did not.
        // Without the ownership check, recorder B would have happily forwarded the call to
        // ARCore, polluting recorder A's track stream with packets from B's frame source.
        val recorderA = ARRecorder()
        val recorderB = ARRecorder()
        val uuid = UUID.randomUUID()
        val handleFromA = recorderA.addTrack(uuid, "application/text")

        val sessionB = ARRecorderTest.FakeSession(playbackStatus = PlaybackStatus.NONE)
        assertTrue(recorderB.start(sessionB, outFile))

        val frame = ThrowingFrame()
        val result = recorderB.recordTrack(handleFromA, frame, ByteBuffer.allocate(1))

        assertFalse("recordTrack must reject a handle owned by another recorder", result)
        assertFalse(frame.recordTrackDataCalled)
    }

    @Test
    fun `recordTrack returns false when recorder is IDLE regardless of registration`() {
        // The pre-existing `_state != RECORDING` guard is preserved; the new ownership
        // check is additive and runs only after the state check passes.
        val recorder = ARRecorder()
        val handle = recorder.addTrack(UUID.randomUUID(), "application/text")
        val frame = ThrowingFrame()

        val result = recorder.recordTrack(handle, frame, ByteBuffer.allocate(1))

        assertFalse("recordTrack must short-circuit when idle", result)
        assertFalse(frame.recordTrackDataCalled)
    }

    // ── Item 5: bounded addTrack + clearTracks (#1845) ───────────────────────

    @Test
    fun `addTrack accepts up to MAX_PENDING_TRACKS distinct UUIDs`() {
        val recorder = ARRecorder()
        repeat(ARRecorder.MAX_PENDING_TRACKS) {
            recorder.addTrack(UUID.randomUUID(), "application/octet-stream")
        }
        // The Nth+1 call must fail-fast — the in-memory registry would otherwise grow
        // unbounded when a caller wires `addTrack(UUID.randomUUID(), …)` into a
        // recomposing block.
        try {
            recorder.addTrack(UUID.randomUUID(), "application/octet-stream")
            fail("addTrack past MAX_PENDING_TRACKS must throw IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(
                "error message should mention the cap, got: ${e.message}",
                e.message!!.contains(ARRecorder.MAX_PENDING_TRACKS.toString()),
            )
        }
    }

    @Test
    fun `addTrack idempotent re-registration does not consume cap quota`() {
        // Re-calling addTrack with the same UUID is a no-op for the registry — important
        // because the call site is often a recomposing block. Cap must not be tripped by
        // benign re-entry.
        val recorder = ARRecorder()
        val uuid = UUID.randomUUID()
        repeat(ARRecorder.MAX_PENDING_TRACKS * 4) {
            recorder.addTrack(uuid, "application/text")
        }
        // No throw — cap was not consumed by the duplicates.
    }

    @Test
    fun `clearTracks empties the registry and re-enables addTrack up to the cap`() {
        val recorder = ARRecorder()
        repeat(ARRecorder.MAX_PENDING_TRACKS) {
            recorder.addTrack(UUID.randomUUID(), "application/octet-stream")
        }
        // Cap is now reached — verify, then clear.
        try {
            recorder.addTrack(UUID.randomUUID(), "application/octet-stream")
            fail("expected cap to be reached")
        } catch (_: IllegalStateException) { /* expected */ }

        recorder.clearTracks()

        // After clear, addTrack works again until the cap is reached anew.
        recorder.addTrack(UUID.randomUUID(), "application/octet-stream")
    }

    @Test
    fun `clearTracks is idempotent on an empty registry`() {
        val recorder = ARRecorder()
        recorder.clearTracks()
        recorder.clearTracks()
        // No throw — second invocation must not raise.
    }

    @Test
    fun `clearTracks unregisters previously-owned handles for recordTrack purposes`() {
        // After clearTracks, the same TrackHandle no longer passes the ownership check —
        // this is the contract used by callers who change their track schema between
        // recordings.
        //
        // Start a recording with an EMPTY registry first (so `start()` doesn't try to wire
        // any Track into the RecordingConfig — `Track(session)` is JNI-backed and would
        // crash the unit test under Robolectric). Then register the handle AFTER `start()`
        // — addTrack is a pure in-memory mutation, so it's safe to call post-start; only
        // the NEXT recording would actually pick those up.
        val recorder = ARRecorder()
        val session = ARRecorderTest.FakeSession(playbackStatus = PlaybackStatus.NONE)
        assertTrue(recorder.start(session, outFile))
        val handle = recorder.addTrack(UUID.randomUUID(), "application/text")

        recorder.clearTracks()

        val frame = ThrowingFrame()
        val result = recorder.recordTrack(handle, frame, ByteBuffer.allocate(1))
        assertFalse("recordTrack must reject a handle dropped by clearTracks", result)
        assertFalse(frame.recordTrackDataCalled)
    }

    /**
     * [Frame] subclass that flags whether [recordTrackData] was invoked and throws if it
     * was — used to assert that the recorder's short-circuit branches return BEFORE the
     * JNI call would be issued.
     */
    private class ThrowingFrame : Frame() {
        var recordTrackDataCalled: Boolean = false
            private set

        override fun recordTrackData(uuid: UUID?, data: ByteBuffer?) {
            recordTrackDataCalled = true
            throw AssertionError(
                "ARRecorder.recordTrack must short-circuit before invoking " +
                    "Frame.recordTrackData when state != RECORDING, the handle is " +
                    "unregistered, or any other guard fails.",
            )
        }
    }
}
