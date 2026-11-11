package io.github.sceneview.demo.demos.internal.feel

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/** Three portrait rows, fitted using the same perspective as the renderer and touch rays. */
internal class MatterStage {
    var widthPx = 1
    var heightPx = 1
    val captionPx = IntArray(3)
    val x = FloatArray(3)
    val y = FloatArray(3)
    val z = FloatArray(3)
    val scale = FloatArray(3) { 1f }
    val tilt = FloatArray(3)
    var openingElevation = ELEVATION
        private set
    var openingAzimuth = AZIMUTH
        private set
    val halfWidth = DISTANCE * tan(Math.toRadians(HORIZONTAL_FOV / 2.0)).toFloat()
    var halfHeight = halfWidth
        private set
    private var framedWidth = -1
    private var framedHeight = -1
    private val framedCaptions = IntArray(3) { -1 }

    fun frame(): Boolean {
        if (framedWidth == widthPx && framedHeight == heightPx && framedCaptions.contentEquals(captionPx)) return false
        framedWidth = widthPx
        framedHeight = heightPx
        captionPx.copyInto(framedCaptions)
        halfHeight = halfWidth * heightPx / widthPx.coerceAtLeast(1)
        val row = halfHeight * 2f / 3f
        val unitsPerPx = halfWidth * 2f / widthPx.coerceAtLeast(1)
        for (i in 0..2) {
            val top = halfHeight - i * row
            val bottom = top - row + captionPx[i] * unitsPerPx
            val middle = (top + bottom) / 2f
            val half = (top - bottom).coerceAtLeast(0f) * 0.48f
            // Aim each display plinth at its row's view ray. Without this correction the
            // top row is almost edge-on and the bottom row almost overhead in a tall viewport.
            tilt[i] = atan(middle / DISTANCE)
            // Each corner imposes an interval on the centre in camera right/up coordinates.
            // Intersect those intervals: unlike an orthographic fit, this includes foreshortening
            // and the different viewing angles of the upper and lower samples.
            fun fit(s: Float): FloatArray {
                var left = Float.NEGATIVE_INFINITY
                var right = Float.POSITIVE_INFINITY
                var low = Float.NEGATIVE_INFINITY
                var high = Float.POSITIVE_INFINITY
                for (px in listOf(-FeelSimulation.PAD_HALF_WIDTH, FeelSimulation.PAD_HALF_WIDTH))
                    for (py in listOf(-FeelSimulation.PAD_THICKNESS, 0f))
                        for (pz in listOf(-FeelSimulation.PAD_HALF_LENGTH, FeelSimulation.PAD_HALF_LENGTH)) {
                            val p = rotate(px, py, pz, tilt[i])
                            val depth = 1f - s * (p[0] * BACK_X + p[1] * SIN + p[2] * BACK_Z) / DISTANCE
                            val r = s * (p[0] * COS_AZIMUTH - p[2] * SIN_AZIMUTH)
                            val u = s * (p[0] * UP_X + p[1] * COS + p[2] * UP_Z)
                            left = maxOf(left, -halfWidth * 0.94f * depth - r)
                            right = minOf(right, halfWidth * 0.94f * depth - r)
                            low = maxOf(low, (middle - half) * depth - u)
                            high = minOf(high, (middle + half) * depth - u)
                        }
                return floatArrayOf(left, right, low, high)
            }
            var small = 0f
            var large = 2f
            repeat(24) {
                val candidate = (small + large) / 2f
                val bounds = fit(candidate)
                if (bounds[0] <= bounds[1] && bounds[2] <= bounds[3]) small = candidate else large = candidate
            }
            scale[i] = small.coerceAtLeast(0.001f)
            val bounds = fit(scale[i])
            val right = (bounds[0] + bounds[1]) / 2f
            val up = (bounds[2] + bounds[3]) / 2f
            x[i] = right * COS_AZIMUTH + up * UP_X
            y[i] = up * COS
            z[i] = -right * SIN_AZIMUTH + up * UP_Z
        }
        // The opening belongs to the tilted foam row. Face its frame toward the actual
        // local eye, including row translation, rather than the old world camera pitch.
        val eye = rotate(DISTANCE * BACK_X - x[0], DISTANCE * SIN - y[0],
            DISTANCE * BACK_Z - z[0], -tilt[0])
        openingElevation = Math.toDegrees(atan2(eye[1], hypot(eye[0], eye[2])).toDouble()).toFloat()
        openingAzimuth = Math.toDegrees(atan2(eye[0], eye[2]).toDouble()).toFloat()
        return true
    }

