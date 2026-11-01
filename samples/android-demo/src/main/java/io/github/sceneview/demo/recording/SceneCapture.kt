package io.github.sceneview.demo.recording

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import io.github.sceneview.capture.CaptureOverlay
import io.github.sceneview.capture.SceneRecorder
import io.github.sceneview.capture.rememberSceneRecorder
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens
import java.io.File
import java.util.Locale

// The demo side of the shared capture actions (samples step 0): the SDK's `SceneRecorder` takes
// the photo or records the video; this file only names the watermark and hands the file to
// another app.

/** MIME type of the videos [SceneRecorder] writes. */
const val VIDEO_MIME = "video/mp4"

/**
 * A [SceneRecorder] whose captures carry the demo's watermark. Pass its `surfaceMirrorer` to the
 * scene and the recorder itself to `DemoScaffold(recorder = …)`.
 */
@Composable
fun rememberDemoSceneRecorder(): SceneRecorder {
    val recorder = rememberSceneRecorder()
    val label = stringResource(R.string.demo_capture_watermark)
    val overlay = remember(label) { watermark(label) }
    // Set after composition, not during it, and again when the locale changes the label.
    SideEffect { recorder.overlay = overlay }
    return recorder
}

/**
 * [label] in the bottom-left corner, sized to the capture's short edge: a 1080 × 1920 video and a
 * 1080 × 2400 photo carry text of the same size.
 *
 * White (`on-glass`) on a pill of `chrome-scrim`: a capture's ground is whatever was behind the
 * scene — the light theme's near-white grid, a dark stage, a camera frame — and white alone
 * disappears on the light one. The pill reads on all of them, so it is not themed.
 */
fun watermark(label: String): CaptureOverlay {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SceneViewTokens.Glass.onGlass.toArgb()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SceneViewTokens.Glass.scrim.toArgb() }
    val pill = RectF()
    // A photo taken while recording draws on the main thread while the encoder draws a frame.
    return CaptureOverlay { canvas, width, height ->
        synchronized(paint) {
            val shortEdge = minOf(width, height)
            paint.textSize = shortEdge * WATERMARK_TEXT_SHARE
            val margin = shortEdge * WATERMARK_MARGIN_SHARE
            watermarkPill(paint.measureText(label), paint.textSize, margin, height.toFloat(), pill)
            canvas.drawRoundRect(pill, pill.height() / 2f, pill.height() / 2f, ground)
            canvas.drawText(label, margin, height - margin, paint)
        }
    }
}

/**
 * The pill behind a watermark of [textWidth] × [textSize] whose baseline sits [margin] above the
 * bottom of a capture [height] tall, written into [out]: the text, padded round by half its size
 * sideways and a third of it above and below.
 */
internal fun watermarkPill(textWidth: Float, textSize: Float, margin: Float, height: Float, out: RectF): RectF {
    val padX = textSize * PILL_PAD_X_SHARE
    val padY = textSize * PILL_PAD_Y_SHARE
    val baseline = height - margin
    // Cap height above the baseline, descenders below it.
    out.set(
        margin - padX,
        baseline - textSize * CAP_SHARE - padY,
        margin + textWidth + padX,
        baseline + textSize * DESCENT_SHARE + padY,
    )
    return out
}

/** `m:ss` for an elapsed time in milliseconds. */
fun formatElapsed(millis: Long): String {
    val seconds = millis / MILLIS_PER_SECOND
    return "%d:%02d".format(Locale.US, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

/** Opens [file] in the app the device has for [mimeType]. Returns whether one took it. */
fun openCapture(context: Context, file: File, mimeType: String): Boolean = runCatching {
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(captureUri(context, file), mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}.isSuccess

/** Offers [file] to the share sheet. Returns whether the sheet opened. */
fun shareCapture(context: Context, file: File, mimeType: String): Boolean = runCatching {
    val send = Intent(Intent.ACTION_SEND)
        .setType(mimeType)
        .putExtra(Intent.EXTRA_STREAM, captureUri(context, file))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(
        Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}.isSuccess

/** The `content://` URI the app's FileProvider serves [file] under (`captures/`). */
private fun captureUri(context: Context, file: File) =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

private const val WATERMARK_TEXT_SHARE = 0.04f
private const val WATERMARK_MARGIN_SHARE = 0.04f
private const val PILL_PAD_X_SHARE = 0.5f
private const val PILL_PAD_Y_SHARE = 0.35f
private const val CAP_SHARE = 0.75f
private const val DESCENT_SHARE = 0.2f
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
