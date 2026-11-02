package io.github.sceneview.ar.replay

import kotlin.math.max
import kotlin.math.min

/**
 * A rectangle in normalised screen space: `0..1` on both axes, origin top-left, `y` down — the
 * space a screenshot's pixels live in once divided by its size.
 */
data class ScreenRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)

    /** Share of the screen this rectangle covers, `0..1`. */
    val area: Float get() = width * height

    val isEmpty: Boolean get() = width <= 0f || height <= 0f

    /** Smallest rectangle enclosing both. */
    fun union(other: ScreenRect): ScreenRect = ScreenRect(
        left = min(left, other.left),
        top = min(top, other.top),
        right = max(right, other.right),
        bottom = max(bottom, other.bottom),
    )

    companion object {
        val FULL = ScreenRect(0f, 0f, 1f, 1f)

        /** The middle of the screen, clear of the top bar and the bottom controls. */
        val CENTRE = ScreenRect(0.2f, 0.2f, 0.8f, 0.8f)
    }
}

/**
 * How much of the screen the visible models take.
 *
 * @property fraction share of the screen covered by the projected bounds, `0..1`.
 * @property rect the projected bounds, or `null` when nothing visible is on screen.
 * @property cameraInside the camera sits inside a model's bounds — what a model scaled up a
 * hundredfold looks like: the viewer is in it and sees nothing of it.
 */
data class ProjectedCoverage(
    val fraction: Float,
    val rect: ScreenRect?,
    val cameraInside: Boolean = false,
) {
    /** Bounding union of two coverages: what two models on screen take together. */
    fun union(other: ProjectedCoverage): ProjectedCoverage {
        if (other.rect == null) return copy(cameraInside = cameraInside || other.cameraInside)
        if (rect == null) return other.copy(cameraInside = cameraInside || other.cameraInside)
        val r = rect.union(other.rect)
        return ProjectedCoverage(
            fraction = r.area,
            rect = r,
            cameraInside = cameraInside || other.cameraInside,
        )
    }

    companion object {
        val NONE = ProjectedCoverage(0f, null)
    }
}

/**
 * The arithmetic behind the replay checks — pure functions, no Android, no ARCore, so any app can
 * reuse them and a JVM test can pin them.
 */
object ARReplayMetrics {

    private const val W_EPSILON = 1e-5f

    /** The 8 corners of an axis-aligned box, `x, y, z` interleaved (24 floats). */
    fun boxCorners(center: FloatArray, halfExtent: FloatArray): FloatArray {
        val out = FloatArray(24)
        var i = 0
        for (sx in SIGNS) for (sy in SIGNS) for (sz in SIGNS) {
            out[i++] = center[0] + sx * halfExtent[0]
            out[i++] = center[1] + sy * halfExtent[1]
            out[i++] = center[2] + sz * halfExtent[2]
        }
        return out
    }

    /** `true` when [point] lies inside the axis-aligned box. */
    fun isInside(point: FloatArray, center: FloatArray, halfExtent: FloatArray): Boolean =
        (0..2).all { kotlin.math.abs(point[it] - center[it]) <= halfExtent[it] }

    /** `a × b` for column-major 4×4 matrices (the OpenGL / ARCore layout). */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
            out[col * 4 + row] = sum
        }
        return out
    }

    /**
     * Projects a box's [corners] (see [boxCorners]) through a column-major [viewProjection]
     * matrix and returns the screen share its bounds cover.
     *
     * - Every corner behind the camera: nothing on screen, [ProjectedCoverage.NONE].
     * - Some corners behind, some in front: the box crosses the camera plane, so it spans the
     *   view — reported as the whole screen. That is the "giant model" case.
     * - Otherwise: the bounding rectangle of the projected corners, clipped to the screen.
     */
    fun projectCoverage(corners: FloatArray, viewProjection: FloatArray): ProjectedCoverage {
        require(corners.size % 3 == 0) { "corners must hold x, y, z triples" }
        require(viewProjection.size == 16) { "viewProjection must be a 4x4 matrix" }
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var behind = 0
        val count = corners.size / 3
        for (c in 0 until count) {
            val x = corners[c * 3]
            val y = corners[c * 3 + 1]
            val z = corners[c * 3 + 2]
            val m = viewProjection
            val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
            val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
            val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
            if (cw <= W_EPSILON) {
                behind++
                continue
            }
            val nx = cx / cw
            val ny = cy / cw
            minX = min(minX, nx); maxX = max(maxX, nx)
            minY = min(minY, ny); maxY = max(maxY, ny)
        }
        if (behind == count) return ProjectedCoverage.NONE
        if (behind > 0) return ProjectedCoverage(1f, ScreenRect.FULL)
        // NDC (-1..1, y up) → screen (0..1, y down), clipped.
        val rect = ScreenRect(
            left = ((minX + 1f) / 2f).coerceIn(0f, 1f),
            top = ((1f - maxY) / 2f).coerceIn(0f, 1f),
            right = ((maxX + 1f) / 2f).coerceIn(0f, 1f),
            bottom = ((1f - minY) / 2f).coerceIn(0f, 1f),
        )
        return if (rect.isEmpty) ProjectedCoverage.NONE else ProjectedCoverage(rect.area, rect)
    }

    /**
     * Perceived brightness of one `0xAARRGGBB` pixel, `0..1`: Rec. 709 luma on the stored
     * (gamma-encoded) values — what a person calls "dark", not linear light.
     */
    fun luma(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
    }

    /**
     * Mean [luma] of the `0xAARRGGBB` [pixels] (row-major, [width] × [height]) inside [region],
     * sampling every [stride]-th pixel on each axis. Returns `0` for an empty region.
     */
    fun meanLuminance(
        pixels: IntArray,
        width: Int,
        height: Int,
        region: ScreenRect = ScreenRect.FULL,
        stride: Int = 1,
    ): Float {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "pixels too small" }
        require(stride >= 1) { "stride must be >= 1" }
        val x0 = (region.left * width).toInt().coerceIn(0, width)
        val x1 = (region.right * width).toInt().coerceIn(0, width)
        val y0 = (region.top * height).toInt().coerceIn(0, height)
        val y1 = (region.bottom * height).toInt().coerceIn(0, height)
        var sum = 0.0
        var n = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                sum += luma(pixels[y * width + x])
                n++
                x += stride
            }
            y += stride
        }
        return if (n == 0) 0f else (sum / n).toFloat()
    }

    private val SIGNS = floatArrayOf(-1f, 1f)
}

