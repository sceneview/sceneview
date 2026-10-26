package io.github.sceneview.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.sceneview.utils.SurfaceMirrorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Takes shareable photos and MP4 videos of a `SceneView` or `ARSceneView` — exactly what the scene
 * renders, AR camera feed included, and none of the Compose UI drawn over it.
 *
 * Hand its [surfaceMirrorer] to the scene, then capture:
 *
 * ```kotlin
 * val recorder = rememberSceneRecorder()
 * val scope = rememberCoroutineScope()
 * ARSceneView(surfaceMirrorer = recorder.surfaceMirrorer, ...)  // or SceneView(...)
 *
 * Button(onClick = { scope.launch { recorder.savePhoto() } }) { Text("Photo") }
 * Button(onClick = {
 *     if (recorder.isRecording) recorder.stopRecording() else recorder.startRecording()
 * }) { Text(if (recorder.isRecording) "Stop" else "Record") }
 * ```
 *
 * ### Photos
 * [takePhoto] copies the scene view's own pixels at full resolution with `PixelCopy` (a
 * `TextureView` scene is read with `TextureView.getBitmap`), then draws the [overlay] on the copy.
 * [savePhoto] does the same and writes a JPEG or PNG file.
 *
 * ### Videos
 * [startRecording] encodes H.264 in an MP4, 1080p at 30 fps by default, in the scene view's own
 * aspect ratio (see [VideoSettings]), optionally with the microphone and a maximum duration. It
 * uses [SurfaceMirrorer]: the scene is rendered a second time straight into the encoder, so there
 * is no MediaProjection consent dialog, no foreground service, and no system UI or Compose
 * overlay in the file. The [overlay] is drawn on every frame on Android 10 and newer.
 *
 * ### State
 * [isRecording], [elapsedMillis], [lastRecording] and [lastError] are Compose state: read them in
 * composition and the UI follows. [onRecordingFinished] reports a recording that ends by itself at
 * [VideoSettings.maxDurationMillis] as well as one ended by [stopRecording].
 *
 * ### Threading
 * Call [startRecording], [stopRecording], [takePhoto], [savePhoto] and [release] from the main
 * thread — the mirror's Filament swap chain is destroyed there.
 *
 * ### Files
 * Without an explicit file, captures go to the app's external files folder
 * (`Android/data/<package>/files/captures/`, or the internal files folder if there is none) — no
 * storage permission needed. Share them through a `FileProvider`, or copy them to `MediaStore`.
 *
 * @param context          Any context; the recorder keeps only the application context.
 * @param surfaceMirrorer  The mirrorer to pass to the scene. A new one by default.
 */
