// Derived from Google Sceneform (Apache-2.0), modified by the SceneView contributors.
package io.github.sceneview.collision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Static functions for common math operations used by the collision system.
 *
 * Provides float comparison with tolerance, clamping, and linear interpolation.
 */
object MathHelper {

    /** Machine epsilon for single-precision IEEE 754 floats. */
    internal const val FLT_EPSILON = 1.19209290E-07f

    /**
     * Absolute tolerance for near-zero comparisons.
     *
     * Note: deliberately small because `almostEqualRelativeAndAbs` is also called with
     * SQUARED magnitudes (e.g. `lengthSquared` in [Vector3.normalized]). Bumping this
     * higher would cause small-but-non-zero vectors (length ≈ 1e-4 → length² ≈ 1e-8)
     * to be classified as zero. Call sites that need a coarser epsilon for non-squared
     * floats (e.g. ray-direction parallelism in `Box.rayIntersection`) should use a
     * direct `abs(x) < epsilon` check at the call site instead of widening this
     * constant. See #1096.
     */
    internal const val MAX_DELTA = 1.0E-10f

    /**
     * Returns true if two floats are equal within a tolerance. Useful for comparing floating point
     * numbers while accounting for the limitations in floating point precision.
     */
    fun almostEqualRelativeAndAbs(a: Float, b: Float): Boolean {
        val diff = abs(a - b)
        if (diff <= MAX_DELTA) {
            return true
        }

        val largest = max(abs(a), abs(b))

        return diff <= largest * FLT_EPSILON
    }

    /** Clamps a value between a minimum and maximum range. */
    fun clamp(value: Float, min: Float, max: Float): Float = value.coerceIn(min, max)

    /** Clamps a value between a range of 0 and 1. */
    internal fun clamp01(value: Float): Float = value.coerceIn(0.0f, 1.0f)

    /**
     * Linearly interpolates between a and b by a ratio.
     *
     * @param a the beginning value
     * @param b the ending value
     * @param t ratio between the two floats
     * @return interpolated value between the two floats
     */
    fun lerp(a: Float, b: Float, t: Float): Float = a + t * (b - a)
}
