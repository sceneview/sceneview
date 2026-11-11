package io.github.sceneview.demo.demos.internal.feel

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** One centred hero on the showroom floor. Rendering and picking use this same perspective. */
internal class MatterStage {
    var widthPx = 1
    var heightPx = 1
    var distance = 9f
        private set
    val halfWidth get() = distance * TAN_HALF_FOV
    val halfHeight get() = halfWidth * heightPx / widthPx.coerceAtLeast(1)
    val eyeX get() = distance * BACK_X
    var targetY = TARGET_Y
        private set
    val eyeY get() = targetY + distance * SIN
    val eyeZ get() = distance * BACK_Z
    // Enough travel to put the complete silhouette off screen before the next settles.
    val slideDistance get() = halfWidth * 2f + FeelSimulation.PAD_HALF_WIDTH * 2f
    private var framedWidth = -1
    private var framedHeight = -1

    fun frame(): Boolean {
        if (widthPx == framedWidth && heightPx == framedHeight) return false
        framedWidth = widthPx; framedHeight = heightPx
        val aspect = widthPx.toFloat() / heightPx.coerceAtLeast(1)
        targetY = TARGET_Y
        fun fits(d: Float): Boolean {
            for (x in listOf(-FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_WIDTH))
                for (y in listOf(-FeelSimulation.PAD_THICKNESS, 0f))
                    for (z in listOf(-FeelSimulation.PAD_HALF_LENGTH, FeelSimulation.PAD_HALF_LENGTH)) {
                        val cy = y - targetY
                        val depth = d - (x * BACK_X + cy * SIN + z * BACK_Z)
                        if (depth <= NEAR) return false
                        val right = x * COS_AZIMUTH - z * SIN_AZIMUTH
                        val up = x * UP_X + cy * COS + z * UP_Z
                        if (abs(right) > depth * TAN_HALF_FOV * .957f ||
                            abs(up) > depth * TAN_HALF_FOV / aspect * .78f) return false
                    }
            return true
        }
        // Re-centre the projected silhouette, not the origin: perspective lifts the far edge.
        // The rounded silhouette fills ~90% width; a small upward bias leaves room for its shadow.
        repeat(5) {
            var low = 3f; var high = 100f
            repeat(30) {
                val mid = (low + high) / 2f
                if (fits(mid)) high = mid else low = mid
            }
            distance = high
            if (it < 4) {
                var bottom = Float.POSITIVE_INFINITY
                var top = Float.NEGATIVE_INFINITY
                for (x in listOf(-FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_WIDTH))
                    for (y in listOf(-FeelSimulation.PAD_THICKNESS, 0f))
                        for (z in listOf(-FeelSimulation.PAD_HALF_LENGTH, FeelSimulation.PAD_HALF_LENGTH)) {
                            val cy = y - targetY
                            val depth = distance - (x * BACK_X + cy * SIN + z * BACK_Z)
                            val projected = (x * UP_X + cy * COS + z * UP_Z) / depth
                            bottom = minOf(bottom, projected); top = maxOf(top, projected)
                        }
                targetY += ((bottom + top) * .5f - TAN_HALF_FOV / aspect * .04f) * distance / COS
            }
        }
        return true
    }

    /** Sphere trace the rounded solid. No rectangular hit region in the bevel's empty corners. */
    fun pointOnSlab(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): Pair<Float, Float>? {
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (!length.isFinite() || length < 0.000001f) return null
        val nx = dx / length; val ny = dy / length; val nz = dz / length
        // Start at the bounding box, so nearly grazing side rays do not spend all 96 steps
        // approaching the slab from the camera. The rounded SDF still rejects empty corners.
        var enter = 0f
        var leave = FAR.toFloat()
        fun clip(origin: Float, direction: Float, low: Float, high: Float): Boolean {
            if (abs(direction) < .000001f) return origin in low..high
            val a = (low - origin) / direction
            val b = (high - origin) / direction
            enter = maxOf(enter, minOf(a, b))
            leave = minOf(leave, maxOf(a, b))
            return enter <= leave
        }
        if (!clip(ox, nx, -FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_WIDTH) ||
            !clip(oy, ny, -FeelSimulation.PAD_THICKNESS, 0f) ||
            !clip(oz, nz, -FeelSimulation.PAD_HALF_LENGTH, FeelSimulation.PAD_HALF_LENGTH)
        ) return null
        var travel = enter
        repeat(96) {
            val x = ox + nx * travel; val y = oy + ny * travel; val z = oz + nz * travel
            val distance = MatterMeshes.distance(x, y, z)
            if (distance < 0.00005f) return x to z
            travel += distance
            if (travel > leave + .00005f) return null
        }
        return null
    }

    /** The captured finger's ray meets the centre of its own well, even during gel overshoot. */
    fun pointOnPad(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float,
                   visualDepth: Float = 0f): Pair<Float, Float>? {
        if (dy >= -0.0001f) return null
        val t = (-visualDepth - oy) / dy
        if (t < 0f) return null
        return ox + dx * t to oz + dz * t
    }

    companion object {
        const val HORIZONTAL_FOV = 48.0
        const val NEAR = 0.1
        const val FAR = 150.0
        const val ELEVATION = 24f
        const val AZIMUTH = 24f
        const val TARGET_Y = -FeelSimulation.PAD_THICKNESS * 0.35f
        val TAN_HALF_FOV = tan(Math.toRadians(HORIZONTAL_FOV / 2.0)).toFloat()
        val SIN = sin(Math.toRadians(ELEVATION.toDouble())).toFloat()
        val COS = cos(Math.toRadians(ELEVATION.toDouble())).toFloat()
        val SIN_AZIMUTH = sin(Math.toRadians(AZIMUTH.toDouble())).toFloat()
        val COS_AZIMUTH = cos(Math.toRadians(AZIMUTH.toDouble())).toFloat()
        val BACK_X = SIN_AZIMUTH * COS
        val BACK_Z = COS_AZIMUTH * COS
        val UP_X = -SIN_AZIMUTH * SIN
        val UP_Z = -COS_AZIMUTH * SIN
        // Yaw the HDR by azimuth - 90 degrees: a long side softbox crosses the top reflection.
        // Rotation around Y preserves the showroom's upward irradiance and floor exposure.
        val STUDIO_ROTATION = floatArrayOf(
            SIN_AZIMUTH, 0f, COS_AZIMUTH,
            0f, 1f, 0f,
            -COS_AZIMUTH, 0f, SIN_AZIMUTH,
        )
        val RADIUS = sqrt(FeelSimulation.PAD_HALF_WIDTH * FeelSimulation.PAD_HALF_WIDTH +
            FeelSimulation.PAD_HALF_LENGTH * FeelSimulation.PAD_HALF_LENGTH +
            FeelSimulation.PAD_THICKNESS * FeelSimulation.PAD_THICKNESS / 4f)
    }
}
