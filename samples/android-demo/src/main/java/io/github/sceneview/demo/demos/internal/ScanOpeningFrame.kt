package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/** Screen-only framing: never changes the scan's geometry or its saved bounds. */
internal object ScanOpeningFrame {
    const val FILL = 0.75f

    /**
     * Keep the pivot at the robust box's centre. Fit its projected larger extent to [FILL] of
     * the shorter screen side, in either orientation, including perspective depth.
     * [azimuthDegrees] is in the box's local frame; the returned pose is in world space.
     */
    fun pose(
        frame: ScanResultFrame,
        azimuthDegrees: Float,
        elevationDegrees: Float,
        verticalFovDegrees: Double,
        aspect: Float,
    ): OrbitPose {
        require(aspect.isFinite() && aspect > 0f)
        require(verticalFovDegrees > 0.0 && verticalFovDegrees < 180.0)
        val b = frame.local
        val centre = Position((b[0] + b[3]) / 2f, (b[1] + b[4]) / 2f, (b[2] + b[5]) / 2f)
        val a = Math.toRadians(azimuthDegrees.toDouble())
        val e = Math.toRadians(elevationDegrees.toDouble())
        val across = DoubleArray(8)
        val up = DoubleArray(8)
        val toward = DoubleArray(8)
        for (i in 0..7) {
            val x = (b[if (i and 1 == 0) 0 else 3] - centre.x).toDouble()
            val y = (b[if (i and 2 == 0) 1 else 4] - centre.y).toDouble()
            val z = (b[if (i and 4 == 0) 2 else 5] - centre.z).toDouble()
            across[i] = x * cos(a) - z * sin(a)
            up[i] = -x * sin(a) * sin(e) + y * cos(e) - z * cos(a) * sin(e)
            toward[i] = x * sin(a) * cos(e) + y * sin(e) + z * cos(a) * cos(e)
        }
        val tanHalf = tan(Math.toRadians(verticalFovDegrees / 2.0))
        // Screen-height units: horizontal FOV follows tan(h/2) = aspect * tan(v/2).
        val span = FILL * min(aspect, 1f) * 2.0 * tanHalf
        var low = toward.max() + ArDebugFraming.MIN_DISTANCE
        var high = low
        for (i in 0..7) {
            high = max(high, toward[i] + 2.0 * max(abs(across[i]), abs(up[i])) / span)
        }
        fun extent(values: DoubleArray, distance: Double): Double {
            var least = Double.POSITIVE_INFINITY
            var most = Double.NEGATIVE_INFINITY
            for (i in values.indices) {
                val projected = values[i] / (distance - toward[i])
                least = min(least, projected)
                most = max(most, projected)
            }
            return most - least
        }
        // Solve the actual span; symmetric margins leave a perspective box unnecessarily small.
        repeat(28) {
            val middle = (low + high) / 2.0
            if (max(extent(across, middle), extent(up, middle)) > span) low = middle else high = middle
        }
        return frame.toWorld(OrbitPose(centre, azimuthDegrees, elevationDegrees, high.toFloat()))
    }
}
