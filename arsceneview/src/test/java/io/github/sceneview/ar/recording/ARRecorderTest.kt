package io.github.sceneview.ar.recording

import android.util.Size
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.RecordingConfig
import com.google.ar.core.Session
import com.google.ar.core.exceptions.RecordingFailedException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [ARRecorder].
 *
 * The ARCore [Session] and [RecordingConfig] classes are not `final`, so we exercise
 * the recorder against:
 *
 *  * a hand-rolled [FakeSession] subclass that overrides only the methods we need
 *    ([Session.getPlaybackStatus], [Session.startRecording], [Session.stopRecording]),
 *  * a Robolectric [Shadow][org.robolectric.shadow.api.Shadow] for [RecordingConfig]
 *    so calls to the native-backed setters become observable in pure JVM.
 *
 * All tests run on the Robolectric runtime, which neutralises the JNI symbols ARCore
 * would otherwise pull in. No emulator is required.
 *
 * Closes [sceneview/sceneview#875](https://github.com/sceneview/sceneview/issues/875).
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    shadows = [
        ARRecorderTest.ShadowRecordingConfig::class,
        ARRecorderTest.ShadowCameraConfigFilter::class,
    ],
    manifest = Config.NONE,
)
class ARRecorderTest {

    // ── Shared fixtures ──────────────────────────────────────────────────────

