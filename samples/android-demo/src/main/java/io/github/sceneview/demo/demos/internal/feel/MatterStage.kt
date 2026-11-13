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
    private var orbitSin = SIN_AZIMUTH
    private var orbitCos = COS_AZIMUTH
    fun orbit(yawDegrees: Float) {
        val radians = Math.toRadians((AZIMUTH + yawDegrees).toDouble())
        orbitSin = sin(radians).toFloat()
        orbitCos = cos(radians).toFloat()
    }
    private var targetRight = 0f
    val targetX get() = targetRight * orbitCos
    val targetZ get() = -targetRight * orbitSin
    val eyeX get() = targetX + distance * orbitSin * COS
    var targetY = TARGET_Y
        private set
    val eyeY get() = targetY + distance * SIN
    // Bottom of the mark clears the viewport before it becomes invisible, even in tall windows.
    val openingTravel get(): Float {
        val verticalTan = TAN_HALF_FOV * heightPx / widthPx.coerceAtLeast(1)
        return maxOf(MatterOpening.TRAVEL,
            targetY + distance * verticalTan / (COS + verticalTan * SIN) + FeelSimulation.PAD_THICKNESS)
    }
    val eyeZ get() = targetZ + distance * orbitCos * COS
    // Enough travel to put the complete silhouette off screen before the next settles.
    val slideDistance get() = halfWidth * 2f + FeelSimulation.PAD_HALF_WIDTH * 2f
    private var framedWidth = -1
    private var framedHeight = -1

    fun frame(): Boolean {
        if (widthPx == framedWidth && heightPx == framedHeight) return false
        framedWidth = widthPx; framedHeight = heightPx
        val aspect = widthPx.toFloat() / heightPx.coerceAtLeast(1)
        targetY = TARGET_Y
        targetRight = 0f
        fun fits(d: Float): Boolean {
            for (x in listOf(-FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_WIDTH))
                for (y in listOf(-FeelSimulation.PAD_THICKNESS, 0f))
                    for (z in listOf(-FeelSimulation.PAD_HALF_LENGTH, FeelSimulation.PAD_HALF_LENGTH)) {
                        val cy = y - targetY
                        val depth = d - (x * BACK_X + cy * SIN + z * BACK_Z)
                        if (depth <= NEAR) return false
                        val right = x * COS_AZIMUTH - z * SIN_AZIMUTH - targetRight
                        val up = x * UP_X + cy * COS + z * UP_Z
                        if (abs(right) > depth * TAN_HALF_FOV * .77f ||
                            abs(up) > depth * TAN_HALF_FOV / aspect * .78f) return false
                    }
            return true
        }
        // Centre BOTH projected axes, not the world origin. Perspective made the old
        // silhouette 15 px left on a 4a; the old .48 vertical target put it 37 px high.
        // The .77 box allowance leaves ~.75 for the actual rounded silhouette.
        repeat(8) {
            var low = 3f; var high = 100f
            repeat(30) {
                val mid = (low + high) / 2f
                if (fits(mid)) high = mid else low = mid
            }
            distance = high
            if (it < 7) {
                var bottom = Float.POSITIVE_INFINITY
                var top = Float.NEGATIVE_INFINITY
                var left = Float.POSITIVE_INFINITY
                var right = Float.NEGATIVE_INFINITY
                for (x in listOf(-FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_WIDTH))
                    for (y in listOf(-FeelSimulation.PAD_THICKNESS, 0f))
                        for (z in listOf(-FeelSimulation.PAD_HALF_LENGTH, FeelSimulation.PAD_HALF_LENGTH)) {
                            val cy = y - targetY
                            val depth = distance - (x * BACK_X + cy * SIN + z * BACK_Z)
                            val projected = (x * UP_X + cy * COS + z * UP_Z) / depth
                            bottom = minOf(bottom, projected); top = maxOf(top, projected)
                            val horizontal = (x * COS_AZIMUTH - z * SIN_AZIMUTH - targetRight) / depth
                            left = minOf(left, horizontal); right = maxOf(right, horizontal)
                        }
                targetY += (bottom + top) * .5f * distance / COS
                targetRight += (left + right) * .5f * distance
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
        const val ELEVATION = 30f
        const val AZIMUTH = 24f
        const val TARGET_Y = -FeelSimulation.PAD_THICKNESS * 0.35f
        // Unlit scene-linear grey before PBR Neutral: ~22% sRGB, shared by floor and sky.
        const val STUDIO_GREY = .08f
        // Shared by the physical spot and the analytic floor transmitted by the gel.
        const val FLOOR_Y = -FeelSimulation.PAD_THICKNESS - .003f
        const val KEY_X = -3f
        const val KEY_Y = 6f
        const val KEY_Z = 4f
        const val KEY_TARGET_Z = -.5f
        const val KEY_CANDELA = 4_500_000f
        const val KEY_INNER_DEGREES = 8f
        const val KEY_OUTER_DEGREES = 32f
        const val KEY_RANGE = 20f
        const val POOL_STRENGTH = .40f
        const val GEL_CONTACT_SHADOW = .12f
        const val RIM_LUX = 18_000f
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