@Stable
class SceneRecorder internal constructor(
    context: Context,
    /** Pass to `SceneView(surfaceMirrorer = …)` or `ARSceneView(surfaceMirrorer = …)`. */
    val surfaceMirrorer: SurfaceMirrorer,
    private val sessionFactory: VideoSessionFactory,
    private val compositorFactory: OverlayCompositorFactory?,
    private val pixelSource: PixelSource,
    private val encoderSupports: (width: Int, height: Int) -> Boolean,
) {

    @JvmOverloads
    constructor(context: Context, surfaceMirrorer: SurfaceMirrorer = SurfaceMirrorer()) : this(
        context = context,
        surfaceMirrorer = surfaceMirrorer,
        sessionFactory = MediaRecorderSessionFactory(context.applicationContext ?: context),
        compositorFactory = defaultOverlayCompositorFactory(),
        pixelSource = ViewPixelSource,
        encoderSupports = ::isAvcEncoderSizeSupported,
    )

    private val appContext: Context = context.applicationContext ?: context

    /**
     * Drawn over every captured photo and video frame — a watermark hook. `null` (the default)
     * captures the scene alone. Read when a photo is taken or a recording starts.
     */
    var overlay: CaptureOverlay? = null

    /**
     * Called on the main thread when a recording ends — by [stopRecording], by
     * [VideoSettings.maxDurationMillis] or by [release] — with the finished recording, or `null`
     * when the encoder could not produce a file (then [lastError] says why).
     */
    var onRecordingFinished: ((SceneRecording?) -> Unit)? = null

    /** Whether a video is being recorded. Compose state. */
    var isRecording: Boolean by mutableStateOf(false)
        private set

    /**
     * How long the current recording has been running, in milliseconds, updated four times a
     * second; the final duration once it stops. Compose state.
     */
    var elapsedMillis: Long by mutableLongStateOf(0L)
        private set

    /** The last video successfully written. Compose state. */
    var lastRecording: SceneRecording? by mutableStateOf(null)
        private set

    /** Why the last capture failed, or `null` after a success. Compose state. */
    var lastError: Exception? by mutableStateOf(null)
        private set

    private class ActiveRecording(
        val file: File,
        val size: VideoSize,
        val session: VideoSession,
        val compositor: OverlayCompositor?,
        val mirroredSurface: Surface,
        val startedAt: Long,
        val hasAudio: Boolean,
    )

    private var active: ActiveRecording? = null
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val recording = active ?: return
            elapsedMillis = now() - recording.startedAt
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    private val maxDurationStop = Runnable { finish(reachedMaxDuration = true) }

    // ── Video ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Starts recording the scene to [file].
     *
     * Returns `false` — and sets [lastError] — when no scene is using [surfaceMirrorer] yet, when
     * the encoder cannot be set up, or when a recording is already running (that one keeps going,
     * and [lastError] is left alone).
     *
     * @param file     The MP4 to write. A new timestamped file under `captures/` by default.
     * @param settings Size, frame rate, bit rate, microphone and maximum duration.
     */
    @MainThread
    @JvmOverloads
    fun startRecording(
        file: File = newCaptureFile("mp4"),
        settings: VideoSettings = VideoSettings(),
    ): Boolean {
        if (active != null) return false
        val view = surfaceMirrorer.host?.renderView
        val size = view?.let {
            chooseVideoSize(
                it.width,
                it.height,
                settings.maxLongEdge,
                settings.maxShortEdge,
                encoderSupports,
            )
        }
        if (size == null) {
            lastError = IllegalStateException(
                if (view == null) {
                    "No scene renders through this recorder's surfaceMirrorer: pass it to " +
                        "SceneView(surfaceMirrorer = …) or ARSceneView(surfaceMirrorer = …)"
                } else {
                    "No video size for a ${view.width} x ${view.height} scene on this encoder"
                }
            )
            return false
        }
        val hasAudio = settings.recordAudio && appContext.checkSelfPermission(
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        var session: VideoSession? = null
        var compositor: OverlayCompositor? = null
        try {
            file.parentFile?.mkdirs()
            val request = VideoSessionRequest(file, size, settings.frameRate, settings.bitRate, hasAudio)
            session = sessionFactory.create(request)
            compositor = overlay?.let { compositorFactory?.create(size, session.inputSurface, it) }
            val mirroredSurface = compositor?.inputSurface ?: session.inputSurface
            session.start()
            surfaceMirrorer.startMirroring(mirroredSurface, width = size.width, height = size.height)
            active = ActiveRecording(
                file, size, session, compositor, mirroredSurface, now(), hasAudio
            )
        } catch (e: Exception) {
            compositor?.release()
            session?.let { runCatching { it.release() } }
            file.delete()
            lastError = e
            return false
        }
        isRecording = true
        elapsedMillis = 0L
        lastError = null
        handler.postDelayed(ticker, TICK_MILLIS)
        if (settings.maxDurationMillis > 0) {
            handler.postDelayed(maxDurationStop, settings.maxDurationMillis)
        }
        return true
    }

    /**
     * Stops the recording and finalises its file.
     *
     * @return the finished recording — also in [lastRecording] — or `null` when nothing was
     * recording or the encoder produced no playable file (then [lastError] says why and the
     * partial file is deleted).
     */
    @MainThread
    fun stopRecording(): SceneRecording? = finish(reachedMaxDuration = false)

    private fun finish(reachedMaxDuration: Boolean): SceneRecording? {
        val recording = active ?: return null
        active = null
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(maxDurationStop)
        val duration = now() - recording.startedAt
        // Stop feeding frames first, then the compositor, then the encoder: an encoder stopped
        // while a frame is still being drawn on its surface fails the whole file.
        surfaceMirrorer.stopMirroring(recording.mirroredSurface)
        recording.compositor?.release()
        val stopped = runCatching { recording.session.stop() }.getOrDefault(false)
        runCatching { recording.session.release() }

        isRecording = false
        elapsedMillis = duration
        val result = if (stopped && recording.file.length() > 0) {
            SceneRecording(
                file = recording.file,
                width = recording.size.width,
                height = recording.size.height,
                durationMillis = duration,
                hasAudio = recording.hasAudio,
                reachedMaxDuration = reachedMaxDuration,
                hasOverlay = recording.compositor != null,
            ).also { lastRecording = it }
        } else {
            recording.file.delete()
            lastError = IllegalStateException(
                "The encoder produced no video — was the scene rendering while recording?"
            )
            null
        }
        onRecordingFinished?.invoke(result)
        return result
    }

    // ── Photo ───────────────────────────────────────────────────────────────────────────────────

    /**
     * A full-resolution photo of the scene view, with the [overlay] drawn on it.
     *
     * @return the photo — yours to recycle — or `null` when no scene is using [surfaceMirrorer]
     * or its surface has nothing to copy yet.
     */
    @MainThread
    suspend fun takePhoto(): Bitmap? {
        val view = surfaceMirrorer.host?.renderView ?: return null
        val copy = pixelSource.copy(view) ?: return null
        val overlay = overlay ?: return copy
        val photo = if (copy.isMutable && copy.config == Bitmap.Config.ARGB_8888) {
            copy
        } else {
            copy.copy(Bitmap.Config.ARGB_8888, true).also { copy.recycle() }
        }
        overlay.draw(Canvas(photo), photo.width, photo.height)
        return photo
    }

    /**
     * Takes a photo (see [takePhoto]) and writes it to [file].
     *
     * @param file    The file to write. A new timestamped file under `captures/` by default.
     * @param format  JPEG (the default) or PNG.
     * @param quality JPEG quality, 0 to 100. Ignored for PNG.
     * @return [file], or `null` when there was nothing to capture or the write failed (then
     * [lastError] says why).
     */
    @MainThread
    @JvmOverloads
    suspend fun savePhoto(
        file: File? = null,
        format: PhotoFormat = PhotoFormat.JPEG,
        quality: Int = DEFAULT_JPEG_QUALITY,
    ): File? {
        require(quality in 0..100) { "quality must be in 0..100, got $quality" }
        val photo = takePhoto() ?: return null
        val target = file ?: newCaptureFile(format.extension)
        return try {
            withContext(Dispatchers.IO) {
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    check(photo.compress(format.compressFormat, quality, out)) {
                        "Bitmap.compress refused the photo"
                    }
                }
            }
            lastError = null
            target
        } catch (e: Exception) {
            target.delete()
            lastError = e
            null
        } finally {
            photo.recycle()
        }
    }

    // ── Files and lifecycle ─────────────────────────────────────────────────────────────────────

    /**
     * A new, not yet existing file named `scene_<date>_<time>_<millis>.<extension>` in the app's
     * `captures/` folder.
     */
    fun newCaptureFile(extension: String): File {
        val root = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(File(root, CAPTURE_FOLDER), "scene_$stamp.$extension")
    }

    /**
     * Stops any running recording (keeping its file) and the duration timers. The recorder stays
     * usable; [rememberSceneRecorder] calls this when it leaves composition.
     */
    @MainThread
    fun release() {
        finish(reachedMaxDuration = false)
        handler.removeCallbacksAndMessages(null)
    }

    private fun now() = SystemClock.uptimeMillis()
}

private const val TICK_MILLIS = 250L
private const val DEFAULT_JPEG_QUALITY = 95
private const val CAPTURE_FOLDER = "captures"

/**
 * Creates and remembers a [SceneRecorder], released when it leaves composition.
 *
 * ```kotlin
 * val recorder = rememberSceneRecorder()
 * ARSceneView(surfaceMirrorer = recorder.surfaceMirrorer, ...)
 * ```
 */
@Composable
fun rememberSceneRecorder(): SceneRecorder {
    val context = LocalContext.current.applicationContext
    val recorder = remember(context) { SceneRecorder(context) }
    DisposableEffect(recorder) { onDispose { recorder.release() } }
    return recorder
}
