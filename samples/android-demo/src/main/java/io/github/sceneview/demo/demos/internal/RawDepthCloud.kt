package io.github.sceneview.demo.demos.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Pure-Kotlin helpers for ARCore raw-depth point cloud generation.
 *
 * `Config.DepthMode.RAW_DEPTH_ONLY` exposes raw per-pixel depth alongside a companion
 * **confidence image** ([com.google.ar.core.Frame.acquireRawDepthConfidenceImage]).
 * Confidence is a single-channel image where each byte (0..255) encodes the confidence
 * for the corresponding raw-depth pixel — 0 = "unreliable", 255 = "high confidence".
 *
 * This object turns the (raw-depth, confidence) pair into a flat list of
 * [DepthPoint]s sub-sampled and filtered, ready to be drawn by a Compose `Canvas`
 * overlay. The two image buffers are read by a pure-Kotlin loop and the resulting
 * `IntArray` packs `[x, y, depthMm, argbColor]` quadruplets so the per-frame
 * allocation is a single primitive array.
 *
 * Extracted as `internal` top-level functions so they can be unit-tested without
 * ARCore on the classpath. See `RawDepthCloudTest`.
 *
 * Closes part of [#1715](https://github.com/sceneview/sceneview/issues/1715).
 */
internal object RawDepthCloud {

    /** Default sub-sample stride in depth-image pixels. Smaller → denser cloud. */
    const val DEFAULT_STRIDE: Int = 2

    /** Maximum points emitted per frame — caps the Compose Canvas draw cost. */
    const val DEFAULT_MAX_POINTS: Int = 4_000

    /** Default minimum confidence (0..255) to keep a point. */
    const val DEFAULT_CONFIDENCE_THRESHOLD: Int = 64

    /**
     * Packed per-point datum, laid out as 4 ints per point in a flat [IntArray] so
     * a per-frame point cloud is one allocation rather than thousands.
     *
     * `[x, y, depthMm, argbColor]`: the depth-image pixel coordinates (for screen-space
     * draw at fractional `x/width`, `y/height`), the raw depth in millimetres (so the
     * UI can render a per-point legend or filter further), and the false-color packed
     * ARGB (turbo-style ramp from [DepthVisualization.falseColorArgb]).
     */
    const val STRIDE_INTS: Int = 4

    /**
     * Build a packed `IntArray` of `[x, y, depthMm, argbColor]` quadruplets from a
     * raw-depth + confidence image pair.
     *
     * @param depthBytes            Raw-depth `Y_16` (little-endian, row-strided) buffer.
     * @param depthRowStrideBytes   Number of bytes between consecutive depth rows.
     * @param confidenceBytes       Raw-depth confidence single-channel buffer (1 byte per pixel).
     * @param confidenceRowStrideBytes Number of bytes between consecutive confidence rows.
     * @param width                 Depth-image pixel width.
     * @param height                Depth-image pixel height.
     * @param stride                Sub-sample stride (>=1). Defaults to [DEFAULT_STRIDE].
     * @param confidenceThreshold   Minimum confidence (0..255) to keep a point.
     * @param maxPoints             Cap on emitted points so the Compose draw stays cheap.
     * @param rotationDegrees       Clockwise rotation, a multiple of 90, applied to every emitted
     *                              `(x, y)` so the cloud is upright on screen (#3271 — same fix as
     *                              [DepthVisualization.depthBufferToArgb] / #3184). ARCore hands
     *                              back the raw-depth image in the **camera sensor** frame, which
     *                              does not follow the display: in portrait the image arrives 90°
     *                              off, so mapping `x/width, y/height` straight onto the screen
     *                              scatters points into the wrong region — reading as "almost
     *                              nothing visible" rather than a clean rotation. Use
     *                              [DepthVisualization.displayRotationToDegrees] to derive it from
     *                              the current display rotation. `0` (the default) preserves the
     *                              original, byte-for-byte behavior.
     *
     * @return Packed array length = `nPoints * STRIDE_INTS`. `x`/`y` are already rotated — the
     *         caller must size its overlay to [DepthVisualization.rotatedWidth] /
     *         [DepthVisualization.rotatedHeight] of ([width], [height], [rotationDegrees]), not to
     *         the raw `width` / `height`, on a quarter turn.
     */
    @Suppress("NestedBlockDepth") // y→x→confidence→depth nested loops are the canonical depth-cloud pattern
    fun buildCloud(
        depthBytes: ByteBuffer,
        depthRowStrideBytes: Int,
        confidenceBytes: ByteBuffer,
        confidenceRowStrideBytes: Int,
        width: Int,
        height: Int,
        stride: Int = DEFAULT_STRIDE,
        confidenceThreshold: Int = DEFAULT_CONFIDENCE_THRESHOLD,
        maxPoints: Int = DEFAULT_MAX_POINTS,
        rotationDegrees: Int = 0,
    ): IntArray {
        require(width > 0 && height > 0) { "raw-depth image must be non-empty (got $width x $height)" }
        require(stride >= 1) { "stride must be >= 1, was $stride" }
        require(depthRowStrideBytes >= width * 2) {
            "depthRowStrideBytes ($depthRowStrideBytes) must be >= width*2 (${width * 2})"
        }
        require(confidenceRowStrideBytes >= width) {
            "confidenceRowStrideBytes ($confidenceRowStrideBytes) must be >= width ($width)"
        }
        require(maxPoints >= 0) { "maxPoints must be >= 0, was $maxPoints" }
        val rotation = ((rotationDegrees % 360) + 360) % 360
        require(rotation % 90 == 0) {
            "rotationDegrees ($rotationDegrees) must be a multiple of 90"
        }
        val clampedThreshold = confidenceThreshold.coerceIn(0, 255)

        val prevDepthOrder = depthBytes.order()
        depthBytes.order(ByteOrder.LITTLE_ENDIAN)
        try {
            // Upper bound = number of sampled pixels at this stride.
            val sampledPerRow = (width + stride - 1) / stride
            val sampledRows = (height + stride - 1) / stride
            val capacityPoints = max(0, kotlin.math.min(sampledPerRow * sampledRows, maxPoints))
            val out = IntArray(capacityPoints * STRIDE_INTS)
            var n = 0

            var y = 0
            while (y < height && n < capacityPoints) {
                val depthRow = y * depthRowStrideBytes
                val confRow = y * confidenceRowStrideBytes
                var x = 0
                while (x < width && n < capacityPoints) {
                    val confIdx = confRow + x
                    val confidence = confidenceBytes.get(confIdx).toInt() and 0xFF
                    if (confidence >= clampedThreshold) {
                        val depthIdx = depthRow + x * 2
                        val lo = depthBytes.get(depthIdx).toInt() and 0xFF
                        val hi = depthBytes.get(depthIdx + 1).toInt() and 0xFF
                        val mm = (hi shl 8) or lo
                        if (mm > 0) {
                            val t = DepthVisualization.normalize(mm) ?: 0f
                            val color = DepthVisualization.falseColorArgb(t)
                            // Rotate (x, y) the same way DepthVisualization.depthBufferToArgb
                            // re-indexes pixels while writing (#3271 / #3184): the destination
                            // point lands in the *rotated* output frame, matching the display.
                            val (outX, outY) = when (rotation) {
                                90 -> (height - 1 - y) to x
                                180 -> (width - 1 - x) to (height - 1 - y)
                                270 -> y to (width - 1 - x)
                                else -> x to y
                            }
                            val base = n * STRIDE_INTS
                            out[base] = outX
                            out[base + 1] = outY
                            out[base + 2] = mm
                            out[base + 3] = color
                            n++
                        }
                    }
                    x += stride
                }
                y += stride
            }
            // Compact: if we filtered out many points, return the tightly-sized array so
            // callers don't iterate over zero padding.
            return if (n == capacityPoints) {
                out
            } else {
                out.copyOf(n * STRIDE_INTS)
            }
        } finally {
            depthBytes.order(prevDepthOrder)
        }
    }

    /** Number of points encoded in a packed cloud array. */
    fun pointCount(packed: IntArray): Int = packed.size / STRIDE_INTS

    /** X pixel coordinate of the [index]-th point in a packed cloud. */
    fun x(packed: IntArray, index: Int): Int = packed[index * STRIDE_INTS]

    /** Y pixel coordinate of the [index]-th point in a packed cloud. */
    fun y(packed: IntArray, index: Int): Int = packed[index * STRIDE_INTS + 1]

    /** Depth (mm) of the [index]-th point in a packed cloud. */
    fun depthMm(packed: IntArray, index: Int): Int = packed[index * STRIDE_INTS + 2]

    /** ARGB color of the [index]-th point in a packed cloud. */
    fun argb(packed: IntArray, index: Int): Int = packed[index * STRIDE_INTS + 3]
}
