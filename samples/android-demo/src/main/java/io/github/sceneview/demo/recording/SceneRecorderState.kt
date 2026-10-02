package io.github.sceneview.demo.recording

import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import io.github.sceneview.utils.SurfaceMirrorer
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The shared Record action's state (samples step 0): records what a demo's scene renders to an
 * MP4 **in-app**, with no MediaProjection (#2626). It used to be the `video-recording` demo of
 * its own; it is now an action [io.github.sceneview.demo.DemoScaffold] offers on any demo that
 * hands it the [SurfaceMirrorer] its `SceneView` renders through.
 *
 * The scene side stays two lines — `rememberSurfaceMirrorer()` +
 * `SceneView(surfaceMirrorer = ...)`. Record points a [MediaRecorder]'s input surface at the
 * scene with [SurfaceMirrorer.startMirroring]; Stop tears it down with `stopMirroring` +
 * `recorder.stop()`. What lands in the MP4 is exactly what Filament rendered and none of the
 * Compose UI, because only the scene's frames are mirrored. No consent dialog, no
 * `mediaProjection` foreground service. Files land in the app's external files dir
 * (`Android/data/<pkg>/files/recordings/`), so no storage permission is needed.
 */
@Stable
class SceneRecorderState internal constructor(
    private val context: Context,
    private val mirrorer: SurfaceMirrorer,
) {
    private var session by mutableStateOf<RecordingSession?>(null)

    /** Whether a recording is running. */
    val isRecording: Boolean get() = session != null

    /** Seconds since the running recording started; 0 when idle. */
    var elapsedSeconds by mutableLongStateOf(0L)
        internal set

    /** The last MP4 that finished cleanly, or `null`. */
    var lastSaved by mutableStateOf<File?>(null)
        private set

    /** Whether the last start, stop, play or share failed. */
    var failed by mutableStateOf(false)
        private set

    /** Starts recording; a no-op while one is running. Returns whether it started. */
    fun start(): Boolean {
        if (session != null) return true
        session = startRecording(context, mirrorer)
        failed = session == null
        return !failed
    }

    /** Stops the running recording and keeps the MP4 when it is valid. */
    fun stop() {
        val active = session ?: return
        session = null
        failed = !stopRecording(mirrorer, active)
        if (!failed) lastSaved = active.outputFile
    }

    /** Opens the last saved MP4 in the device's video player. */
    fun play() {
        val uri = lastSavedUri() ?: run { failed = true; return }
        failed = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, VIDEO_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isFailure
    }

    /** The content URI of [lastSaved], served by the app's FileProvider, or `null`. */
    fun lastSavedUri(): Uri? = lastSaved?.let { file ->
        runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    internal fun release() {
        session?.let { stopRecording(mirrorer, it) }
        session = null
    }
}

/** Remembers the Record action's state for [mirrorer]; leaving mid-recording finalizes the file. */
@Composable
fun rememberSceneRecorderState(mirrorer: SurfaceMirrorer): SceneRecorderState {
    val context = LocalContext.current
    val state = remember(mirrorer) { SceneRecorderState(context.applicationContext, mirrorer) }
    DisposableEffect(state) { onDispose { state.release() } }
    LaunchedEffect(state.isRecording) {
        state.elapsedSeconds = 0L
        val started = SystemClock.elapsedRealtime()
        while (state.isRecording) {
            state.elapsedSeconds = (SystemClock.elapsedRealtime() - started) / MILLIS_PER_SECOND
            delay(ELAPSED_TICK_MILLIS)
        }
    }
    return state
}

/** `m:ss` for the running recording's elapsed time. */
fun formatElapsed(seconds: Long): String =
    "%d:%02d".format(Locale.US, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)

/**
 * An in-flight recording — the [MediaRecorder], its captured input [android.view.Surface],
 * and the MP4 it writes.
 *
 * The surface is captured **once**: `MediaRecorder.getSurface()` may return a new Java
 * object per call (wrapping the same native surface), and `SurfaceMirrorer` identifies
 * mirrors by [android.view.Surface] instance — `stopMirroring` must receive the same
 * instance `startMirroring` did.
 */
private data class RecordingSession(
    val recorder: MediaRecorder,
    val surface: android.view.Surface,
    val outputFile: File,
)

/**
 * Configures a video-only [MediaRecorder] (720p H.264 MP4, no audio → no permission),
 * mirrors the scene onto its input surface, and starts it.
 *
 * Returns `null` when the recorder fails to prepare/start (e.g. emulator without a
 * hardware encoder) — the demo stays usable, the action simply doesn't latch.
 */
private fun startRecording(
    context: Context,
    surfaceMirrorer: SurfaceMirrorer,
): RecordingSession? {
    val externalDir = context.getExternalFilesDir(null) ?: return null
    val outputDir = File(externalDir, "recordings")
    if (!outputDir.isDirectory && !outputDir.mkdirs()) return null
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
    val outputFile = File(outputDir, "scene_$timestamp.mp4")

    @Suppress("DEPRECATION")
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        MediaRecorder()
    }
    // Captured before the try so the failure path stops exactly the surface THIS call
    // started — never sibling mirrors another recording may own (SurfaceMirrorer is
    // multi-surface, and stopping all of them here would tear down unrelated captures).
    var startedSurface: android.view.Surface? = null
    return runCatching {
        recorder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
            setVideoEncodingBitRate(VIDEO_BIT_RATE)
            setVideoFrameRate(VIDEO_FRAME_RATE)
            setOutputFile(outputFile.absolutePath)
            prepare()
        }
        // recorder.surface is only valid after prepare(). Capture it ONCE — see
        // [RecordingSession] — and letterbox the scene into the 720p frame.
        val recorderSurface = recorder.surface
        startedSurface = recorderSurface
        surfaceMirrorer.startMirroring(recorderSurface, width = VIDEO_WIDTH, height = VIDEO_HEIGHT)
        recorder.start()
        RecordingSession(recorder, recorderSurface, outputFile)
    }.getOrElse { e ->
        Log.e(TAG, "Failed to start recording", e)
        // Stop only the surface we started in this call — not every mirrored surface.
        startedSurface?.let { surfaceMirrorer.stopMirroring(it) }
        runCatching { recorder.release() }
        outputFile.delete()
        null
    }
}

/** Stops mirroring first (idempotent), then finalizes the MP4. */
private fun stopRecording(surfaceMirrorer: SurfaceMirrorer, session: RecordingSession): Boolean {
    runCatching { surfaceMirrorer.stopMirroring(session.surface) }
    val stopped = runCatching { session.recorder.stop() }
        .onFailure { e -> Log.e(TAG, "Failed to stop recorder", e) }
    runCatching { session.recorder.release() }
    return stopped.isSuccess && session.outputFile.length() > 0L
}

/** MIME type of the recorded file. */
const val VIDEO_MIME = "video/mp4"

private const val TAG = "SceneRecorder"
private const val VIDEO_WIDTH = 1280
private const val VIDEO_HEIGHT = 720
private const val VIDEO_BIT_RATE = 8_000_000
private const val VIDEO_FRAME_RATE = 30
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val ELAPSED_TICK_MILLIS = 250L
