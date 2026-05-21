package io.github.sceneview.demo.demos.internal

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin helpers for ARCore depth-image false-color visualization.
 *
 * The ARCore [com.google.ar.core.Frame.acquireDepthImage16Bits] (and
 * [com.google.ar.core.Frame.acquireRawDepthImage16Bits]) APIs return depth maps with
 * 16-bit unsigned millimetres per pixel — `Y_16` pixels in little-endian byte order.
 * A value of `0` means "unknown" (no depth data). This object turns that 1-channel
 * byte buffer into an `ARGB_8888` bitmap colored with a turbo-style false-color
 * ramp, suitable for a [android.graphics.Bitmap] overlay.
 *
 * Extracted as `internal` top-level functions so they can be unit-tested without
 * ARCore on the classpath — ARCore [com.google.ar.core.Image] is not mockable on
 * the JVM, but a plain [ByteBuffer] is. See `DepthVisualizationTest`.
 *
 * Closes part of [#1714](https://github.com/sceneview/sceneview/issues/1714).
 */
internal object DepthVisualization {

    /**
     * Minimum depth to color (millimetres). Below this distance, fall through to
     * `nearColorArgb`. ARCore's environment depth has a typical near range of
     * ~0.3 m (300 mm) — anything closer is either out-of-range or noise.
     */
    const val NEAR_MM_DEFAULT: Int = 300

    /**
     * Maximum depth to color (millimetres). The [Config.DepthMode.AUTOMATIC]
     * surface has a typical effective range up to ~5 m on most phones; pin the
     * upper bound there so the gradient uses the available dynamic range instead
     * of being squashed by a single far outlier.
     */
    const val FAR_MM_DEFAULT: Int = 5_000

    /** Sentinel used when ARCore has no depth datum for a given pixel. */
    private const val ARGB_UNKNOWN: Int = 0x00000000 // fully transparent

    /**
     * Compute the normalized depth ratio in `[0, 1]` for a raw 16-bit depth
     * sample, given the visualization window `[nearMm, farMm]`. Out-of-range
     * samples clamp to the edge; the special value `0` (ARCore "no data")
     * returns `null` so the caller can paint it transparent rather than as a
     * near-color pixel.
     */
    fun normalize(depthMm: Int, nearMm: Int = NEAR_MM_DEFAULT, farMm: Int = FAR_MM_DEFAULT): Float? {
        if (depthMm <= 0) return null
        require(farMm > nearMm) { "farMm ($farMm) must be > nearMm ($nearMm)" }
        val span = (farMm - nearMm).toFloat()
        val t = (depthMm - nearMm).toFloat() / span
        return when {
            t < 0f -> 0f
            t > 1f -> 1f
            else -> t
        }
    }

    /**
     * Turbo-inspired false-color ramp: t=0 → red (near), t=1 → blue (far). Three
     * piecewise-linear segments (red→yellow→green→blue) instead of the full 7-knot
     * Turbo, traded against the cost of running this on the UI thread once per
     * frame at 240×180. Visually it reads as "warm near, cool far" — same mental
     * model as Depth Lab's depth-effects shader.
     *
     * @return 0xAARRGGBB; alpha is always 0xFF for in-range samples.
     */
    fun falseColorArgb(t: Float): Int {
        val clamped = when {
            t < 0f -> 0f
            t > 1f -> 1f
            else -> t
        }
        val r: Int
        val g: Int
        val b: Int
        when {
            clamped < 1f / 3f -> {
                // Red → Yellow: ramp up green.
                val k = clamped * 3f
                r = 0xFF
                g = (k * 0xFF).toInt().coerceIn(0, 255)
                b = 0x00
            }
            clamped < 2f / 3f -> {
                // Yellow → Green → Cyan: drop red, raise blue.
                val k = (clamped - 1f / 3f) * 3f
                r = ((1f - k) * 0xFF).toInt().coerceIn(0, 255)
                g = 0xFF
                b = (k * 0xFF).toInt().coerceIn(0, 255)
            }
            else -> {
                // Cyan → Blue: drop green.
                val k = (clamped - 2f / 3f) * 3f
                r = 0x00
                g = ((1f - k) * 0xFF).toInt().coerceIn(0, 255)
                b = 0xFF
            }
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Convert a raw depth-image byte buffer (Y_16, little-endian, rowStride-aware)
     * into a packed `IntArray` of ARGB pixels of size `width * height`. Pixels with
     * a depth of 0 ("no data") become fully transparent so the camera feed shows
     * through them when the overlay is alpha-blended.
     *
     * @param depthBytes     Direct buffer with ARCore depth data; not consumed (callers
     *                       can re-read it).
     * @param width          Pixel width of the depth image.
     * @param height         Pixel height of the depth image.
     * @param rowStrideBytes Number of bytes between consecutive rows. ARCore returns
     *                       row-strided buffers, so this is `>= width * 2`.
     * @param nearMm         Near plane in millimetres for the false-color window.
     * @param farMm          Far plane in millimetres for the false-color window.
     */
    fun depthBufferToArgb(
        depthBytes: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
        nearMm: Int = NEAR_MM_DEFAULT,
        farMm: Int = FAR_MM_DEFAULT,
    ): IntArray {
        require(width > 0 && height > 0) { "depth image must be non-empty (got $width x $height)" }
        require(rowStrideBytes >= width * 2) {
            "rowStrideBytes ($rowStrideBytes) must be >= width*2 (${width * 2})"
        }
        val out = IntArray(width * height)
        // Defensive: ARCore hands us the buffer in little-endian on every platform we ship to,
        // but pinning the order makes the contract explicit and lets the JVM test feed an
        // identical buffer without depending on the host platform endianness.
        val previousOrder = depthBytes.order()
        depthBytes.order(ByteOrder.LITTLE_ENDIAN)
        try {
            for (y in 0 until height) {
                val rowStart = y * rowStrideBytes
                val outBase = y * width
                for (x in 0 until width) {
                    val byteIndex = rowStart + x * 2
                    // Read as little-endian unsigned 16-bit millimetres.
                    val lo = depthBytes.get(byteIndex).toInt() and 0xFF
                    val hi = depthBytes.get(byteIndex + 1).toInt() and 0xFF
                    val mm = (hi shl 8) or lo
                    val normalized = normalize(mm, nearMm, farMm)
                    out[outBase + x] = if (normalized == null) {
                        ARGB_UNKNOWN
                    } else {
                        falseColorArgb(normalized)
                    }
                }
            }
        } finally {
            depthBytes.order(previousOrder)
        }
        return out
    }

    /**
     * Clamp a UI slider value to the `[0, 1]` Compose contract. Hoisted here so the
     * demo composable doesn't repeat itself and the JVM test can pin the contract.
     */
    fun clampUnit(value: Float): Float = max(0f, min(1f, value))
}
