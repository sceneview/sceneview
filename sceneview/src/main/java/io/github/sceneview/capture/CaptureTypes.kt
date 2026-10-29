package io.github.sceneview.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File

/**
 * Draws over every photo and video frame a [SceneRecorder] captures — a watermark, a logo, a
 * caption. It is never drawn on the live view: the screen shows the scene, the shared file shows
 * the scene plus the overlay.
 *
 * ```kotlin
 * val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
 * recorder.overlay = CaptureOverlay { canvas, width, height ->
 *     paint.textSize = height / 40f
 *     canvas.drawText("Made with SceneView", width * 0.04f, height * 0.96f, paint)
 * }
 * ```
 *
 * [draw] receives a canvas the size of the captured image — the full-resolution photo, or the
 * encoded video frame — so size anything relative to [width] and [height]. On a video it runs on
 * a background thread once per frame: keep it allocation-free and do not touch views from it. A
 * photo taken while recording calls the same instance on the main thread at the same time, so
 * guard any state it mutates, such as a shared [android.graphics.Paint].
 *
 * Photos get the overlay on every API level. Videos get it on Android 10 (API 29) and newer;
 * older devices record the scene alone and report it in [SceneRecording.hasOverlay].
 */
fun interface CaptureOverlay {
    /** Draws the overlay on [canvas], a [width] × [height] pixel image. */
    fun draw(canvas: Canvas, width: Int, height: Int)
}

/**
 * The file format a [SceneRecorder.savePhoto] writes.
 *
 * @property mimeType  The MIME type to share the file under.
 * @property extension The file extension, without the dot.
 */
enum class PhotoFormat(
    val mimeType: String,
    val extension: String,
    internal val compressFormat: Bitmap.CompressFormat,
) {
    /** Lossy, small — the format to share. */
    JPEG("image/jpeg", "jpg", Bitmap.CompressFormat.JPEG),

    /** Lossless, larger. */
    PNG("image/png", "png", Bitmap.CompressFormat.PNG),
}

/**
 * A video a [SceneRecorder] has finished writing.
 *
 * @property file               The MP4 file — H.264 video, plus AAC audio when [hasAudio].
 * @property width              Video width, in pixels.
 * @property height             Video height, in pixels.
 * @property durationMillis     How long the recording ran, in milliseconds.
 * @property hasAudio           Whether the microphone was recorded.
 * @property reachedMaxDuration Whether it stopped by itself at [VideoSettings.maxDurationMillis].
 * @property hasOverlay         Whether the [CaptureOverlay] is drawn on the video.
 */
data class SceneRecording(
    val file: File,
    val width: Int,
    val height: Int,
    val durationMillis: Long,
    val hasAudio: Boolean,
    val reachedMaxDuration: Boolean,
    val hasOverlay: Boolean,
)
