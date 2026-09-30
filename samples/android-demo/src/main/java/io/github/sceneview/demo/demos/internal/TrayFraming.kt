package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The `rolling-balls` opening shot (#4180), fitted to the table itself rather than to a bounding
 * volume around it.
 *
 * A generic box fit reserves room for the column the balls drop from and for any orbit azimuth, so
 * the table filled about three quarters of a phone's width and sat low under an empty band. This
 * solves the actual perspective instead: the camera looks down at [pitchDegrees] along -Z, and its
 * distance and aim are chosen so the projected table spans [widthFill] of the viewport width,
 * never more than [heightFill] of its height (the binding axis in landscape), and is centred
 * vertically — its near and far edges equally far from the viewport's top and bottom.
 *
 * Pure math, so it is unit-tested on the JVM.
 */
object TrayFraming {

    /** A fixed-pitch look at the table: where the eye sits and the point it aims at. */
    data class Shot(val eye: Float3, val target: Float3)

    /** Projected extent of the table in normalised device coordinates, `[-1, 1]` on both axes. */
    data class ScreenBounds(val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)

    /**
     * The shot that frames the axis-aligned box from [min] to [max] (the table, in world space)
     * in a viewport of [aspect] (width / height) through a lens of [verticalFovDegrees].
     */
    @Suppress("LongParameterList")
    fun fit(
        min: Float3,
        max: Float3,
        aspect: Float,
        pitchDegrees: Float,
        verticalFovDegrees: Float,
        widthFill: Float,
        heightFill: Float,
    ): Shot {
        val safeAspect = if (aspect.isFinite() && aspect > 0f) aspect else DEFAULT_ASPECT
        val pitch = Math.toRadians(pitchDegrees.toDouble()).toFloat()
        val back = Float3(0f, sin(pitch), cos(pitch))
        val up = Float3(0f, cos(pitch), -sin(pitch))
        val tanHalf = tan(Math.toRadians(verticalFovDegrees / 2.0)).toFloat()
        val center = Float3((min.x + max.x) / 2f, (min.y + max.y) / 2f, (min.z + max.z) / 2f)

        var target = center
        var distance = 1f
        repeat(CENTERING_PASSES) {
            distance = solveDistance(min, max, target, back, up, tanHalf, safeAspect, widthFill, heightFill)
            val bounds = project(min, max, Shot(target + back * distance, target), up, tanHalf, safeAspect)
            // Move the aim along the camera's up axis by the off-centre amount, measured at the
            // depth of the aim point, so the next pass sees the table centred.
            val offCentre = (bounds.minY + bounds.maxY) / 2f
            target += up * (offCentre * distance * tanHalf)
        }
        distance = solveDistance(min, max, target, back, up, tanHalf, safeAspect, widthFill, heightFill)
        return Shot(target + back * distance, target)
    }

    /** Where the box from [min] to [max] lands on screen from [shot], camera up along [up]. */
    fun project(min: Float3, max: Float3, shot: Shot, up: Float3, tanHalf: Float, aspect: Float): ScreenBounds {
        val forward = normalize(shot.target - shot.eye)
        val right = Float3(1f, 0f, 0f)
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (cx in listOf(min.x, max.x)) for (cy in listOf(min.y, max.y)) for (cz in listOf(min.z, max.z)) {
            val v = Float3(cx, cy, cz) - shot.eye
            val depth = dot(v, forward)
            if (depth <= 1e-4f) return ScreenBounds(-BEHIND, BEHIND, -BEHIND, BEHIND)
            val x = dot(v, right) / (depth * tanHalf * aspect)
            val y = dot(v, up) / (depth * tanHalf)
            minX = min(minX, x)
            maxX = max(maxX, x)
            minY = min(minY, y)
            maxY = max(maxY, y)
        }
        return ScreenBounds(minX, maxX, minY, maxY)
    }

    /** Smallest distance at which the box fits both fills, by bisection (the fit shrinks with distance). */
    @Suppress("LongParameterList")
    private fun solveDistance(
        min: Float3,
        max: Float3,
        target: Float3,
        back: Float3,
        up: Float3,
        tanHalf: Float,
        aspect: Float,
        widthFill: Float,
        heightFill: Float,
    ): Float {
        var near = MIN_DISTANCE
        var far = MAX_DISTANCE
        repeat(BISECTION_STEPS) {
            val mid = (near + far) / 2f
            val b = project(min, max, Shot(target + back * mid, target), up, tanHalf, aspect)
            val width = max(abs(b.minX), abs(b.maxX))
            val height = (b.maxY - b.minY) / 2f
            if (width > widthFill || height > heightFill) near = mid else far = mid
        }
        return far
    }

    private const val DEFAULT_ASPECT = 0.8f
    private const val CENTERING_PASSES = 6
    private const val BISECTION_STEPS = 40
    private const val MIN_DISTANCE = 0.05f
    private const val MAX_DISTANCE = 100f

    /** Bounds reported for a corner behind the eye: larger than any fill, so bisection backs off. */
    private const val BEHIND = 10f
}
