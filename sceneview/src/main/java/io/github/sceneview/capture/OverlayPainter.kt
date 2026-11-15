package io.github.sceneview.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log

private const val TAG = "SceneRecorder"

/**
 * Draws a [CaptureOverlay] into a reused [bitmap], one video frame at a time, so that the overlay
 * can never cost the video:
 *
 * - the canvas is saved before [CaptureOverlay.draw] and restored after it, so a `translate` or
 *   `clipRect` left unrestored does not leak into the next frame;
 * - an overlay that throws (a `HARDWARE` bitmap on this software canvas, say) leaves a transparent
 *   bitmap: the frame is recorded with the scene alone, and the failure is logged once per
 *   recording, not once per frame.
 *
 * Not thread-safe: one compositor thread calls [paint].
 */
internal class OverlayPainter(
    private val overlay: CaptureOverlay,
    private val bitmap: Bitmap,
) {
    private val canvas = Canvas(bitmap)
    private var failureLogged = false

    /** Clears [bitmap] and draws the overlay on it. `false` when the overlay threw. */
    fun paint(): Boolean {
        bitmap.eraseColor(Color.TRANSPARENT)
        val saveCount = canvas.save()
        return try {
            overlay.draw(canvas, bitmap.width, bitmap.height)
            true
        } catch (e: RuntimeException) {
            if (!failureLogged) {
                failureLogged = true
                Log.w(TAG, "CaptureOverlay.draw failed, recording frames without the overlay", e)
            }
            // Whatever it drew before throwing is dropped with it.
            bitmap.eraseColor(Color.TRANSPARENT)
            false
        } finally {
            canvas.restoreToCount(saveCount)
        }
    }
}
