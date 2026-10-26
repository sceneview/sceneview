// The three parts of a capture that need a GPU, a codec or a window: the video encoder, the overlay
// compositor and the pixel copy. Each sits behind a small internal interface so SceneRecorder's
// lifecycle can be tested on the JVM with fakes.
package io.github.sceneview.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

private const val TAG = "SceneRecorder"

// ── Video encoder ────────────────────────────────────────────────────────────────────────────────

/** What a [VideoSession] is asked to encode. */
internal data class VideoSessionRequest(
    val file: File,
    val size: VideoSize,
    val frameRate: Int,
    val bitRate: Int,
    val recordAudio: Boolean,
)

/** One prepared encoder writing one file. */
internal interface VideoSession {
    /** The surface frames are drawn on. The same instance for the session's whole life. */
    val inputSurface: Surface

    fun start()

    /** Finalises the file. `false` when the encoder could not (no frame received, say). */
    fun stop(): Boolean

    fun release()
}

internal fun interface VideoSessionFactory {
    /** Returns a prepared session, or throws when the encoder cannot be set up. */
    fun create(request: VideoSessionRequest): VideoSession
}

/** H.264 + AAC in an MP4, through [MediaRecorder] with a surface video source. */
internal class MediaRecorderSessionFactory(private val context: Context) : VideoSessionFactory {

    override fun create(request: VideoSessionRequest): VideoSession {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.configure(request)
            recorder.prepare()
            // Captured once: each `getSurface()` call may return a new Java object, and a mirror is
            // identified by instance (see SurfaceMirrorer.stopMirroring).
            return MediaRecorderSession(recorder, recorder.surface)
        } catch (e: Exception) {
            recorder.release()
            throw e
        }
    }

    private fun MediaRecorder.configure(request: VideoSessionRequest) {
        if (request.recordAudio) setAudioSource(MediaRecorder.AudioSource.MIC)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        if (request.recordAudio) {
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44_100)
            setAudioEncodingBitRate(128_000)
            setAudioChannels(1)
        }
        setVideoSize(request.size.width, request.size.height)
        setVideoFrameRate(request.frameRate)
        setVideoEncodingBitRate(request.bitRate)
        setOutputFile(request.file.absolutePath)
    }

    private class MediaRecorderSession(
        private val recorder: MediaRecorder,
        override val inputSurface: Surface,
    ) : VideoSession {
        override fun start() = recorder.start()

        override fun stop(): Boolean = try {
            recorder.stop()
            true
        } catch (e: RuntimeException) {
            // MediaRecorder throws when it received no frame: there is no file to keep.
            Log.w(TAG, "MediaRecorder.stop failed", e)
            false
        }

        override fun release() = recorder.release()
    }
}

// ── Overlay compositor ───────────────────────────────────────────────────────────────────────────

/** Sits between the mirror and the encoder and draws the [CaptureOverlay] on every frame. */
internal interface OverlayCompositor {
    /** The surface the scene is mirrored to instead of the encoder's. */
    val inputSurface: Surface

    fun release()
}

internal fun interface OverlayCompositorFactory {
    fun create(size: VideoSize, output: Surface, overlay: CaptureOverlay): OverlayCompositor
}

/** The real compositor where the platform has one (API 29+), `null` below. */
internal fun defaultOverlayCompositorFactory(): OverlayCompositorFactory? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        OverlayCompositorFactory { size, output, overlay ->
            ImageReaderOverlayCompositor(size, output, overlay)
        }
    } else {
        null
    }

/**
 * The scene is mirrored into a GPU-only [ImageReader]; each frame is wrapped as a hardware
 * [Bitmap] (no copy to the CPU), drawn on the encoder surface's hardware canvas, and the overlay is
 * drawn over it. Runs on its own thread so the render thread never waits on it.
 *
 * Each image stays open until the next one has been drawn: a hardware canvas posts its frame
 * asynchronously, and the GPU may still be reading the previous buffer when the next one arrives.
 */
@RequiresApi(Build.VERSION_CODES.Q)
private class ImageReaderOverlayCompositor(
    size: VideoSize,
    private val output: Surface,
    private val overlay: CaptureOverlay,
) : OverlayCompositor {

    private val thread = HandlerThread("SceneRecorderOverlay").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader = ImageReader.newInstance(
        size.width,
        size.height,
        PixelFormat.RGBA_8888,
        MAX_IMAGES,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
    )
    private val colorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
    private var held: Image? = null
    private var heldFrame: Bitmap? = null
    private var released = false

    override val inputSurface: Surface = reader.surface

    init {
        reader.setOnImageAvailableListener({ drawLatest() }, handler)
    }

    private fun drawLatest() {
        if (released) return
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        val frame = image.hardwareBuffer?.use { Bitmap.wrapHardwareBuffer(it, colorSpace) }
        if (frame == null) {
            image.close()
            return
        }
        try {
            val canvas = output.lockHardwareCanvas()
            try {
                canvas.drawBitmap(frame, null, Rect(0, 0, canvas.width, canvas.height), null)
                overlay.draw(canvas, canvas.width, canvas.height)
            } finally {
                output.unlockCanvasAndPost(canvas)
            }
        } catch (e: RuntimeException) {
            // The encoder surface goes away while the recording stops.
            Log.w(TAG, "Overlay frame dropped", e)
        }
        heldFrame?.recycle()
        held?.close()
        held = image
        heldFrame = frame
    }

    override fun release() {
        handler.post {
            released = true
            reader.setOnImageAvailableListener(null, null)
            heldFrame?.recycle()
            held?.close()
            heldFrame = null
            held = null
            reader.close()
        }
        // quitSafely runs the cleanup above first; wait for it so no overlay frame is drawn on the
        // encoder surface once the caller stops the encoder.
        thread.quitSafely()
        thread.join(RELEASE_TIMEOUT_MS)
    }

    private companion object {
        const val MAX_IMAGES = 3
        const val RELEASE_TIMEOUT_MS = 500L
    }
}

// ── Pixel copy ───────────────────────────────────────────────────────────────────────────────────

internal fun interface PixelSource {
    /** A full-resolution copy of what [view] shows, or `null` when it has nothing to copy. */
    suspend fun copy(view: View): Bitmap?
}

/**
 * [PixelCopy] for a [SurfaceView] — the GPU buffer itself, camera feed included, never the views
 * drawn over it — and [TextureView.getBitmap] for a [TextureView].
 */
internal object ViewPixelSource : PixelSource {

    override suspend fun copy(view: View): Bitmap? = when (view) {
        is SurfaceView -> copySurface(view)
        is TextureView -> view.bitmap
        else -> null
    }

    private suspend fun copySurface(view: SurfaceView): Bitmap? {
        if (view.width <= 0 || view.height <= 0 || !view.holder.surface.isValid) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        // Cancelled callers: the bitmap is not recycled on cancellation, PixelCopy may still be
        // writing into it — it is recycled when the copy lands and nobody is waiting for it.
        return suspendCancellableCoroutine { continuation ->
            try {
                PixelCopy.request(
                    view,
                    bitmap,
                    { result ->
                        if (result == PixelCopy.SUCCESS) {
                            continuation.resume(bitmap) { _, copy, _ -> copy.recycle() }
                        } else {
                            Log.w(TAG, "PixelCopy failed with $result")
                            bitmap.recycle()
                            continuation.resume(null)
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
            } catch (e: IllegalArgumentException) {
                // The surface went away between the check and the request.
                Log.w(TAG, "PixelCopy refused the surface", e)
                bitmap.recycle()
                continuation.resume(null)
            }
        }
    }
}