/** One thing a replay can verify. */
enum class ARReplayCheck {
    /** The recorded session advanced at least [ARReplayThresholds.minFrames] frames. */
    REPLAYED,

    /** A plane was tracked within [ARReplayThresholds.planeWithinFrames] frames. */
    PLANE,

    /** At least one anchor is tracking. */
    ANCHOR,

    /** The visible models cover a share of the screen inside the allowed range. */
    MODEL_COVERAGE,

    /** The rendered screen is not dark. */
    SCENE_BRIGHTNESS,

    /** The area the models cover is not dark. */
    MODEL_BRIGHTNESS,
}

/**
 * Pass marks for [evaluateReplay]. The defaults target the two failures seen in a shipping AR
 * app: a scene rendered dark, and a model so large the camera sits inside it.
 */
data class ARReplayThresholds(
    val minFrames: Int = 5,
    val planeWithinFrames: Int = 300,
    val minCoverage: Float = 0.002f,
    val maxCoverage: Float = 0.85f,
    val minSceneLuminance: Float = 0.08f,
    val minModelLuminance: Float = 0.05f,
)

/** What a replay measured. `null` means "not measured". */
data class ARReplayObservation(
    val frames: Int,
    val firstPlaneFrame: Int? = null,
    val trackedAnchors: Int = 0,
    val visibleModels: Int = 0,
    val coverage: ProjectedCoverage? = null,
    val sceneLuminance: Float? = null,
    val modelLuminance: Float? = null,
)

/** The outcome of one [ARReplayCheck]. */
data class ARReplayCheckResult(
    val check: ARReplayCheck,
    val passed: Boolean,
    val measured: String,
    val expected: String,
)

/**
 * Grades [observation] against each of the [checks]. Pure: the same inputs always give the same
 * verdict, so a regression in a replay shows up as a named, measured failure.
 */
fun evaluateReplay(
    observation: ARReplayObservation,
    checks: Set<ARReplayCheck>,
    thresholds: ARReplayThresholds = ARReplayThresholds(),
): List<ARReplayCheckResult> = ARReplayCheck.entries.filter { it in checks }.map { check ->
    val t = thresholds
    val o = observation
    when (check) {
        ARReplayCheck.REPLAYED -> ARReplayCheckResult(
            check, o.frames >= t.minFrames, "${o.frames} frames", ">= ${t.minFrames} frames",
        )
        ARReplayCheck.PLANE -> ARReplayCheckResult(
            check,
            o.firstPlaneFrame != null && o.firstPlaneFrame <= t.planeWithinFrames,
            o.firstPlaneFrame?.let { "first plane at frame $it" } ?: "no plane",
            "plane by frame ${t.planeWithinFrames}",
        )
        ARReplayCheck.ANCHOR -> ARReplayCheckResult(
            check, o.trackedAnchors > 0, "${o.trackedAnchors} tracking anchor(s)", ">= 1",
        )
        ARReplayCheck.MODEL_COVERAGE -> {
            val c = o.coverage
            val passed = c != null && o.visibleModels > 0 && !c.cameraInside &&
                c.fraction >= t.minCoverage && c.fraction <= t.maxCoverage
            val measured = when {
                o.visibleModels == 0 || c == null -> "no visible model"
                c.cameraInside -> "camera inside the model (giant)"
                else -> "${fmt(c.fraction, 4)} of the screen"
            }
            ARReplayCheckResult(check, passed, measured, "${t.minCoverage}..${t.maxCoverage}")
        }
        ARReplayCheck.SCENE_BRIGHTNESS -> ARReplayCheckResult(
            check,
            o.sceneLuminance != null && o.sceneLuminance >= t.minSceneLuminance,
            o.sceneLuminance?.let { "luma ${fmt(it, 3)}" } ?: "not measured",
            ">= ${t.minSceneLuminance}",
        )
        ARReplayCheck.MODEL_BRIGHTNESS -> ARReplayCheckResult(
            check,
            o.modelLuminance != null && o.modelLuminance >= t.minModelLuminance,
            o.modelLuminance?.let { "luma ${fmt(it, 3)}" } ?: "not measured",
            ">= ${t.minModelLuminance}",
        )
    }
}

private fun fmt(value: Float, digits: Int): String =
    String.format(java.util.Locale.ROOT, "%.${digits}f", value)
