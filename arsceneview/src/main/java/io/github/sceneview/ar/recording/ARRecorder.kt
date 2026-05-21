package io.github.sceneview.ar.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.RecordingConfig
import com.google.ar.core.Session
import com.google.ar.core.Track
import com.google.ar.core.exceptions.RecordingFailedException
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.EnumSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Compose-friendly helper that records the full state of an [ARSceneView][io.github.sceneview.ar.ARSceneView]'s
 * underlying ARCore [Session] into an MP4 file.
 *
 * ### What ARCore actually records
 *
 * `Session.startRecording(RecordingConfig)` captures **everything** the session sees: the
 * camera image stream, IMU data, depth (if enabled), light estimation, plane updates,
 * anchors, augmented images, and the rest. The resulting MP4 is not a normal video — it is a
 * full session dataset that can be replayed 1:1 with [Session.setPlaybackDataset].
 *
 * ### Why this exists
 *
 * Recording / playback is the single most useful debugging tool ARCore offers: capture a real
 * outdoor / on-site session once, then iterate at the desk against the recording. Combined
 * with the [RerunBridge][io.github.sceneview.ar.rerun.RerunBridge], a recording becomes the
 * deterministic input of a Rerun-driven debugging loop. It also makes deterministic AR tests
 * possible: ship a fixture recording with the test suite and rerun it on every CI.
 *
 * ### Usage
 *
 * ```kotlin
 * val recorder = rememberARRecorder()
 * ARSceneView(
 *     onSessionUpdated = { session, frame -> recorder.attach(session) },
 *     content = { /* … */ }
 * )
 * Button(onClick = { recorder.start(File(context.getExternalFilesDir("ar-recordings"), "session.mp4")) }) {
 *     Text("Record")
 * }
 * Button(onClick = { recorder.stop() }) { Text("Stop") }
 * ```
 *
 * The recorder is non-fatal: any [RecordingFailedException] thrown by ARCore is captured into
 * [errorMessage] and surfaced via the [State.ERROR] state. The session itself keeps running.
 *
 * ### Threading
 *
 * `attach`, `start` and `stop` may be called from any thread — the underlying [AtomicReference]
 * makes the session pointer safe to publish across the render thread (where `onSessionUpdated`
 * runs) and the UI thread (where the buttons live). The actual ARCore calls are synchronous;
 * they're cheap (no blocking I/O), so calling from a UI handler is fine.
 *
 * @see com.google.ar.core.Session.startRecording
 * @see com.google.ar.core.Session.stopRecording
 * @see com.google.ar.core.Session.setPlaybackDataset
 */
public class ARRecorder {

    /** Lifecycle states for the recorder. */
    public enum class State {
        /** No recording in progress. */
        IDLE,

        /** ARCore is actively writing frames to [recordingFile]. */
        RECORDING,

        /**
         * The last [start] / [stop] call failed for a reason that is NOT a disk I/O fault —
         * typically a [RecordingFailedException] thrown because the session was in playback
         * mode, the config was invalid, or the dataset path was unwriteable at the OS level.
         * See [errorMessage] for details.
         */
        ERROR,

        /**
         * ARCore reported a disk I/O fault during recording — typically a full disk, an
         * unmounted external storage card, or a permission failure on the target [recordingFile].
         * Distinguished from [ERROR] so apps can offer the user a "clear cache and retry"
         * action instead of a generic retry. Surface mapped from ARCore's
         * [com.google.ar.core.RecordingStatus.IO_ERROR] (#1770).
         */
        IO_ERROR,
    }

    /**
     * Opaque handle returned by [addTrack] that pairs the track's UUID with its MIME type so
     * [recordTrack] can write data into the correct MP4 stream (#1770).
     *
     * Per Google's recording-with-extra-data flow, callers register a custom data track BEFORE
     * [start], then call [recordTrack] from `onSessionUpdated` to write annotation data
     * (ML detections, ground truth, custom sensor packets) into the same MP4 alongside the
     * camera image + IMU + ARCore data. The MP4 can later be replayed via
     * [ARSceneView][io.github.sceneview.ar.ARSceneView]'s `playbackDataset` parameter and the
     * data accessed through `Frame.getUpdatedTrackData(uuid)`.
     *
     * @property uuid The user-chosen UUID that identifies this track (used by
     *   `Frame.getUpdatedTrackData(uuid)` on the playback side).
     * @property mimeType The MP4 sample-description MIME type for the track (e.g.
     *   `"application/text"` for JSON detections, `"application/octet-stream"` for binary).
     */
    public data class TrackHandle(val uuid: UUID, val mimeType: String)

