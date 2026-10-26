package io.github.sceneview.demo.recording

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import io.github.sceneview.capture.CaptureOverlay
import io.github.sceneview.capture.SceneRecorder
import io.github.sceneview.capture.rememberSceneRecorder
import io.github.sceneview.demo.R
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
    if (recorder.overlay == null) recorder.overlay = watermark(label)
    return recorder
}

/**
 * [label] in the bottom-left corner, sized to the capture's short edge: a 1080 × 1920 video and a
 * 1080 × 2400 photo carry text of the same size.
 */
fun watermark(label: String): CaptureOverlay {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    return CaptureOverlay { canvas, width, height ->
        val shortEdge = minOf(width, height)
        paint.textSize = shortEdge * WATERMARK_TEXT_SHARE
        paint.setShadowLayer(paint.textSize / SHADOW_RADIUS_DIVISOR, 0f, 0f, SHADOW_COLOR)
        val margin = shortEdge * WATERMARK_MARGIN_SHARE
        canvas.drawText(label, margin, height - margin, paint)
    }
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
private const val SHADOW_RADIUS_DIVISOR = 6f
private const val SHADOW_COLOR = 0x99000000.toInt()
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
