package io.github.sceneview.web.splat

import kotlin.math.pow
import kotlin.math.sqrt

/** CPU reference for the vertex shader, independent of Filament and a WebGL context. */
internal object SplatWebMath {
    // Constants are identical to Android splat.mat / camera_stream_flat.mat.
    fun srgbToLinear(value: Double): Double {
        val s = value.coerceIn(0.0, 1.0)
        return if (s < 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    fun inverseFilmic(x: Double): Double =
        (0.03 - 0.59 * x - sqrt(0.0009 + 1.3702 * x - 1.0127 * x * x)) / (-5.02 + 4.86 * x)

    /**
     * Project R·S through the same pixel Jacobian and 1.3/P clamp as splat_web.mat.
     * [viewFromModel] is the row-major linear node-to-view transform (rotation AND scale).
     * [center] is already in view space. Returns covariance [a, b, c], including 0.3 px².
     */
    fun covariance2D(
        quaternion: FloatArray, scale: FloatArray, center: FloatArray,
        projectionX: Double, projectionY: Double, width: Double, height: Double,
        viewFromModel: DoubleArray = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
    ): DoubleArray {
        val x = quaternion[0].toDouble(); val y = quaternion[1].toDouble()
        val z = quaternion[2].toDouble(); val w = quaternion[3].toDouble()
        val rotation = doubleArrayOf(
            1 - 2 * (y*y + z*z), 2 * (x*y - w*z), 2 * (x*z + w*y),
            2 * (x*y + w*z), 1 - 2 * (x*x + z*z), 2 * (y*z - w*x),
            2 * (x*z - w*y), 2 * (y*z + w*x), 1 - 2 * (x*x + y*y),
        )
        val tm = DoubleArray(9) { index ->
            val row = index / 3; val col = index % 3
            (0..2).sumOf { k -> viewFromModel[row * 3 + k] * rotation[k * 3 + col] } * scale[col]
        }
        val depth = -center[2].toDouble()
        require(depth > 1e-4)
        val tx = (center[0] / depth).coerceIn(-1.3 / projectionX, 1.3 / projectionX) * depth
        val ty = (center[1] / depth).coerceIn(-1.3 / projectionY, 1.3 / projectionY) * depth
        val fx = projectionX * width * 0.5; val fy = projectionY * height * 0.5
        val j0 = DoubleArray(3) { fx / depth * tm[it] + fx * tx / (depth*depth) * tm[6 + it] }
        val j1 = DoubleArray(3) { fy / depth * tm[3 + it] + fy * ty / (depth*depth) * tm[6 + it] }
        return doubleArrayOf(
            j0.sumOf { it * it } + 0.3,
            (0..2).sumOf { j0[it] * j1[it] },
            j1.sumOf { it * it } + 0.3,
        )
    }
}