    private val sessionRef = AtomicReference<Session?>(null)

    /**
     * The session's camera config captured immediately before [start] swapped in a
     * higher-resolution one for `recordingResolution`. `null` when no swap happened — [stop]
     * only restores the config if this is non-null, so a [start] without `recordingResolution`
     * (or one where no matching config was found) leaves the session untouched on [stop].
     */
    private val previousCameraConfig = AtomicReference<CameraConfig?>(null)

    private var _state by mutableStateOf(State.IDLE)
    private var _recordingFile by mutableStateOf<File?>(null)
    private var _errorMessage by mutableStateOf<String?>(null)

    /** Current recorder state. Backed by a Compose `MutableState` — recompose-aware. */
    public val state: State
        get() = _state

    /**
     * The file currently being written (when [state] is [State.RECORDING]) or the most
     * recent file written (after [stop]). `null` until [start] succeeds.
     */
    public val recordingFile: File?
        get() = _recordingFile

    /**
     * Human-readable description of the last error, populated when [state] is [State.ERROR].
     * Cleared on the next successful [start].
     */
    public val errorMessage: String?
        get() = _errorMessage

    /**
     * Bind the recorder to an ARCore [Session]. Call this from `onSessionUpdated` (or any
     * place where you have a live session reference) — passing the same session repeatedly
     * is a no-op, so wiring it on every frame is fine.
     *
     * Without an attached session, [start] returns `false` and transitions to [State.ERROR].
     *
     * **Prefer [recordFrame].** This stateful split between `attach` + `start` was the only
     * AR side-channel in the codebase that didn't pass the session in per call —
     * [io.github.sceneview.ar.rerun.RerunBridge] uses the stateless `logFrame(session, frame)`
     * pattern. Mismatch noted in audit #876; the new [recordFrame] / [start] (Session, File)
     * overloads bring `ARRecorder` in line.
     */
    @Deprecated(
        "Prefer the stateless recordFrame(session) — passes the session per call (matches " +
            "RerunBridge.logFrame). attach() will be removed in v5.",
        ReplaceWith("recordFrame(session)"),
    )
    public fun attach(session: Session) {
        sessionRef.set(session)
    }

