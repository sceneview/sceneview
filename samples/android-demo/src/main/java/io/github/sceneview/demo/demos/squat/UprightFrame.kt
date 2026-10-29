package io.github.sceneview.demo.demos.squat

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How the camera's CPU image sits on screen, read from ARCore's own display transform.
 *
 * ARCore hands the CPU image in the sensor's orientation (landscape on a portrait phone)
 * and draws the camera feed rotated and centre-cropped into the view. Instead of guessing
 * that rotation from the display rotation and a convention, this reads it back from three
 * points ARCore itself transformed from `IMAGE_NORMALIZED` to `VIEW_NORMALIZED`: whatever
 * ARCore does to put the picture on screen, the pose model sees the same upright picture,
 * and its landmarks land on the person drawn under them.
 *
 * - [swapAxes]: the image's `u` axis runs along the view's `y` (a quarter turn).
 * - [flipX] / [flipY]: the upright image's `x` / `y` run against the raw axis they come from.
 * - [viewLeft]..[viewBottom]: where the whole upright image lands in view-normalised
 *   coordinates — outside `0..1` where the feed is cropped.
 */
data class UprightFrame(
    val swapAxes: Boolean,
    val flipX: Boolean,
    val flipY: Boolean,
    val viewLeft: Float,
    val viewTop: Float,
    val viewRight: Float,
    val viewBottom: Float,
) {
    /** Maps an upright-image-normalised x to view-normalised x. */
    fun toViewX(x: Float): Float = viewLeft + x * (viewRight - viewLeft)

    /** Maps an upright-image-normalised y to view-normalised y. */
    fun toViewY(y: Float): Float = viewTop + y * (viewBottom - viewTop)

    /** Upright image width for a raw image of [rawWidth] × [rawHeight]. */
    fun uprightWidth(rawWidth: Int, rawHeight: Int): Int = if (swapAxes) rawHeight else rawWidth

    /** Upright image height for a raw image of [rawWidth] × [rawHeight]. */
    fun uprightHeight(rawWidth: Int, rawHeight: Int): Int = if (swapAxes) rawWidth else rawHeight

    companion object {
        /**
         * Builds the frame from ARCore's transform of the raw image's corners `(0,0)`,
         * `(1,0)` and `(0,1)` — passed as `[x0, y0, x1, y1, x2, y2]` in view-normalised
         * coordinates, exactly what `Frame.transformCoordinates2d(IMAGE_NORMALIZED, …,
         * VIEW_NORMALIZED, …)` writes. Returns `null` while ARCore has no display geometry
         * yet (all three points collapse).
         */
        fun fromTransformedCorners(view: FloatArray): UprightFrame? {
            require(view.size >= 6) { "need three transformed points" }
            val ox = view[0]
            val oy = view[1]
            // Where the raw u axis and v axis point on screen.
            val ux = view[2] - ox
            val uy = view[3] - oy
            val vx = view[4] - ox
            val vy = view[5] - oy
            if (max(abs(ux) + abs(uy), abs(vx) + abs(vy)) < 1e-6f) return null

            val swap = abs(uy) > abs(ux)
            // The upright x axis follows view x; it comes from v when swapped, u otherwise.
            val flipX = if (swap) vx < 0f else ux < 0f
            val flipY = if (swap) uy < 0f else vy < 0f

            // Fourth corner (1,1) by linearity; the upright image's bounds on screen.
            val xs = floatArrayOf(ox, ox + ux, ox + vx, ox + ux + vx)
            val ys = floatArrayOf(oy, oy + uy, oy + vy, oy + uy + vy)
            return UprightFrame(
                swapAxes = swap,
                flipX = flipX,
                flipY = flipY,
                viewLeft = xs.min(),
                viewTop = ys.min(),
                viewRight = xs.max(),
                viewBottom = ys.max(),
            )
        }
    }
}

/**
 * Converts one ARCore `YUV_420_888` camera image straight to a packed RGB buffer, upright
 * and at half resolution, ready for a MediaPipe `ByteBufferImageBuilder(…, IMAGE_FORMAT_RGB)`.
 *
 * This replaces a YUV → NV21 → **JPEG encode → JPEG decode** round trip (the old demo's
 * `Image.toBitmap`). Half resolution is free here — 4:2:0 chroma already *is* half
 * resolution, so every output pixel reads one luma sample and one chroma pair with no
 * filtering — and loses nothing the pose model needs: the lite model's input is 256 px.
 * The rotation is applied while writing, so no rotated copy and no rotation hint.
 *
 * Honours each plane's row and pixel stride (the old NV21 path assumed a pixel stride of 1
 * on U/V, which is not what most ARCore devices deliver).
 *
 * @param out at least `(rawWidth / 2) * (rawHeight / 2) * 3` bytes; reused across frames.
 * @return the upright output size as `width to height`.
 */
@Suppress("LongParameterList")
fun yuv420ToUprightRgbHalf(
    y: ByteArray,
    yRowStride: Int,
    u: ByteArray,
    v: ByteArray,
    uvRowStride: Int,
    uvPixelStride: Int,
    rawWidth: Int,
    rawHeight: Int,
    frame: UprightFrame,
    out: ByteArray,
): Pair<Int, Int> {
    val halfW = rawWidth / 2
    val halfH = rawHeight / 2
    val outW = if (frame.swapAxes) halfH else halfW
    val outH = if (frame.swapAxes) halfW else halfH
    require(out.size >= outW * outH * 3) { "output buffer too small" }

    var o = 0
    for (j in 0 until outH) {
        for (i in 0 until outW) {
            // Upright (i, j) → raw half-resolution (ru, rv).
            val a = if (frame.flipX) outW - 1 - i else i
            val b = if (frame.flipY) outH - 1 - j else j
            val ru = if (frame.swapAxes) b else a
            val rv = if (frame.swapAxes) a else b

            val luma = (y[(rv * 2) * yRowStride + ru * 2].toInt() and 0xFF) - 16
            val c = rv * uvRowStride + ru * uvPixelStride
            val cb = (u[c].toInt() and 0xFF) - 128
            val cr = (v[c].toInt() and 0xFF) - 128

            // BT.601 limited range, fixed point (×1024).
            val l = 1192 * max(luma, 0)
            out[o++] = clampByte((l + 1634 * cr) shr 10)
            out[o++] = clampByte((l - 833 * cr - 400 * cb) shr 10)
            out[o++] = clampByte((l + 2066 * cb) shr 10)
        }
    }
    return outW to outH
}

private fun clampByte(value: Int): Byte = min(max(value, 0), 255).toByte()
