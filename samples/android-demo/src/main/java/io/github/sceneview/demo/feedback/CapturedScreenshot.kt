package io.github.sceneview.demo.feedback

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import androidx.core.view.drawToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Permission-free screenshot of the app's own window for a bug report.
 *
 * Primary path: [PixelCopy.request] on the activity's window — reads back the
 * composited frame, so Filament's `SurfaceView` viewport is included on
 * modern devices. Fallback: software [drawToBitmap] on the decor view, where
 * a `SurfaceView` renders black — [CapturedScreenshot.mayMissSurfaceContent]
 * flags it so the UI can say so honestly.
 *
 * The flag is NOT keyed on the fallback alone: some compositors (the emulator's
 * gfxstream today, driver quirks tomorrow) return [PixelCopy.SUCCESS] while
 * leaving the `SurfaceView` layer out of the read-back — an `alpha == 0`
 * punch-through hole where the 3D viewport should be (#2654). Every capture is
 * therefore also sampled for such a hole, so the report never silently ships a
 * blank-viewport image without its warning.
 *
 * This captures ONLY this app's own content (never other apps or system UI
 * surfaces) — which is exactly why it needs no MediaProjection consent, no
 * foreground service, and no Play Store declaration.
 */
data class CapturedScreenshot(
    val bitmap: Bitmap,
    /** PNG on disk (under [feedbackCacheDir]) ready for a FileProvider share. */
    val file: File,
    /** True when the capture may not show the 3D viewport (a `SurfaceView`):
     *  either the software fallback was used, or the composited read-back came
     *  back with a transparent hole where a layer was skipped (#2654). */
    val mayMissSurfaceContent: Boolean,
) {
    /** `content://` URI for `ACTION_SEND` — matches the manifest's FileProvider. */
    fun contentUri(activity: Activity): Uri = FileProvider.getUriForFile(
        activity,
        "${activity.packageName}.fileprovider",
        file,
    )
}

/** Longest output edge — plenty for triage, keeps the PNG well under 1 MB. */
private const val SCREENSHOT_MAX_DIMENSION_PX = 1080

/**
 * Capture, downscale (longest edge ≤ 1080 px) and persist a screenshot of
 * [activity]'s window. Returns `null` when both capture paths fail (the
 * report then goes out text-only). Main-safe; encoding runs on IO.
 */
suspend fun captureBugReportScreenshot(activity: Activity): CapturedScreenshot? {
    var fallbackUsed = false
    val raw = capturePixelCopy(activity)
        ?: captureSoftware(activity)?.also { fallbackUsed = true }
        ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val scaled = downscale(raw, SCREENSHOT_MAX_DIMENSION_PX)
            val file = File(feedbackCacheDir(activity), "screenshot-${System.currentTimeMillis()}.png")
            file.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            // The fallback path is already flagged; a PixelCopy SUCCESS still
            // needs the transparent-hole probe — see the class KDoc (#2654).
            CapturedScreenshot(
                bitmap = scaled,
                file = file,
                mayMissSurfaceContent = fallbackUsed || hasTransparentHole(scaled),
            )
        }.getOrNull()
    }
}

/**
 * Fraction of sampled pixels that must be fully transparent before the capture
 * is deemed to be missing a composited layer. A correctly composited read-back
 * of an opaque app window contains NO `alpha == 0` pixels, so any meaningful
 * share of them means a layer (in practice the Filament `SurfaceView`) was
 * punched out of the frame. 5 % tolerates decoration edge cases while still
 * catching every real viewport-sized hole; a legitimately dark scene stays
 * opaque black (`alpha == 255`) and can never trip this (#2654).
 */
private const val TRANSPARENT_HOLE_MIN_FRACTION = 0.05f

/** Sampling stride, both axes — every 4th pixel is plenty at ≤ 1080 px. */
private const val TRANSPARENT_HOLE_SAMPLE_STEP = 4

/**
 * True when a meaningful share of [bitmap] is fully transparent (`alpha == 0`)
 * — the signature of a compositor that skipped a `SurfaceView` layer while
 * still reporting [PixelCopy.SUCCESS] (#2654). Strided sampling keeps this a
 * sub-millisecond check on the already-downscaled capture.
 */
@VisibleForTesting
internal fun hasTransparentHole(bitmap: Bitmap): Boolean {
    val row = IntArray(bitmap.width)
    var sampled = 0
    var transparent = 0
    var y = 0
    while (y < bitmap.height) {
        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        var x = 0
        while (x < row.size) {
            sampled++
            if (row[x] ushr 24 == 0) transparent++
            x += TRANSPARENT_HOLE_SAMPLE_STEP
        }
        y += TRANSPARENT_HOLE_SAMPLE_STEP
    }
    return sampled > 0 && transparent.toFloat() / sampled >= TRANSPARENT_HOLE_MIN_FRACTION
}

/** Hardware read-back of the composited window — includes SurfaceView content. */
private suspend fun capturePixelCopy(activity: Activity): Bitmap? =
    suspendCancellableCoroutine { continuation ->
        val decor = activity.window?.decorView
        if (decor == null || decor.width <= 0 || decor.height <= 0) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(
                activity.window,
                bitmap,
                { result ->
                    continuation.resume(bitmap.takeIf { result == PixelCopy.SUCCESS })
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (_: IllegalArgumentException) {
            // Window not yet attached / already destroyed.
            continuation.resume(null)
        }
    }

/** Software fallback — SurfaceView (the 3D viewport) comes out black. */
private fun captureSoftware(activity: Activity): Bitmap? = runCatching {
    activity.window?.decorView
        ?.takeIf { it.width > 0 && it.height > 0 }
        ?.drawToBitmap()
}.getOrNull()

private fun downscale(source: Bitmap, maxDimension: Int): Bitmap {
    val largest = maxOf(source.width, source.height)
    if (largest <= maxDimension) return source
    val scale = maxDimension.toFloat() / largest
    return Bitmap.createScaledBitmap(
        source,
        (source.width * scale).toInt().coerceAtLeast(1),
        (source.height * scale).toInt().coerceAtLeast(1),
        true,
    )
}
