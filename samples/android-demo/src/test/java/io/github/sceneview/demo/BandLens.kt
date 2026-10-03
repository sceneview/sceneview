package io.github.sceneview.demo

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.math.Position
import io.github.sceneview.verticalFovDegreesForFocalLength
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.tan

/**
 * A pinhole camera projecting into the area `contentPadding` leaves free, for the tests of the
 * demos' home shots (#4326): the lens's vertical field spans that area's height whatever it is,
 * and its horizontal field follows from the area's [aspect].
 *
 * Positions come out in the area's own frame: `(0, 0)` at its centre, `±1` at its edges.
 */
internal class BandLens(
    private val eye: Position,
    target: Position,
    focalLengthMm: Double,
    private val aspect: Float,
) {
    private val forward = normalize(target - eye)
    private val right = normalize(cross(forward, Float3(0f, 1f, 0f)))
    private val up = cross(right, forward)
    private val tanY = tan(Math.toRadians(verticalFovDegreesForFocalLength(focalLengthMm)) / 2.0)

    /** Horizontal field of view, in degrees. */
    val horizontalFieldDegrees: Double = Math.toDegrees(2.0 * atan(tanY * aspect))

    /** Where [point] is drawn: `x` then `y`, each in `-1..1` when it is inside the free area. */
    fun project(point: Position): Pair<Double, Double> {
        val fromEye = point - eye
        val depth = dot(fromEye, forward).toDouble()
        check(depth > 0.0) { "$point is behind the camera" }
        return dot(fromEye, right) / (depth * tanY * aspect) to dot(fromEye, up) / (depth * tanY)
    }

    /** Whether [point] is drawn inside the free area. */
    fun holds(point: Position): Boolean {
        val (x, y) = project(point)
        return abs(x) <= 1.0 && abs(y) <= 1.0
    }

    /**
     * Height on screen of a segment from [bottom] up to [top], as a share of the free area's
     * height.
     */
    fun heightShare(bottom: Position, top: Position): Double =
        (project(top).second - project(bottom).second) / 2.0

    /**
     * Width on screen of a segment from [left] to [right], as a share of the free area's
     * **height** — the unit that does not change with the aspect.
     */
    fun widthInHeights(left: Position, right: Position): Double =
        (project(right).first - project(left).first) * aspect / 2.0
}
