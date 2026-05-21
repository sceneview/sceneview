package io.github.sceneview.ar.arcore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import com.google.ar.core.Frame
import java.io.ByteArrayOutputStream

/**
 * Decodes a `YUV_420_888` [Image] (typically obtained from [cameraImage]) into an upright,
 * `ARGB_8888` [Bitmap] suitable for `AugmentedImageDatabase.addImage` (#1553).
 *
 * ARCore's CPU camera image is always `YUV_420_888`, never `ARGB_8888`, so a captured camera
 * frame cannot be handed to the augmented-image database directly — `addImage` requires an
 * `ARGB_8888` bitmap. This extension performs the conversion CPU-side: it packs the three
 * YUV planes into the interleaved `NV21` layout, runs Android's [YuvImage] JPEG encoder, then
 * decodes the JPEG back into an `ARGB_8888` bitmap.
 *
 * The image is rotated by [rotationDegrees] so the result is upright regardless of device
 * orientation — pass the value from `frame.imageRotationDegrees()` (or `0` if the camera image
 * is already upright). `0`, `90`, `180` and `270` are the only meaningful values.
 *
 * This call performs non-trivial work (JPEG round-trip + a pixel-format conversion) and **must
 * be run off the main thread** — it is intended to be paired with the background image
 * processing that [RuntimeAugmentedImageDatabase.addImage] already requires.
 *
 * The passed [Image] is **not** closed by this method — the caller still owns it (close it via
 * `use { }` exactly as [cameraImage]'s contract requires).
 *
 * @param rotationDegrees Clockwise rotation applied to make the result upright. Must be one of
 *   `0`, `90`, `180`, `270`; any other value is treated as `0`.
 * @param jpegQuality JPEG quality (1..100) used for the intermediate encode. Augmented-image
 *   feature extraction is robust to mild compression, so the default `90` keeps the round-trip
 *   fast without hurting tracking quality.
 * @return an upright `ARGB_8888` bitmap, or `null` if the image is not `YUV_420_888`.
 */
fun Image.toArgbBitmap(rotationDegrees: Int = 0, jpegQuality: Int = DEFAULT_JPEG_QUALITY): Bitmap? {
    if (format != ImageFormat.YUV_420_888) return null

    val nv21 = yuv420ToNv21(this)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val jpegStream = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), jpegQuality.coerceIn(1, 100), jpegStream)
    val jpegBytes = jpegStream.toByteArray()

    val decoded = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
    // ARCore's augmented-image database requires ARGB_8888 — BitmapFactory may hand back an
    // RGB_565 bitmap on memory-constrained devices, so normalise unconditionally.
    val argb = if (decoded.config == Bitmap.Config.ARGB_8888) {
        decoded
    } else {
        decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
    }

    val normalized = normalizeRotationDegrees(rotationDegrees)
    if (normalized == 0) return argb

    val matrix = Matrix().apply { postRotate(normalized.toFloat()) }
    val rotated = Bitmap.createBitmap(argb, 0, 0, argb.width, argb.height, matrix, false)
    if (rotated != argb) argb.recycle()
    return rotated
}

/**
 * Captures the current AR camera frame as an upright `ARGB_8888` [Bitmap] — the on-device
 * "take a photo" primitive for the runtime augmented-image workflow (#1553).
 *
 * Combines [cameraImage] (CPU image acquisition) with [Image.toArgbBitmap] (YUV → ARGB
 * conversion) in one call, and always closes the acquired native [Image] so the caller cannot
 * leak it. Returns `null` when the camera image is not yet available (first frames after
 * resume) or when the format is unexpectedly not `YUV_420_888`.
 *
 * Like [Image.toArgbBitmap] this performs a JPEG round-trip and **must be run off the main
 * thread**. The returned bitmap is owned by the caller and should be `recycle()`d when no
 * longer needed.
 *
 * ```kotlin
 * onSessionUpdated = { _, frame ->
 *     if (shouldCapture) {
 *         val photo = withContext(Dispatchers.Default) { frame.captureCameraBitmap() }
 *         // photo is ready for AugmentedImageDatabase.addImage(name, photo)
 *     }
 * }
 * ```
 *
 * @param jpegQuality Forwarded to [Image.toArgbBitmap].
 * @see RuntimeAugmentedImageDatabase
 */
fun Frame.captureCameraBitmap(jpegQuality: Int = DEFAULT_JPEG_QUALITY): Bitmap? =
    cameraImage()?.use { it.toArgbBitmap(rotationDegrees = 0, jpegQuality = jpegQuality) }

/** Default JPEG quality for the intermediate YUV → ARGB encode in [Image.toArgbBitmap]. */
const val DEFAULT_JPEG_QUALITY: Int = 90

/**
 * Snaps an arbitrary clockwise rotation to the nearest valid quadrant (`0`, `90`, `180`,
 * `270`). Negative inputs and inputs ≥ 360 are normalised into `[0, 360)` first.
 *
 * Extracted as `internal` pure logic so the rotation contract can be unit-tested without an
 * Android [Image] / [Bitmap] (#1553).
 */
internal fun normalizeRotationDegrees(rotationDegrees: Int): Int {
    val wrapped = ((rotationDegrees % 360) + 360) % 360
    return when (wrapped) {
        in 45 until 135 -> 90
        in 135 until 225 -> 180
        in 225 until 315 -> 270
        else -> 0
    }
}

/**
 * Packs a `YUV_420_888` [Image]'s three planes into a single interleaved `NV21` byte array
 * (`Y` plane followed by interleaved `V`/`U`), the layout [YuvImage] requires.
 *
 * Handles arbitrary `rowStride` / `pixelStride` — the chroma planes of a `YUV_420_888` image
 * are frequently sub-sampled with a pixel stride of 2, so a naive flat copy corrupts colour.
 */
private fun yuv420ToNv21(image: Image): ByteArray {
    val width = image.width
    val height = image.height
    val ySize = width * height
    val nv21 = ByteArray(ySize + ySize / 2)

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    // Y plane — copy row by row to honour rowStride padding.
    val yBuffer = yPlane.buffer
    val yRowStride = yPlane.rowStride
    var pos = 0
    if (yRowStride == width) {
        yBuffer.get(nv21, 0, ySize)
        pos = ySize
    } else {
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, pos, width)
            pos += width
        }
    }

    // Interleaved V/U for NV21. Chroma planes are half-resolution in both dimensions.
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer
    val uvRowStride = uPlane.rowStride
    val uvPixelStride = uPlane.pixelStride
    val chromaHeight = height / 2
    val chromaWidth = width / 2
    for (row in 0 until chromaHeight) {
        val rowStart = row * uvRowStride
        for (col in 0 until chromaWidth) {
            val uvIndex = rowStart + col * uvPixelStride
            nv21[pos++] = vBuffer.get(uvIndex)
            nv21[pos++] = uBuffer.get(uvIndex)
        }
    }
    return nv21
}
