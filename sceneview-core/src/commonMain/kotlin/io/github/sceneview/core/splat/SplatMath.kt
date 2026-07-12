package io.github.sceneview.core.splat

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Activation math shared by the PLY and SPZ parsers so both emit identical [SplatCloud] semantics.
 */
internal object SplatMath {

    /** Zeroth-order spherical-harmonics basis constant `0.5 * sqrt(1/pi)` used for the DC color term. */
    const val SH_C0 = 0.28209479177387814f

    /** Logistic sigmoid `1 / (1 + e^-x)`, the opacity activation used by 3D Gaussian Splatting. */
    fun sigmoid(x: Float): Float = 1f / (1f + exp(-x))

    /** Clamp to the closed unit interval `[0, 1]`. */
    fun clamp01(x: Float): Float = when {
        x < 0f -> 0f
        x > 1f -> 1f
        else -> x
    }

    /** Convert an SH degree-0 DC coefficient to a linear color component in `[0, 1]`. */
    fun dcToLinearColor(dc: Float): Float = clamp01(0.5f + SH_C0 * dc)

    /**
     * Normalize the quaternion stored at `q[offset..offset+3]` (order `x, y, z, w`) in place. A
     * near-zero quaternion is replaced by the identity `(0, 0, 0, 1)`.
     */
    fun normalizeQuaternion(q: FloatArray, offset: Int) {
        val x = q[offset]
        val y = q[offset + 1]
        val z = q[offset + 2]
        val w = q[offset + 3]
        val norm = sqrt(x * x + y * y + z * z + w * w)
        if (norm > 1e-8f) {
            val inv = 1f / norm
            q[offset] = x * inv
            q[offset + 1] = y * inv
            q[offset + 2] = z * inv
            q[offset + 3] = w * inv
        } else {
            q[offset] = 0f
            q[offset + 1] = 0f
            q[offset + 2] = 0f
            q[offset + 3] = 1f
        }
    }
}