    /**
     * Stateless equivalent of [attach]: publishes the latest session reference each frame so
     * a subsequent [start] knows which session to record. Pass the same `session` you got from
     * `onSessionUpdated`. Calling this on every frame with the same session is a no-op (it's
     * an `AtomicReference.set`), matching [io.github.sceneview.ar.rerun.RerunBridge.logFrame].
     *
     * Side-effect (#1770): while [state] is [State.RECORDING], the call also polls
     * [Session.getRecordingStatus][com.google.ar.core.Session.getRecordingStatus]. If ARCore
     * reports [com.google.ar.core.RecordingStatus.IO_ERROR] (disk full, MP4 path unmounted,
     * permission revoked mid-recording), the recorder transitions to [State.IO_ERROR] and
     * populates [errorMessage] so the app can offer a "clear cache and retry" CTA instead of
     * silently swallowing the corrupt MP4.
     */
    public fun recordFrame(session: Session) {
        sessionRef.set(session)
        // Surface disk-IO faults as a distinct state — without this poll, ARCore's
        // RecordingStatus.IO_ERROR would only be observable via the next stopRecording() call
        // throwing, which is too late to alert the user (#1770).
        if (_state == State.RECORDING) {
            // Narrow the catch to ARCore's documented JNI failure modes (#1845): we swallow
            // a runtime failure of the JNI-backed getter (`Session.getRecordingStatus` can
            // throw if the native handle is closed concurrently) but never an Error — those
            // signal a process-wide problem the recorder cannot recover from.
            val status: com.google.ar.core.RecordingStatus? = try {
                session.recordingStatus
            } catch (e: RuntimeException) {
                null
            }
            if (status == com.google.ar.core.RecordingStatus.IO_ERROR) {
                // Write order matters (#1845): the previous code wrote [_errorMessage] first
                // then [_state], so a UI-thread reader that observed the writes individually
                // could see `state == RECORDING` paired with a non-null [errorMessage] — an
                // impossible pair per the public contract. Swap: write [_state] last AFTER
                // [_errorMessage] is fully populated, then guard the pair behind a single
                // [Snapshot.withMutableSnapshot] commit so Compose observers see the pair
                // atomically and never observe the in-between state.
                androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                    _errorMessage = "Recording I/O error — disk full, storage detached, or " +
                        "permission revoked. ARCore reported RecordingStatus.IO_ERROR."
                    _state = State.IO_ERROR
                }
            }
        }
    }

    /**
     * Detach the session reference. After this, [start] will fail until [recordFrame] (or the
     * deprecated [attach]) is called again.
     */
    public fun detach() {
        sessionRef.set(null)
    }

    // ── Custom data tracks (ML annotations, ground truth, sensor packets) — #1770 ────────────

    /**
     * Pending custom data tracks registered by [addTrack]. Drained into [RecordingConfig] in
     * [startInternal] before `session.startRecording()` runs — ARCore mandates the
     * `addTrack` call happen BEFORE `startRecording`, not after.
     */
    private val pendingTracks = AtomicReference<List<TrackHandle>>(emptyList())

    /**
     * Register a custom data track to be written into the next recording's MP4 (#1770).
     *
     * Wraps ARCore's [RecordingConfig.addTrack] + [com.google.ar.core.Frame.recordTrackData]
     * flow — the official way to ship side-channel data (ML detections, ground truth boxes,
     * custom sensor packets) inside the recording. On playback, the data is accessible via
     * `Frame.getUpdatedTrackData(handle.uuid)`.
     *
     * **Call BEFORE [start].** ARCore rejects new tracks once a recording is in progress.
     * Calling after [start] returns the same handle but the track will only take effect on the
     * next recording session. The handle is safe to retain across recordings — re-registering
     * the same UUID/MIME pair is idempotent.
     *
     * **Bounded to [MAX_PENDING_TRACKS] entries (#1845).** A caller that accidentally wires
     * `addTrack(UUID.randomUUID(), …)` into a recomposing block would otherwise leak handle
     * entries indefinitely — idempotent dedup is keyed on UUID equality, but unique UUIDs
     * bypass the check. Past the limit, [addTrack] raises [IllegalStateException] so the leak
     * surfaces at the call site instead of as a confusing native error at [start]. Use
     * [clearTracks] to drop registered tracks between recordings if your app needs a flexible
     * track schema. The default cap of 64 is well above ARCore's practical limit (the MP4 box
     * carries each track in its own sample-description entry — adding hundreds is a sign of
     * a programming error, not a legitimate use case).
     *
     * @param uuid The UUID that identifies this track. Must be unique within a recording.
     *   Re-use the same UUID on the playback side via `Frame.getUpdatedTrackData(uuid)`.
     * @param mimeType MP4 sample-description MIME type. Common choices:
     *   `"application/text"` for UTF-8 JSON, `"application/octet-stream"` for binary blobs.
     * @return A [TrackHandle] to pass to [recordTrack].
     * @throws IllegalStateException if more than [MAX_PENDING_TRACKS] distinct UUIDs have been
     *   registered without an intervening [clearTracks] or [stop] call.
     */
    public fun addTrack(uuid: UUID, mimeType: String): TrackHandle {
        val handle = TrackHandle(uuid, mimeType)
        // Idempotent: if the caller calls addTrack twice with the same uuid+mimeType, keep
        // only one entry — avoids native-side duplicate-key errors when the user accidentally
        // wires addTrack into a recomposing block.
        pendingTracks.updateAndGet { current ->
            when {
                current.any { it.uuid == uuid } -> current
                current.size >= MAX_PENDING_TRACKS -> throw IllegalStateException(
                    "ARRecorder.addTrack: refusing to register more than $MAX_PENDING_TRACKS " +
                        "custom data tracks (#1845). Likely cause: addTrack(UUID.randomUUID(), …) " +
                        "wired into a recomposing block — every recomposition leaks a new " +
                        "TrackHandle. Call clearTracks() between recordings or hoist the UUID " +
                        "out of the composition."
                )
                else -> current + handle
            }
        }
        return handle
    }

    /**
     * Drop every track previously registered via [addTrack] (#1845).
     *
     * Use between recordings when the track schema changes (e.g. a new ML model with a
     * different annotation MIME type) — without this, the next recording would carry both
     * the stale and the new tracks, and old [TrackHandle] instances would still be considered
     * "owned" by the recorder. Also useful in tests to reset state between runs.
     *
     * Has no effect on the underlying ARCore session — only the in-memory registry is
     * cleared. Existing recordings in progress are unaffected; the call is safe to make at
     * any [state]. Idempotent.
     */
    public fun clearTracks() {
        pendingTracks.set(emptyList())
    }

    /**
     * Write a single packet of data to a previously-registered custom track at the timestamp
     * of [frame] (#1770).
     *
     * Wraps [com.google.ar.core.Frame.recordTrackData]. Call from `onSessionUpdated` with the
     * frame parameter ARCore passes in — that's the live frame whose timestamp the packet
     * gets stamped with.
     *
     * No-op (returns `false`) when [handle] was not registered via [addTrack] or the recorder
     * is not currently in [State.RECORDING] (the track-data write would land in the abyss; we
     * skip the JNI call to keep the contract clear).
     *
     * @param handle A handle returned by [addTrack].
     * @param frame The current ARCore frame from `onSessionUpdated`.
     * @param data The packet payload. ARCore copies the bytes synchronously, so the buffer
     *   may be reused immediately after this call returns.
     * @return `true` if the write was issued to ARCore; `false` otherwise.
     */
    public fun recordTrack(
        handle: TrackHandle,
        frame: com.google.ar.core.Frame,
        data: ByteBuffer,
    ): Boolean {
        if (_state != State.RECORDING) return false
        // Handle ownership validation (#1845): the docstring promises a no-op when the handle
        // was not registered via [addTrack], but the implementation previously skipped this
        // check and forwarded the call to ARCore. ARCore raises an opaque error for an
        // unknown UUID — and worse, on a cross-recorder reuse (caller passes a [TrackHandle]
        // produced by a different [ARRecorder] instance) the write would land in whichever
        // recorder happened to be active. Validate that the UUID is in this recorder's
        // [pendingTracks] before issuing the JNI call.
        if (pendingTracks.get().none { it.uuid == handle.uuid }) {
            logWarning("recordTrack(${handle.uuid}) skipped — track not registered via addTrack")
            return false
        }
        return try {
            frame.recordTrackData(handle.uuid, data)
            true
        } catch (e: Exception) {
            logWarning("recordTrack(${handle.uuid}) failed: ${e.message}")
            false
        }
    }

    /**
     * Begin recording the attached session into [file]. The parent directory is created if
     * it doesn't already exist.
     *
     * Recording cannot run while the session is in playback mode — if it is, this returns
     * `false` and transitions to [State.ERROR].
     *
     * @param file               Absolute path of the destination MP4. Passed to
     *                           [RecordingConfig.setMp4DatasetFilePath] (no scoped-storage /
     *                           [android.net.Uri] wrapping needed).
     * @param recordingRotation  Optional [android.view.Display] rotation (`Surface.ROTATION_0` …
     *                           `Surface.ROTATION_270`) so the MP4 plays back upright when
     *                           captured in landscape. Pass the current display rotation from
     *                           the calling context (`display.rotation`). The value is converted
     *                           to degrees via [surfaceRotationToDegrees] before being handed to
     *                           [RecordingConfig.setRecordingRotation], which expects degrees
     *                           (`0`/`90`/`180`/`270`) — not the `Surface.ROTATION_*` ordinal
     *                           (`0`/`1`/`2`/`3`). Default `null` keeps ARCore's default of 0.
     * @param recordingResolution Optional target CPU-image resolution for the recording. ARCore
     *                           writes the **CPU image stream** into the MP4, whose default is
     *                           the lowest-resolution config the device exposes (often 640×480 on
     *                           Pixels). Pass e.g. `Size(1920, 1080)` to record at 1080p — the
     *                           recorder picks the supported BACK-facing, 30 FPS [CameraConfig]
     *                           whose pixel count is closest to the request. `null` (default)
     *                           leaves the session's existing camera config untouched. See #1065.
     *
     * Returns `true` if recording started, `false` if no session is attached, the session is
     * in playback mode, or ARCore refused the start. On failure, [state] is set to
     * [State.ERROR] and [errorMessage] holds the reason.
     */
    public fun start(
        file: File,
        recordingRotation: Int? = null,
        recordingResolution: Size? = null,
    ): Boolean {
        val session = sessionRef.get()
        if (session == null) {
            _errorMessage = "no AR session attached — call recordFrame(session) first"
            _state = State.ERROR
            return false
        }
        return startInternal(session, file, recordingRotation, recordingResolution)
    }

    /**
     * Stateless variant: pass the session explicitly. Equivalent to calling
     * [recordFrame] then [start], minus the runtime "no session attached" failure mode.
     * Mirrors the side-channel shape of
     * [io.github.sceneview.ar.rerun.RerunBridge.logFrame] which also takes the session per
     * call. Once a session is published this way, every subsequent [stop] uses the same
     * reference until [detach] is called or a new session is published.
     *
     * @param recordingResolution Optional target CPU-image resolution — see the [start] overload
     *                            above. `null` (default) keeps the session's camera config as-is.
     */
    public fun start(
        session: Session,
        file: File,
        recordingRotation: Int? = null,
        recordingResolution: Size? = null,
    ): Boolean {
        sessionRef.set(session)
        return startInternal(session, file, recordingRotation, recordingResolution)
    }

    private fun startInternal(
        session: Session,
        file: File,
        recordingRotation: Int?,
        recordingResolution: Size?,
    ): Boolean {
        // Recording is only allowed when the session is NOT in playback mode. PlaybackStatus
        // values: NONE (no playback dataset bound — recordable), OK / FINISHED / IO_ERROR
        // (playback active or terminated — never recordable).
        if (session.playbackStatus != PlaybackStatus.NONE) {
            _errorMessage = "Cannot record while session is in playback mode"
            _state = State.ERROR
            return false
        }
        return try {
            file.parentFile?.mkdirs()
            // ARCore writes the CPU image stream into the MP4 — its default is the
            // lowest-resolution config the device exposes (often 640×480 on Pixels, #1065).
            // When the caller asked for a specific resolution, swap in the closest-matching
            // BACK-facing 30 FPS camera config BEFORE startRecording(). ARCore mandates the
            // camera config be applied while the session is configured (which it is here —
            // the session has been created and configured by ARSceneView); applying it just
            // before startRecording() is the valid window.
            recordingResolution?.let { requested ->
                selectCameraConfig(session, requested)?.let { newConfig ->
                    // Remember the config in effect BEFORE the swap so stop() can restore it —
                    // otherwise the higher-res CPU image stream (and its perf cost) silently
                    // persists for the rest of the AR session (#1358). Captured via runCatching
                    // because Session.getCameraConfig is JNI-backed and must never abort the
                    // recording; on failure we just skip the restore.
                    previousCameraConfig.set(runCatching { session.cameraConfig }.getOrNull())
                    session.cameraConfig = newConfig
                }
            }
            val config = RecordingConfig(session)
                .setMp4DatasetFilePath(file.absolutePath)
                .setAutoStopOnPause(true)
            // ARCore's RecordingConfig.setRecordingRotation(int) expects the display rotation in
            // DEGREES (0/90/180/270). Callers pass a Surface.ROTATION_* constant (ordinals
            // 0/1/2/3) — forwarding it verbatim records a 90° capture as 1° and leaves the
            // dataset sideways (#1648). Convert to degrees before the ARCore call.
            recordingRotation?.let { config.setRecordingRotation(surfaceRotationToDegrees(it)) }
            // Wire any custom data tracks registered via addTrack() into the recording config
            // BEFORE startRecording(). ARCore mandates addTrack happen pre-start (#1770).
            for (handle in pendingTracks.get()) {
                config.addTrack(
                    Track(session)
                        .setId(handle.uuid)
                        .setMimeType(handle.mimeType)
                )
            }
            session.startRecording(config)
            _recordingFile = file
            _errorMessage = null
            _state = State.RECORDING
            true
        } catch (e: RecordingFailedException) {
            logWarning("startRecording failed: ${e.message}")
            // The camera-config swap happens before startRecording() — if the start fails,
            // restore the prior config so a failed recording doesn't leak the high-res
            // stream (#1358).
            restoreCameraConfig(session)
            _errorMessage = e.message ?: "RecordingFailedException"
            _state = State.ERROR
            false
        } catch (e: Exception) {
            logWarning("start failed: ${e.message}")
            restoreCameraConfig(session)
            _errorMessage = e.message ?: e::class.java.simpleName
            _state = State.ERROR
            false
        }
    }

    /**
     * Stop the current recording. Returns the file that was written, or `null` if no
     * recording was active. Safe to call when idle — it's a no-op.
     *
     * If [start] swapped the session's camera config to satisfy a `recordingResolution`
     * request, the prior config is restored here so the higher-res CPU image stream does not
     * outlive the recording (#1358).
     */
    public fun stop(): File? {
        val session = sessionRef.get() ?: run {
            _state = State.IDLE
            return _recordingFile
        }
        return try {
            session.stopRecording()
            _state = State.IDLE
            _recordingFile
        } catch (e: RecordingFailedException) {
            logWarning("stopRecording failed: ${e.message}")
            _errorMessage = e.message ?: "RecordingFailedException"
            _state = State.ERROR
            null
        } catch (e: Exception) {
            logWarning("stop failed: ${e.message}")
            _errorMessage = e.message ?: e::class.java.simpleName
            _state = State.ERROR
            null
        } finally {
            // Restore the camera config even if stopRecording() threw — leaking the
            // higher-res CPU image stream for the rest of the session is worse than the
            // stop failure itself (#1358).
            restoreCameraConfig(session)
        }
    }

    /**
     * Restore the camera config captured by [start] before it swapped in a higher-resolution
     * one for `recordingResolution`. No-op when no swap happened (the [AtomicReference] holds
     * `null`). The swapped-out config is cleared so a subsequent recording without
     * `recordingResolution` does not erroneously restore a stale config.
     *
     * Wrapped in `runCatching` because [Session.setCameraConfig] is JNI-backed; a failure to
     * restore must not surface as a recording error — the recording has already stopped.
     */
    private fun restoreCameraConfig(session: Session) {
        previousCameraConfig.getAndSet(null)?.let { prior ->
            runCatching { session.cameraConfig = prior }
                .onFailure { logWarning("restoring camera config failed: ${it.message}") }
        }
    }

    /** Reset the recorder to [State.IDLE], clearing any previous error. */
    public fun clearError() {
        if (_state == State.ERROR || _state == State.IO_ERROR) {
            _state = State.IDLE
            _errorMessage = null
        }
    }

    /**
     * Query the [session] for its supported BACK-facing, 30 FPS camera configs and pick the
     * one whose CPU image size best matches [requested]. Returns `null` if ARCore exposes no
     * matching config (degenerate device) so the caller leaves the existing config untouched.
     *
     * The catch is narrowed to [RuntimeException] (#1845) so JVM [Error]s — OOM, stack
     * overflow, native crash — propagate; the runtime failure mode we want to absorb is a
     * JNI-backed [Session.getSupportedCameraConfigs] throwing on a session in an odd state,
     * which is always a [RuntimeException] in ARCore. A failure here must never abort the
     * recording — it just falls back to ARCore's default config.
     */
    private fun selectCameraConfig(session: Session, requested: Size): CameraConfig? =
        try {
            val filter = CameraConfigFilter(session)
                .setFacingDirection(CameraConfig.FacingDirection.BACK)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
            pickCameraConfig(session.getSupportedCameraConfigs(filter), requested)
        } catch (e: RuntimeException) {
            null
        }

    private fun logWarning(msg: String) {
        try { Log.w(TAG, msg) } catch (_: RuntimeException) { /* unit test stub */ }
    }

    public companion object {
        private const val TAG = "ARRecorder"

        /**
         * Maximum number of custom data tracks the recorder will accept before refusing further
         * [addTrack] calls (#1845).
         *
         * The cap exists to make a leaky-recomposition pattern observable at the call site:
         * a `addTrack(UUID.randomUUID(), …)` wired into a Composable block would otherwise
         * accumulate one new handle per recomposition. The MP4 sample-description box could
         * hold thousands, but a SceneView recording with that many channels is invariably a
         * programming error — see Google's recording-with-extra-data flow at
         * [https://developers.google.com/ar/develop/java/recording-and-playback].
         *
         * `64` is comfortably above any legitimate use case (typically 1-5 tracks for
         * detections / ground truth / sensor packets) and well below ARCore's own resource
         * ceiling.
         */
        public const val MAX_PENDING_TRACKS: Int = 64

        /**
         * Convert an `android.view.Display` rotation — a [Surface.ROTATION_0] … [Surface.ROTATION_270]
         * constant whose underlying ordinals are `0`/`1`/`2`/`3` — to the **degrees** value
         * (`0`/`90`/`180`/`270`) expected by ARCore's [RecordingConfig.setRecordingRotation].
         *
         * Passing the `Surface.ROTATION_*` ordinal straight through records a 90° physical
         * rotation as `1°`, so the dataset plays back sideways (#1648). Any unrecognised value
         * falls back to `0` — the same default ARCore uses when no rotation is set.
         *
         * Pure function — no ARCore session interaction — so the mapping is unit-testable.
         */
        @JvmStatic
        public fun surfaceRotationToDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

        /**
         * Pick the [CameraConfig] from [configs] whose CPU [image size][CameraConfig.getImageSize]
         * is the closest match to [requested] (smallest absolute pixel-count difference). Ties are
         * broken towards the higher-resolution config, and a config exactly matching [requested]
         * always wins.
         *
         * Pure function — no ARCore session interaction — so the selection logic is unit-testable
         * against a mocked [Session.getSupportedCameraConfigs] result. Returns `null` for an empty
         * list so callers can leave the session's camera config untouched on a degenerate device.
         *
         * @see selectCameraConfig
         */
        @JvmStatic
        public fun pickCameraConfig(configs: List<CameraConfig>, requested: Size): CameraConfig? {
            if (configs.isEmpty()) return null
            val targetPixels = requested.width.toLong() * requested.height.toLong()
            return configs.minWithOrNull(
                compareBy<CameraConfig> { config ->
                    val size = config.imageSize
                    val pixels = size.width.toLong() * size.height.toLong()
                    kotlin.math.abs(pixels - targetPixels)
                }.thenByDescending { config ->
                    val size = config.imageSize
                    size.width.toLong() * size.height.toLong()
                },
            )
        }

        /**
         * Copy a recorded MP4 from app-private storage into the public **Downloads** collection
         * so it can be pulled via `adb pull /sdcard/Download/SceneView/<name>.mp4`, opened in
         * the Files app, or shared to Drive / cloud storage.
         *
         * Without this helper, recordings live in [Context.getExternalFilesDir] which is
         * sandboxed to the app — invisible to ADB and not selectable from any picker. The
         * canonical use case is the deterministic-AR-tests workflow: record a real session
         * once on a device, export it here, ship the MP4 as a fixture in
         * `src/androidTest/assets/ar-recordings/`, and replay it on every CI run via
         * [io.github.sceneview.ar.ARSceneView]'s `playbackDataset` parameter — no device with
         * AR hardware required for the regression run.
         *
         * On Android 10+ (API 29) uses [MediaStore.Downloads] and needs no runtime permission
         * for files this app authors. On older versions it falls back to the legacy public
         * Downloads directory; that path requires `WRITE_EXTERNAL_STORAGE` if your `minSdk` is
         * below 29, otherwise it relies on the legacy storage scope already granted.
         *
         * @param context      Any [Context] (application or activity).
         * @param recording    The MP4 file to export. Typically the value returned by [stop]
         *                     or [recordingFile].
         * @param displayName  Optional display name. Defaults to `recording.name`.
         * @param subdirectory Subfolder under `Downloads/`. Defaults to `"SceneView"`. Pass an
         *                     empty string to land directly in `Downloads/`.
         * @return The public content [Uri] (Q+) or `file:` Uri (P-) on success, or `null` if
         *         the copy failed.
         * @throws FileNotFoundException if [recording] does not exist.
         */
        @JvmStatic
        @JvmOverloads
        public fun exportToDownloads(
            context: Context,
            recording: File,
            displayName: String = recording.name,
            subdirectory: String = "SceneView",
        ): Uri? {
            if (!recording.isFile) {
                throw FileNotFoundException("Recording does not exist: ${recording.absolutePath}")
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    exportViaMediaStore(context, recording, displayName, subdirectory)
                } else {
                    exportLegacy(recording, displayName, subdirectory)
                }
            } catch (e: Exception) {
                try { Log.w(TAG, "exportToDownloads failed: ${e.message}") } catch (_: RuntimeException) {}
                null
            }
        }

        @RequiresApi(Build.VERSION_CODES.Q)
        private fun exportViaMediaStore(
            context: Context, src: File, name: String, subdirectory: String,
        ): Uri? {
            val resolver = context.contentResolver
            val relativePath = if (subdirectory.isBlank()) {
                Environment.DIRECTORY_DOWNLOADS
            } else {
                "${Environment.DIRECTORY_DOWNLOADS}/$subdirectory"
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                // IS_PENDING gates visibility until we close the OutputStream below — prevents
                // partial reads if the user opens the file picker mid-copy.
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                } ?: run {
                    resolver.delete(uri, null, null)
                    return null
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }

        @Suppress("DEPRECATION")
        private fun exportLegacy(src: File, name: String, subdirectory: String): Uri {
            val downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS,
            )
            val target = if (subdirectory.isBlank()) downloads else File(downloads, subdirectory)
            target.mkdirs()
            val dest = File(target, name)
            src.copyTo(dest, overwrite = true)
            return Uri.fromFile(dest)
        }
    }
}