    /**
     * Pick the whole resting solid, then project its first intersection vertically onto
     * the top. Unlike clamping an infinite top-plane hit, this rejects empty space beside
     * or below the silhouette. The same mapping is used throughout captured pointer travel.
     */
    fun pointOnSlab(i: Int, ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): Pair<Float, Float>? {
        val origin = rotate((ox - x[i]) / scale[i], (oy - y[i]) / scale[i], (oz - z[i]) / scale[i], -tilt[i])
        val direction = rotate(dx, dy, dz, -tilt[i])
        var entry = 0f
        var exit = Float.POSITIVE_INFINITY
        val low = floatArrayOf(-FeelSimulation.PAD_HALF_WIDTH, -FeelSimulation.PAD_THICKNESS, -FeelSimulation.PAD_HALF_LENGTH)
        val high = floatArrayOf(FeelSimulation.PAD_HALF_WIDTH, 0f, FeelSimulation.PAD_HALF_LENGTH)
        if (direction.all { abs(it) < 0.000001f }) return null
        for (axis in 0..2) {
            val d = direction[axis]
            if (abs(d) < 0.000001f) {
                if (origin[axis] < low[axis] || origin[axis] > high[axis]) return null
            } else {
                val a = (low[axis] - origin[axis]) / d
                val b = (high[axis] - origin[axis]) / d
                entry = maxOf(entry, minOf(a, b))
                exit = minOf(exit, maxOf(a, b))
                if (exit < entry) return null
            }
        }
        return (origin[0] + direction[0] * entry).coerceIn(low[0], high[0]) to
            (origin[2] + direction[2] * entry).coerceIn(low[2], high[2])
    }

    /** Intersect a rendered world ray with the local top or the centre of its visual dent. */
    fun pointOnPad(i: Int, ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float,
                   visualDepth: Float = 0f): Pair<Float, Float>? {
        val origin = rotate((ox - x[i]) / scale[i], (oy - y[i]) / scale[i], (oz - z[i]) / scale[i], -tilt[i])
        val direction = rotate(dx, dy, dz, -tilt[i])
        if (direction[1] >= -0.0001f) return null
        val t = (-visualDepth - origin[1]) / direction[1]
        if (t < 0f) return null
        return origin[0] + direction[0] * t to origin[2] + direction[2] * t
    }

    companion object {
        // Deliberate product view: perspective, 40° horizontal FOV, 42° above and 24° to
        // the right. A close camera makes the top lattice converge and exposes the right side.
        // SceneView resets its lens on surface resize; FeelDemo reapplies this projection per frame.
        const val HORIZONTAL_FOV = 40.0
        const val DISTANCE = 8f
        const val NEAR = 0.1
        const val FAR = 100.0
        const val ELEVATION = 42f
        const val AZIMUTH = 24f
        val SIN = sin(Math.toRadians(ELEVATION.toDouble())).toFloat()
        val COS = cos(Math.toRadians(ELEVATION.toDouble())).toFloat()
        val SIN_AZIMUTH = sin(Math.toRadians(AZIMUTH.toDouble())).toFloat()
        val COS_AZIMUTH = cos(Math.toRadians(AZIMUTH.toDouble())).toFloat()
        val BACK_X = SIN_AZIMUTH * COS
        val BACK_Z = COS_AZIMUTH * COS
        val UP_X = -SIN_AZIMUTH * SIN
        val UP_Z = -COS_AZIMUTH * SIN

        /** Rotate around camera right; also used inversely for local touch coordinates. */
        fun rotate(x: Float, y: Float, z: Float, angle: Float): FloatArray {
            val r = x * COS_AZIMUTH - z * SIN_AZIMUTH
            val u = x * UP_X + y * COS + z * UP_Z
            val b = x * BACK_X + y * SIN + z * BACK_Z
            val up = u * cos(angle) - b * sin(angle)
            val back = u * sin(angle) + b * cos(angle)
            return floatArrayOf(r * COS_AZIMUTH + up * UP_X + back * BACK_X,
                up * COS + back * SIN, -r * SIN_AZIMUTH + up * UP_Z + back * BACK_Z)
        }
    }
}
