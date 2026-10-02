package io.github.sceneview.ar.depth

import java.nio.ByteBuffer
import java.nio.ShortBuffer
import kotlin.math.abs

/**
 * A copy of one `YUV_420_888` camera image, owned by [MlDepthSession]'s pool so the ARCore image
 * can be closed on the render thread right away.
 */
internal class YuvCopy {
    var width = 0
    var height = 0
    var y = ByteArray(0)
    var u = ByteArray(0)
    var v = ByteArray(0)
    var yRowStride = 0
    var uvRowStride = 0
    var uvPixelStride = 0

    /** Copies [buffer]'s remaining bytes into [target], growing it if needed. */
    fun copyPlane(buffer: ByteBuffer, target: ByteArray): ByteArray {
        val size = buffer.remaining()
        val dst = if (target.size >= size) target else ByteArray(size)
        buffer.duplicate().get(dst, 0, size)
        return dst
    }
}

/** Pure pixel math of ML depth: camera image → network input, network output → metric map. */
internal object DepthImageMath {

    /**
     * Resamples a `YUV_420_888` image to [dstWidth] × [dstHeight] interleaved RGB (bilinear on
     * luma, nearest on chroma — chroma is half resolution and the network does not need more).
     * BT.601 full range, as ARCore's camera image is.
     */
    fun yuvToRgb(src: YuvCopy, dst: ByteArray, dstWidth: Int, dstHeight: Int) {
        val sx = src.width.toFloat() / dstWidth
        val sy = src.height.toFloat() / dstHeight
        var o = 0
        for (j in 0 until dstHeight) {
            val fy = ((j + 0.5f) * sy - 0.5f).coerceIn(0f, src.height - 1f)
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, src.height - 1)
            val ay = fy - y0
            val uvRow = (fy.toInt() shr 1) * src.uvRowStride
            for (i in 0 until dstWidth) {
                val fx = ((i + 0.5f) * sx - 0.5f).coerceIn(0f, src.width - 1f)
                val x0 = fx.toInt()
                val x1 = minOf(x0 + 1, src.width - 1)
                val ax = fx - x0
                val l00 = src.y[y0 * src.yRowStride + x0].toInt() and 0xFF
                val l01 = src.y[y0 * src.yRowStride + x1].toInt() and 0xFF
                val l10 = src.y[y1 * src.yRowStride + x0].toInt() and 0xFF
                val l11 = src.y[y1 * src.yRowStride + x1].toInt() and 0xFF
                val luma = (l00 * (1 - ax) + l01 * ax) * (1 - ay) + (l10 * (1 - ax) + l11 * ax) * ay
                val uvIndex = uvRow + (x0 shr 1) * src.uvPixelStride
                val cb = (src.u[uvIndex].toInt() and 0xFF) - 128
                val cr = (src.v[uvIndex].toInt() and 0xFF) - 128
                dst[o++] = clampByte(luma + 1.402f * cr)
                dst[o++] = clampByte(luma - 0.344136f * cb - 0.714136f * cr)
                dst[o++] = clampByte(luma + 1.772f * cb)
            }
        }
    }

    private fun clampByte(value: Float): Byte = value.toInt().coerceIn(0, 255).toByte()

    /** Limits of a written ML depth map. */
    data class Limits(
        val minDepthM: Float = 0.2f,
        val maxDepthM: Float = 8f,
        val extrapolation: Float = 2f,
        val maxRelativeGradient: Float = 0.05f,
        val maxRms: Double = 0.2,
    )

    /**
     * Turns the network output into millimetres and a 0–255 confidence.
     *
     * A pixel gets no depth (`0`) where the fit gives no positive depth, outside
     * `[minDepthM, maxDepthM]`, or further than [Limits.extrapolation]× outside the anchor
     * range. Its confidence is the product of three factors: the fit's RMS residual, how far it
     * extrapolates beyond the anchors, and the local depth gradient (depth edges are where
     * monocular networks are least reliable).
     *
     * @return how many pixels received a depth.
     */
    @Suppress("LongParameterList")
    fun writeMetric(
        map: FloatArray,
        width: Int,
        height: Int,
        scale: DepthScale?,
        outMm: ShortBuffer,
        outConfidence: ByteBuffer,
        limits: Limits = Limits(),
    ): Int {
        val lo = if (scale != null) maxOf(limits.minDepthM, scale.zMin / limits.extrapolation) else limits.minDepthM
        val hi = if (scale != null) minOf(limits.maxDepthM, scale.zMax * limits.extrapolation) else limits.maxDepthM
        val fitFactor = if (scale != null) {
            (1.0 - scale.rmsRelativeError / limits.maxRms).coerceIn(0.0, 1.0).toFloat()
        } else {
            1f
        }
        var valid = 0
        for (p in 0 until width * height) {
            val z = depthAt(map, p, scale)
            val ok = !z.isNaN() && z in lo..hi
            outMm.put(p, if (ok) (z * 1000f + 0.5f).toInt().coerceIn(1, 0xFFFF).toShort() else 0)
            if (!ok) {
                outConfidence.put(p, 0)
                continue
            }
            valid++
            val extrapolationFactor = when {
                scale == null -> 1f
                z < scale.zMin -> 1f - 0.5f * (scale.zMin - z) / (scale.zMin - lo).coerceAtLeast(1e-3f)
                z > scale.zMax -> 1f - 0.5f * (z - scale.zMax) / (hi - scale.zMax).coerceAtLeast(1e-3f)
                else -> 1f
            }
            val gradient = relativeGradient(map, width, height, p, scale, z)
            val gradientFactor = (1f - gradient / limits.maxRelativeGradient).coerceIn(0f, 1f)
            val conf = fitFactor * extrapolationFactor.coerceIn(0f, 1f) * (0.25f + 0.75f * gradientFactor)
            outConfidence.put(p, (conf * 255f + 0.5f).toInt().coerceIn(1, 255).toByte())
        }
        return valid
    }

    private fun depthAt(map: FloatArray, index: Int, scale: DepthScale?): Float =
        if (scale == null) map[index] else scale.depthMeters(map[index])

    private fun relativeGradient(
        map: FloatArray,
        width: Int,
        height: Int,
        p: Int,
        scale: DepthScale?,
        z: Float,
    ): Float {
        val x = p % width
        val y = p / width
        val right = if (x + 1 < width) depthAt(map, p + 1, scale) else z
        val down = if (y + 1 < height) depthAt(map, p + width, scale) else z
        val g = maxOf(abs(right - z), abs(down - z)) / z
        return if (g.isNaN()) 1f else g
    }
}