/**
 * Creates and remembers an [ARRecorder] tied to the composable lifecycle.
 *
 * If the recorder is still recording when the composable leaves the tree, [ARRecorder.stop]
 * is invoked automatically so the MP4 is finalized cleanly. Without this hook, an
 * unfinished recording would be left in an undefined state on the disk.
 */
@Composable
public fun rememberARRecorder(): ARRecorder {
    val recorder = remember { ARRecorder() }
    DisposableEffect(recorder) {
        onDispose {
            if (recorder.state == ARRecorder.State.RECORDING) {
                recorder.stop()
            }
            recorder.detach()
        }
    }
    return recorder
}

/**
 * Compose [State] that mirrors the [Session]'s [PlaybackStatus] (#1770).
 *
 * Surfaces all four ARCore states:
 *  - [PlaybackStatus.NONE]   — no playback dataset is bound (live AR session).
 *  - [PlaybackStatus.OK]     — a dataset is bound and is currently replaying.
 *  - [PlaybackStatus.FINISHED] — the dataset has reached its end. Use this to trigger rewind /
 *    next-dataset / loop logic from app code — there is no other public signal for
 *    end-of-replay.
 *  - [PlaybackStatus.IO_ERROR] — the dataset MP4 is unreadable mid-replay (corruption,
 *    permission revoked, file deleted).
 *
 * Polls [Session.getPlaybackStatus][com.google.ar.core.Session.getPlaybackStatus] each
 * Compose frame via `withFrameNanos` so the value never lags the on-screen camera feed by
 * more than one frame. When `session` is `null`, returns [PlaybackStatus.NONE] — apps don't
 * need a null guard.
 *
 * Typical usage in an `ARRecordPlaybackDemo`-style screen:
 * ```kotlin
 * val status by rememberARPlaybackStatus(arSession)
 * LaunchedEffect(status) {
 *     if (status == PlaybackStatus.FINISHED) {
 *         loopCount++           // or: rewindRequested = true
 *     }
 * }
 * ```
 */