    private lateinit var tempDir: File
    private lateinit var outFile: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("ar-recorder-test").toFile()
        outFile = File(tempDir, "session.mp4")
        ShadowRecordingConfig.reset()
    }

    @After
    fun tearDown() {
        outFile.delete()
        tempDir.deleteRecursively()
        ShadowRecordingConfig.reset()
    }

    // ── Initial state ────────────────────────────────────────────────────────

    @Test
    fun `initial state is IDLE with no error and no file`() {
        val recorder = ARRecorder()
        assertEquals(ARRecorder.State.IDLE, recorder.state)
        assertNull(recorder.errorMessage)
        assertNull(recorder.recordingFile)
    }

    // ── start() preconditions ────────────────────────────────────────────────

    @Test
    fun `start without prior attach returns false and transitions to ERROR with a clear message`() {
        val recorder = ARRecorder()
        val started = recorder.start(outFile)
        assertFalse(started)
        assertEquals(ARRecorder.State.ERROR, recorder.state)
        val msg = recorder.errorMessage
        assertNotNull("errorMessage should be populated", msg)
        assertTrue(
            "expected message to mention session attachment, got: $msg",
            msg!!.contains("session", ignoreCase = true),
        )
    }

    @Test
    fun `attach does not change state`() {
        val recorder = ARRecorder()
        recorder.attach(FakeSession())
        assertEquals(ARRecorder.State.IDLE, recorder.state)
        assertNull(recorder.errorMessage)
        assertNull(recorder.recordingFile)
    }

    @Test
    fun `attach with same session twice is observably idempotent`() {
        val recorder = ARRecorder()
        val session = FakeSession()
        recorder.attach(session)
        val before = Triple(recorder.state, recorder.errorMessage, recorder.recordingFile)
        recorder.attach(session)
        val after = Triple(recorder.state, recorder.errorMessage, recorder.recordingFile)
        assertEquals(before, after)
    }

    // ── recordFrame() — stateless side-channel mirror (audit #876) ───────────

    @Test
    fun `recordFrame does not change state`() {
        val recorder = ARRecorder()
        recorder.recordFrame(FakeSession())
        assertEquals(ARRecorder.State.IDLE, recorder.state)
        assertNull(recorder.errorMessage)
        assertNull(recorder.recordingFile)
    }

    @Test
    fun `recordFrame publishes the session for a subsequent start`() {
        val recorder = ARRecorder()
        val session = FakeSession(playbackStatus = PlaybackStatus.NONE)

        // Mirror RerunBridge.logFrame: caller passes session every frame.
        recorder.recordFrame(session)

        val started = recorder.start(outFile)
        assertTrue("start() should succeed after recordFrame()", started)
        assertEquals(ARRecorder.State.RECORDING, recorder.state)
        assertEquals(1, session.startRecordingCount)
    }

    @Test
    fun `start with explicit session and file does not need a prior recordFrame`() {
        val recorder = ARRecorder()
        val session = FakeSession(playbackStatus = PlaybackStatus.NONE)

        // Stateless overload — no need to publish the session first.
        val started = recorder.start(session, outFile)

        assertTrue("start(session, file) should succeed without recordFrame()", started)
        assertEquals(ARRecorder.State.RECORDING, recorder.state)
        assertEquals(outFile, recorder.recordingFile)
        assertNull(recorder.errorMessage)
        assertEquals(1, session.startRecordingCount)
    }

    @Test
    fun `start with explicit session refreshes the published reference for stop`() {
        val recorder = ARRecorder()
        val first = FakeSession(playbackStatus = PlaybackStatus.NONE)
        val second = FakeSession(playbackStatus = PlaybackStatus.NONE)

        // Publish the first session, then immediately replace it via the explicit-session
        // start overload — stop() must call stopRecording on the SECOND session.
        recorder.recordFrame(first)
        assertTrue(recorder.start(second, outFile))
        recorder.stop()

        assertEquals(0, first.stopRecordingCount)
        assertEquals(1, second.stopRecordingCount)
    }

    // ── start() success ──────────────────────────────────────────────────────

    @Test
    fun `start when attached and playback is NONE transitions to RECORDING`() {
        val recorder = ARRecorder()
        val session = FakeSession(playbackStatus = PlaybackStatus.NONE)
        recorder.attach(session)

        val started = recorder.start(outFile)

        assertTrue("start() should succeed", started)
        assertEquals(ARRecorder.State.RECORDING, recorder.state)
        assertEquals(outFile, recorder.recordingFile)
        assertNull(recorder.errorMessage)
        // ARCore was actually told to record — guards against a fail-open refactor
        // that would set state without invoking the underlying session.
        assertEquals(1, session.startRecordingCount)
    }

    @Test
    fun `start creates the parent directory of the recording file`() {
        val recorder = ARRecorder()
        recorder.attach(FakeSession())
        val nested = File(tempDir, "deep/nested/dir/session.mp4")
        try {
            assertFalse(nested.parentFile!!.exists())

            val started = recorder.start(nested)

            assertTrue(started)
            assertTrue(
                "parent directory should be created by start()",
                nested.parentFile!!.exists(),
            )
        } finally {
            nested.parentFile?.deleteRecursively()
        }
    }

    // ── start() blocked while in playback ────────────────────────────────────

    @Test
    fun `start while session is in playback OK returns false and goes to ERROR`() {
        assertStartRefusedDuringPlayback(PlaybackStatus.OK)
    }

    @Test
    fun `start while session is in playback FINISHED returns false and goes to ERROR`() {
        assertStartRefusedDuringPlayback(PlaybackStatus.FINISHED)
    }

    @Test
    fun `start while session is in playback IO_ERROR returns false and goes to ERROR`() {
        assertStartRefusedDuringPlayback(PlaybackStatus.IO_ERROR)
    }

    private fun assertStartRefusedDuringPlayback(status: PlaybackStatus) {
        val recorder = ARRecorder()
        val session = FakeSession(playbackStatus = status)
        recorder.attach(session)

        val started = recorder.start(outFile)

        assertFalse(started)
        assertEquals(ARRecorder.State.ERROR, recorder.state)
        // Playback rejection MUST NOT leak through to ARCore — guards against
        // a fail-open refactor that would set ERROR but still invoke startRecording.
        assertEquals(0, session.startRecordingCount)
        val msg = recorder.errorMessage
        assertNotNull(msg)
        assertTrue(
            "expected error to mention playback, got: $msg",
            msg!!.contains("playback", ignoreCase = true),
        )
    }

    // ── stop() ──────────────────────────────────────────────────────────────

    @Test
    fun `stop while RECORDING returns the file and transitions to IDLE`() {
        val recorder = ARRecorder()
        val session = FakeSession()
        recorder.attach(session)
        recorder.start(outFile)
        assertEquals(ARRecorder.State.RECORDING, recorder.state)

        val returned = recorder.stop()

        assertEquals(ARRecorder.State.IDLE, recorder.state)
        assertEquals(outFile, returned)
        // recordingFile is preserved so the caller can list/share the MP4 afterwards.
        assertEquals(outFile, recorder.recordingFile)
        // ARCore was actually told to stop — guards against a refactor that would
        // drop the recording without notifying the session (which would leak file handles).
        assertEquals(1, session.stopRecordingCount)
    }

    @Test
    fun `stop while IDLE leaves state IDLE and surfaces no error`() {
        // Note: with an attached session, stop() forwards to session.stopRecording()
        // unconditionally — there is no internal IDLE guard. The user-visible contract
        // ("safe to call when idle — it's a no-op") relies on ARCore tolerating the
        // call, so what we pin down here is the recorder's externally observable
        // behaviour: state stays IDLE and no error is raised.
        val recorder = ARRecorder()
        val session = FakeSession()
        recorder.attach(session)

        val returned = recorder.stop()

        assertNull(returned)
        assertEquals(ARRecorder.State.IDLE, recorder.state)
        assertNull(recorder.errorMessage)
    }

    @Test
    fun `stop with no attached session returns the recordingFile and stays IDLE`() {
        // Documents the current early-return branch in stop() when sessionRef is null.
        val recorder = ARRecorder()
        val returned = recorder.stop()
        assertNull(returned)
        assertEquals(ARRecorder.State.IDLE, recorder.state)
    }

    // ── Double-start path (relies on ARCore throwing) ────────────────────────

    @Test
    fun `start while already RECORDING surfaces RecordingFailedException as ERROR`() {
        val recorder = ARRecorder()
        // First start succeeds; second start triggers the configured failure on
        // session.startRecording() — exactly the path the production class catches.
        val session = FakeSession(
            // After the first call, flip to throwing.
            startRecordingException = lazy { RecordingFailedException("already recording") },
            failOnStartRecordingFromCall = 2,
        )
        recorder.attach(session)
        assertTrue(recorder.start(outFile))

        val secondAttempt = recorder.start(outFile)

        assertFalse(secondAttempt)
        assertEquals(ARRecorder.State.ERROR, recorder.state)
        val msg = recorder.errorMessage
        assertNotNull(msg)
        assertTrue(
            "expected message to surface the underlying failure, got: $msg",
            msg!!.contains("already recording", ignoreCase = true),
        )
    }

    // ── ARCore exception paths ──────────────────────────────────────────────

    @Test
    fun `start propagates RecordingFailedException as ERROR with a message`() {
        val recorder = ARRecorder()
        val session = FakeSession(
            startRecordingException = lazy { RecordingFailedException("disk full") },
            failOnStartRecordingFromCall = 1,
        )
        recorder.attach(session)

        val started = recorder.start(outFile)

        assertFalse(started)
        assertEquals(ARRecorder.State.ERROR, recorder.state)
        assertNotNull(recorder.errorMessage)
        assertTrue(recorder.errorMessage!!.contains("disk full"))
    }

    @Test
    fun `stop propagates RecordingFailedException as ERROR with a message`() {
        val recorder = ARRecorder()
        val session = FakeSession(
            stopRecordingException = lazy { RecordingFailedException("io issue") },
        )
        recorder.attach(session)
        assertTrue(recorder.start(outFile))

        val returned = recorder.stop()

        assertNull(returned)
        assertEquals(ARRecorder.State.ERROR, recorder.state)
        assertNotNull(recorder.errorMessage)
        assertTrue(recorder.errorMessage!!.contains("io issue"))
    }

    // ── clearError() ─────────────────────────────────────────────────────────

    @Test
    fun `clearError on ERROR resets state to IDLE and nulls the message`() {
        val recorder = ARRecorder()
        // Force an ERROR state by calling start() without attach().
        recorder.start(outFile)
        assertEquals(ARRecorder.State.ERROR, recorder.state)
        assertNotNull(recorder.errorMessage)

        recorder.clearError()

        assertEquals(ARRecorder.State.IDLE, recorder.state)
        assertNull(recorder.errorMessage)
    }

    @Test
    fun `clearError when not in ERROR is a no-op`() {
        val recorder = ARRecorder()
        // IDLE → no-op
        recorder.clearError()
        assertEquals(ARRecorder.State.IDLE, recorder.state)
        assertNull(recorder.errorMessage)

        // RECORDING → no-op (state must not regress)
        recorder.attach(FakeSession())
        recorder.start(outFile)
        assertEquals(ARRecorder.State.RECORDING, recorder.state)
        recorder.clearError()
        assertEquals(ARRecorder.State.RECORDING, recorder.state)
    }

    // ── attach mid-RECORDING ─────────────────────────────────────────────────

    @Test
    fun `attach with a new session mid-RECORDING swaps the session pointer without changing state`() {
        // Documents the actual behaviour: attach() only writes to the AtomicReference and never
        // touches the state machine. So the recorder remains in RECORDING after a swap, and a
        // subsequent stop() will route to the NEW session — the original session never gets a
        // stopRecording() call. This is the contract; tests pin it down.
        val recorder = ARRecorder()
        val first = FakeSession()
        val second = FakeSession()
        recorder.attach(first)
        assertTrue(recorder.start(outFile))
        assertEquals(ARRecorder.State.RECORDING, recorder.state)

        recorder.attach(second)
        assertEquals(ARRecorder.State.RECORDING, recorder.state)
        assertEquals(outFile, recorder.recordingFile)

        recorder.stop()
        assertEquals(0, first.stopRecordingCount)
        assertEquals(1, second.stopRecordingCount)
    }

    // ── RecordingConfig wiring ───────────────────────────────────────────────

    @Test
    fun `start without recordingRotation does not call setRecordingRotation`() {
        val recorder = ARRecorder()
        val session = FakeSession()
        recorder.attach(session)

        recorder.start(outFile, recordingRotation = null)

        val captured = ShadowRecordingConfig.lastInstance
        assertNotNull("a RecordingConfig should have been built", captured)
        assertEquals(outFile.absolutePath, captured!!.mp4Path)
        assertEquals(true, captured.autoStopOnPause)
        assertNull(
            "setRecordingRotation must not be called when rotation is null",
            captured.rotation,
        )
        assertSame("the shadow should record the same Session instance", session, captured.session)
    }

    @Test
    fun `start with recordingRotation calls setRecordingRotation with that exact value`() {
        val recorder = ARRecorder()
        val session = FakeSession()
        recorder.attach(session)

        recorder.start(outFile, recordingRotation = 90)

        val captured = ShadowRecordingConfig.lastInstance
        assertNotNull(captured)
        assertEquals(outFile.absolutePath, captured!!.mp4Path)
        assertEquals(true, captured.autoStopOnPause)
        assertEquals(90, captured.rotation)
    }

    @Test
    fun `start always sets autoStopOnPause to true`() {
        val recorder = ARRecorder()
        val session = FakeSession()
        recorder.attach(session)
        recorder.start(outFile, recordingRotation = 270)
        assertEquals(true, ShadowRecordingConfig.lastInstance!!.autoStopOnPause)
    }

    // ── recordingResolution camera-config selection (#1065) ──────────────────

    @Test
    fun `start without recordingResolution leaves the camera config untouched`() {
        // The 640x480 default bug stays only because nothing overrode the config —
        // start() must not touch it when no resolution is requested.
        val recorder = ARRecorder()
        val session = FakeSession(
            supportedCameraConfigs = listOf(fakeCameraConfig(1920, 1080)),
        )
        recorder.attach(session)

        assertTrue(recorder.start(outFile, recordingResolution = null))

        assertNull("setCameraConfig must not be called", session.lastSetCameraConfig)
    }

    @Test
    fun `start with recordingResolution selects the matching high-res camera config`() {
        // Mirrors the #1065 acceptance: a 1080p request on a Pixel-class config list must
        // escape ARCore's low-res 640x480 default and apply the 1920x1080 config.
        val recorder = ARRecorder()
        val session = FakeSession(
            supportedCameraConfigs = listOf(
                fakeCameraConfig(640, 480),
                fakeCameraConfig(1280, 720),
                fakeCameraConfig(1920, 1080),
            ),
        )
        recorder.attach(session)

        assertTrue(recorder.start(outFile, recordingResolution = Size(1920, 1080)))

        val applied = session.lastSetCameraConfig
        assertNotNull("a camera config should have been applied", applied)
        assertEquals(1920, applied!!.imageSize.width)
        assertEquals(1080, applied.imageSize.height)
        // The recording still actually starts.
        assertEquals(ARRecorder.State.RECORDING, recorder.state)
        assertEquals(1, session.startRecordingCount)
    }

    @Test
    fun `start with recordingResolution on the explicit-session overload threads the value through`() {
        val recorder = ARRecorder()
        val session = FakeSession(
            supportedCameraConfigs = listOf(
                fakeCameraConfig(640, 480),
                fakeCameraConfig(1920, 1080),
            ),
        )

        assertTrue(recorder.start(session, outFile, recordingResolution = Size(1920, 1080)))

        assertEquals(1920, session.lastSetCameraConfig!!.imageSize.width)
    }

    @Test
    fun `start with recordingResolution still records when the device exposes no camera configs`() {
        // Degenerate device — getSupportedCameraConfigs returns empty. The recorder must
        // NOT crash and must still start recording with ARCore's existing config.
        val recorder = ARRecorder()
        val session = FakeSession(supportedCameraConfigs = emptyList())
        recorder.attach(session)

        assertTrue(recorder.start(outFile, recordingResolution = Size(1920, 1080)))

        assertNull("no config to apply — setCameraConfig must not be called", session.lastSetCameraConfig)
        assertEquals(ARRecorder.State.RECORDING, recorder.state)
    }

    @Test
    fun `start with recordingResolution survives getSupportedCameraConfigs throwing`() {
        // A JNI-backed query can throw on a session in an odd state — the recording must
        // still proceed rather than abort.
        val recorder = ARRecorder()
        val session = FakeSession(getSupportedCameraConfigsThrows = true)
        recorder.attach(session)

        assertTrue(recorder.start(outFile, recordingResolution = Size(1920, 1080)))

        assertNull(session.lastSetCameraConfig)
        assertEquals(ARRecorder.State.RECORDING, recorder.state)
    }

    // ── Test doubles ─────────────────────────────────────────────────────────

    /** [CameraConfig] test double — stubs only the image size the selector reads. */
    private class FakeCameraConfig(private val size: Size) : CameraConfig() {
        override fun getImageSize(): Size = size
    }

    private fun fakeCameraConfig(width: Int, height: Int): CameraConfig =
        FakeCameraConfig(Size(width, height))

    /**
     * Hand-rolled [Session] subclass for unit tests.
     *
     * `Session` declares a `protected Session()` no-arg constructor specifically so subclasses
     * can be created in tests without going through the JNI-backed `Session(Context)` path.
     * We only override the methods [ARRecorder] actually calls.
     */
    private class FakeSession(
        private val playbackStatus: PlaybackStatus = PlaybackStatus.NONE,
        private val startRecordingException: Lazy<RecordingFailedException>? = null,
        /**
         * 1-based call index at which [startRecording] starts throwing. `null` means never.
         * Lets us simulate "first call OK, second call fails" for the double-start scenario.
         */
        private val failOnStartRecordingFromCall: Int? = null,
        private val stopRecordingException: Lazy<RecordingFailedException>? = null,
        /** Configs returned by [getSupportedCameraConfigs] — empty simulates a degenerate device. */
        private val supportedCameraConfigs: List<CameraConfig> = emptyList(),
        /** When true, [getSupportedCameraConfigs] throws — simulates a JNI failure. */
        private val getSupportedCameraConfigsThrows: Boolean = false,
    ) : Session() {

        var startRecordingCount: Int = 0
            private set
        var stopRecordingCount: Int = 0
            private set
        var lastConfig: RecordingConfig? = null
            private set
        /** The last config passed to [setCameraConfig], or `null` if it was never called. */
        var lastSetCameraConfig: CameraConfig? = null
            private set

        override fun getPlaybackStatus(): PlaybackStatus = playbackStatus

        override fun startRecording(config: RecordingConfig?) {
            startRecordingCount += 1
            lastConfig = config
            val failFrom = failOnStartRecordingFromCall
            val ex = startRecordingException
            if (ex != null && failFrom != null && startRecordingCount >= failFrom) {
                throw ex.value
            }
        }

        override fun stopRecording() {
            stopRecordingCount += 1
            stopRecordingException?.let { throw it.value }
        }

        override fun getSupportedCameraConfigs(filter: CameraConfigFilter?): List<CameraConfig> {
            if (getSupportedCameraConfigsThrows) {
                throw IllegalStateException("simulated getSupportedCameraConfigs failure")
            }
            return supportedCameraConfigs
        }

        override fun setCameraConfig(cameraConfig: CameraConfig?) {
            lastSetCameraConfig = cameraConfig
        }
    }

    /**
     * Robolectric shadow for [CameraConfigFilter].
     *
     * Its `CameraConfigFilter(Session)` constructor and the chained `setFacingDirection` /
     * `setTargetFps` setters are JNI-backed (`nativeCreateCameraConfigFilter`, …). The shadow
     * replaces the constructor with a no-op and returns `this` from the fluent setters so the
     * [ARRecorder] code path can build a filter in pure JVM. The filter content itself is
     * irrelevant to the test — [FakeSession.getSupportedCameraConfigs] returns a fixed list.
     */
    @Implements(CameraConfigFilter::class)
    class ShadowCameraConfigFilter {

        @RealObject
        @JvmField
        var realFilter: CameraConfigFilter? = null

        @Suppress("unused")
        @Implementation
        fun __constructor__(session: Session) {
            // No-op — neutralises nativeCreateCameraConfigFilter.
        }

        @Implementation
        fun setFacingDirection(
            direction: CameraConfig.FacingDirection?,
        ): CameraConfigFilter = realFilter!!

        @Implementation
        fun setTargetFps(
            targetFps: java.util.EnumSet<CameraConfig.TargetFps>?,
        ): CameraConfigFilter = realFilter!!
    }

    /**
     * Robolectric shadow for [RecordingConfig].
     *
     * The production code calls `RecordingConfig(session)` (which would normally hit
     * `nativeCreateRecordingConfig`) followed by chained setters. The shadow replaces every
     * native-backed method with an in-memory record that the tests can inspect.
     */
    @Implements(RecordingConfig::class)
    class ShadowRecordingConfig {

        // The actual RecordingConfig object this shadow is attached to — wired by
        // Robolectric via @RealObject. Used to return `this` from fluent setters.
        @RealObject
        @JvmField
        var realConfig: RecordingConfig? = null

        var session: Session? = null
            private set
        var mp4Path: String? = null
            private set
        var autoStopOnPause: Boolean? = null
            private set
        var rotation: Int? = null
            private set

        @Suppress("unused") // Called by Robolectric via @Implementation.
        @Implementation
        fun __constructor__(session: Session) {
            this.session = session
            register(this)
        }

        @Implementation
        fun setMp4DatasetFilePath(path: String?): RecordingConfig {
            this.mp4Path = path
            return realConfig!!
        }

        @Implementation
        fun setAutoStopOnPause(autoStop: Boolean): RecordingConfig {
            this.autoStopOnPause = autoStop
            return realConfig!!
        }

        @Implementation
        fun setRecordingRotation(rotation: Int): RecordingConfig {
            this.rotation = rotation
            return realConfig!!
        }

        companion object {
            // Tracks the most recently constructed RecordingConfig instance, accessed by tests.
            @Volatile
            var lastInstance: ShadowRecordingConfig? = null
                private set

            fun reset() { lastInstance = null }

            private fun register(shadow: ShadowRecordingConfig) {
                lastInstance = shadow
            }
        }
    }
}
