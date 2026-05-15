package io.github.sceneview.ar.recording

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
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
import com.google.ar.core.exceptions.RecordingFailedException
import java.io.File
import java.io.FileNotFoundException
import java.util.EnumSet
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

        /** The last [start] / [stop] call failed. See [errorMessage] for details. */
        ERROR,
    }

    private val sessionRef = AtomicReference<Session?>(null)

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
     */
    public fun recordFrame(session: Session) {
        sessionRef.set(session)
    }

    /**
     * Detach the session reference. After this, [start] will fail until [recordFrame] (or the
     * deprecated [attach]) is called again.
     */
    public fun detach() {
        sessionRef.set(null)
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
     *                           the calling context. Default `null` keeps ARCore's default of 0.
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
                selectCameraConfig(session, requested)?.let { session.cameraConfig = it }
            }
            val config = RecordingConfig(session)
                .setMp4DatasetFilePath(file.absolutePath)
                .setAutoStopOnPause(true)
            recordingRotation?.let { config.setRecordingRotation(it) }
            session.startRecording(config)
            _recordingFile = file
            _errorMessage = null
            _state = State.RECORDING
            true
        } catch (e: RecordingFailedException) {
            logWarning("startRecording failed: ${e.message}")
            _errorMessage = e.message ?: "RecordingFailedException"
            _state = State.ERROR
            false
        } catch (e: Exception) {
            logWarning("start failed: ${e.message}")
            _errorMessage = e.message ?: e::class.java.simpleName
            _state = State.ERROR
            false
        }
    }

    /**
     * Stop the current recording. Returns the file that was written, or `null` if no
     * recording was active. Safe to call when idle — it's a no-op.
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
        }
    }

    /** Reset the recorder to [State.IDLE], clearing any previous error. */
    public fun clearError() {
        if (_state == State.ERROR) {
            _state = State.IDLE
            _errorMessage = null
        }
    }

    /**
     * Query the [session] for its supported BACK-facing, 30 FPS camera configs and pick the
     * one whose CPU image size best matches [requested]. Returns `null` if ARCore exposes no
     * matching config (degenerate device) so the caller leaves the existing config untouched.
     *
     * Wrapped in `runCatching` because [Session.getSupportedCameraConfigs] is JNI-backed and
     * can throw on a session that is not in a queryable state — a failure here must never
     * abort the recording, it just falls back to ARCore's default config.
     */
    private fun selectCameraConfig(session: Session, requested: Size): CameraConfig? =
        runCatching {
            val filter = CameraConfigFilter(session)
                .setFacingDirection(CameraConfig.FacingDirection.BACK)
                .setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
            pickCameraConfig(session.getSupportedCameraConfigs(filter), requested)
        }.getOrNull()

    private fun logWarning(msg: String) {
        try { Log.w(TAG, msg) } catch (_: RuntimeException) { /* unit test stub */ }
    }

    public companion object {
        private const val TAG = "ARRecorder"

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