@Composable
public fun rememberARPlaybackStatus(session: Session?): androidx.compose.runtime.State<PlaybackStatus> {
    // Match the produceState idiom used by rememberCameraGeospatialPose / rememberEarthState
    // (#1844) — same per-frame poll, less ceremony, no orphan `LaunchedEffect` after the first
    // composition. The bare `try` (vs `runCatching`) avoids allocating a `Throwable` wrapper on
    // every failure frame — relevant when a corrupt dataset surfaces IO_ERROR every frame (#1846).
    return androidx.compose.runtime.produceState(
        initialValue = PlaybackStatus.NONE,
        key1 = session,
    ) {
        if (session == null) {
            value = PlaybackStatus.NONE
            return@produceState
        }
        while (true) {
            androidx.compose.runtime.withFrameNanos { _ ->
                // Narrow the catch to ARCore JNI failures (#1845): the only documented failure
                // mode of [Session.getPlaybackStatus] is a runtime exception when the session
                // is closed concurrently. JVM `Error`s propagate unchanged. Plain `try`/`catch`
                // (vs `runCatching`) avoids allocating a `Throwable` wrapper every IO_ERROR
                // frame (#1846).
                val current: PlaybackStatus = try {
                    session.playbackStatus
                } catch (e: RuntimeException) {
                    PlaybackStatus.NONE
                }
                if (value != current) value = current
            }
        }
    }
}
