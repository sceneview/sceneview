package io.github.sceneview.ar.depth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Temporal filter between ARCore's depth image and the occlusion texture.
 *
 * ARCore's smoothed depth (`Frame.acquireDepthImage16Bits`) is dense but not stable: on a thin
 * or untextured occluder (a white window mullion, a door frame) a texel can swing between the
 * occluder's distance and the background's from one depth image to the next, and drop to `0`
 * ("no depth") for a frame. Written straight into the z-buffer, each swing flips the virtual
 * model in front of or behind the occluder: the mask flickers at the depth rate.
 *
 * The filter keeps one value per texel and applies three rules on every **new** depth image:
 *  - **Same image, no work.** A depth image whose timestamp did not change returns `false` and
 *    the texture keeps what it holds — never cleared, never reset.
 *  - **Small changes are averaged** (relative change ≤ [jumpRatio]): an exponential moving
 *    average with weight [alpha] removes the per-frame noise on floors and walls.
 *  - **Large changes must be confirmed.** A jump beyond [jumpRatio] in either direction is only
 *    accepted when the next depth image jumps the same way. A one-image swing is ignored; a real
 *    change (an object moving in, the camera moving past an edge) lands one depth image late,
 *    ~33 ms at ARCore's 30 Hz.
 *  - **Holes are held.** A texel that comes back `0` keeps its last value for [holdFrames]
 *    images before it is released to `0`.
 *
 * Pure Kotlin, no ARCore or Filament: [ARCameraStream][io.github.sceneview.ar.camera.ARCameraStream]
 * feeds it the ARCore image buffer and uploads [output].
 */
internal class DepthOcclusionFilter(
    val alpha: Float = DEFAULT_ALPHA,
    val jumpRatio: Float = DEFAULT_JUMP_RATIO,
    val holdFrames: Int = DEFAULT_HOLD_FRAMES,
) {
    init {
        require(alpha in 0f..1f) { "alpha must be in 0..1, was $alpha" }
        require(jumpRatio > 0f) { "jumpRatio must be > 0, was $jumpRatio" }
        require(holdFrames >= 0) { "holdFrames must be >= 0, was $holdFrames" }
    }

    /** Width of the current state, in texels. `0` before the first image. */
    var width = 0
        private set

    /** Height of the current state, in texels. `0` before the first image. */
    var height = 0
        private set

    /** Timestamp of the last depth image accepted by [update]. */
    var lastTimestampNs = Long.MIN_VALUE
        private set

    private var state = FloatArray(0)
    private var age = ByteArray(0)
    private var pending = ByteArray(0)

    /**
     * Filtered depth, `width × height` unsigned 16-bit millimetres, little-endian, row-major:
     * the layout of ARCore's `DEPTH16` plane without its row padding — what an `RG8` texture
     * decodes as `r * 255 + g * 255 * 256`. Rewritten by every [update] that returns `true`.
     *
     * A texel with no depth is written [NO_DEPTH_MM], not `0`: the occlusion texture is sampled
     * bilinearly, and a `0` blended with a 2 m neighbour reads as a phantom 1 m occluder all
     * around every hole. Blended with 65.5 m it reads as far, which occludes nothing.
     */
    var output: ByteBuffer = ByteBuffer.allocateDirect(0)
        private set

    /** Fraction of texels with no depth (written [NO_DEPTH_MM]) in [output] after the last [update]. */
    var holeFraction = 0f
        private set

    /** Drops every held value: the next image is taken as is. */
    fun reset() {
        width = 0
        height = 0
        lastTimestampNs = Long.MIN_VALUE
    }

    /**
     * Feeds one depth image.
     *
     * @param timestampNs `Image.timestamp` of the depth image. The same value twice in a row is
     *   the same image: nothing is read and `false` is returned.
     * @param source the ARCore `DEPTH16` plane, unsigned 16-bit millimetres, little-endian.
     *   Read with absolute gets: its position and limit are left untouched.
     * @param rowStrideBytes the plane's row pitch, in bytes (≥ `2 × width`).
     * @return `true` when [output] holds new data to upload.
     */
    fun update(
        timestampNs: Long,
        source: ByteBuffer,
        width: Int,
        height: Int,
        rowStrideBytes: Int,
    ): Boolean {
        require(width > 0 && height > 0) { "empty depth image ${width}x$height" }
        require(rowStrideBytes >= width * 2) { "row stride $rowStrideBytes < ${width * 2}" }
        if (timestampNs == lastTimestampNs && width == this.width && height == this.height) {
            return false
        }
        val fresh = width != this.width || height != this.height
        if (fresh) resize(width, height)
        lastTimestampNs = timestampNs

        val src = source.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val out = output
        var holes = 0
        for (y in 0 until height) {
            val row = y * rowStrideBytes
            for (x in 0 until width) {
                val i = y * width + x
                val raw = (src.getShort(row + x * 2).toInt() and MAX_U16).toFloat()
                val value = if (fresh) raw else filter(i, raw)
                state[i] = value
                val mm = (value + HALF).toInt().coerceIn(0, MAX_DEPTH_MM)
                if (mm == 0) holes++
                out.putShort(i * 2, (if (mm == 0) NO_DEPTH_MM else mm).toShort())
            }
        }
        holeFraction = holes.toFloat() / (width * height)
        return true
    }

    /** One texel: [raw] is the new depth in mm (`0` = none), the state is the previous one. */
    private fun filter(i: Int, raw: Float): Float {
        val old = state[i]
        if (raw <= 0f) {
            pending[i] = 0
            if (old <= 0f) return 0f
            val held = age[i] + 1
            if (held > holdFrames) {
                age[i] = 0
                return 0f
            }
            age[i] = held.toByte()
            return old
        }
        age[i] = 0
        if (old <= 0f) {
            pending[i] = 0
            return raw
        }
        val change = (raw - old) / old
        if (abs(change) <= jumpRatio) {
            pending[i] = 0
            return old + (raw - old) * alpha
        }
        val direction: Byte = if (change > 0f) 1 else -1
        return if (pending[i] == direction) {
            pending[i] = 0
            raw
        } else {
            pending[i] = direction
            old
        }
    }

    private fun resize(width: Int, height: Int) {
        this.width = width
        this.height = height
        val count = width * height
        state = FloatArray(count)
        age = ByteArray(count)
        pending = ByteArray(count)
        output = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.LITTLE_ENDIAN)
    }

    companion object {
        /** Weight of a new sample within [DEFAULT_JUMP_RATIO]: half new, half history. */
        const val DEFAULT_ALPHA = 0.5f

        /** Relative change above which a sample is a jump that must be confirmed: 12 %. */
        const val DEFAULT_JUMP_RATIO = 0.12f

        /** Depth images a hole keeps its last value for: 4, ~130 ms at 30 Hz. */
        const val DEFAULT_HOLD_FRAMES = 4

        /**
         * What [output] holds where there is no depth: 65.535 m, read by the camera materials
         * as "no depth" (anything beyond 60 m), so the texel occludes nothing.
         */
        const val NO_DEPTH_MM = 0xFFFF

        private const val MAX_DEPTH_MM = 0xFFFE
        private const val MAX_U16 = 0xFFFF
        private const val HALF = 0.5f
    }
}
