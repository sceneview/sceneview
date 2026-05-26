package io.github.sceneview.math

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.distance
import dev.romainguy.kotlin.math.normalize
import kotlin.math.pow

/**
 * Catmull-Rom spline interpolation between four control points.
 *
 * Given points p0, p1, p2, p3, this evaluates the spline segment between p1 and p2
 * at parameter [t] in [0..1]. The [alpha] parameter controls the knot parameterization:
 * - 0.0 = uniform (matrix formulation; may overshoot or form cusps near sharp turns)
 * - 0.5 = centripetal (recommended, avoids cusps and self-intersections)
 * - 1.0 = chordal
 *
 * The non-uniform case (alpha != 0) is evaluated with the Barry-Goldman pyramidal
 * recurrence over knots `t_i` spaced by `|p_{i+1} - p_i|^alpha`, so that [alpha]
 * genuinely changes the shape of the curve.
 *
 * @param p0 Control point before the segment start.
 * @param p1 Segment start point (t=0 returns this).
 * @param p2 Segment end point (t=1 returns this).
 * @param p3 Control point after the segment end.
 * @param t Parameter in [0..1].
 * @param alpha Knot parameterization. Default 0.5 (centripetal).
 * @return Interpolated position on the spline.
 */
fun catmullRom(
    p0: Float3, p1: Float3, p2: Float3, p3: Float3,
    t: Float, alpha: Float = 0.5f
): Float3 {
    if (alpha == 0f) {
        // Uniform Catmull-Rom matrix formulation.
        val t2 = t * t
        val t3 = t2 * t
        return Float3(
            x = 0.5f * ((2f * p1.x) +
                    (-p0.x + p2.x) * t +
                    (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * t2 +
                    (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * t3),
            y = 0.5f * ((2f * p1.y) +
                    (-p0.y + p2.y) * t +
                    (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 +
                    (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3),
            z = 0.5f * ((2f * p1.z) +
                    (-p0.z + p2.z) * t +
                    (2f * p0.z - 5f * p1.z + 4f * p2.z - p3.z) * t2 +
                    (-p0.z + 3f * p1.z - 3f * p2.z + p3.z) * t3)
        )
    }

    // Non-uniform (centripetal/chordal) parameterization via the Barry-Goldman
    // pyramidal algorithm. Knots are spaced by the alpha-power of the chord length.
    fun knot(ti: Float, a: Float3, b: Float3): Float = ti + distance(a, b).pow(alpha)

    val t0 = 0f
    val t1 = knot(t0, p0, p1)
    val t2 = knot(t1, p1, p2)
    val t3 = knot(t2, p2, p3)

    // Degenerate (coincident) control points collapse a knot interval to zero;
    // fall back to uniform spacing for that interval to avoid division by zero.
    val k0 = t0
    val k1 = if (t1 > t0) t1 else t0 + 1f
    val k2 = if (t2 > k1) t2 else k1 + 1f
    val k3 = if (t3 > k2) t3 else k2 + 1f

    // Evaluate the spline between p1 and p2: remap t in [0,1] onto [k1, k2].
    val tt = k1 + t * (k2 - k1)

    fun lerp(a: Float3, b: Float3, ta: Float, tb: Float, x: Float): Float3 {
        val w = (tb - x) / (tb - ta)
        return a * w + b * (1f - w)
    }

    val a1 = lerp(p0, p1, k0, k1, tt)
    val a2 = lerp(p1, p2, k1, k2, tt)
    val a3 = lerp(p2, p3, k2, k3, tt)
    val b1 = lerp(a1, a2, k0, k2, tt)
    val b2 = lerp(a2, a3, k1, k3, tt)
    return lerp(b1, b2, k1, k2, tt)
}

/**
 * Evaluates a Catmull-Rom spline through a list of control points.
 *
 * Returns [segments] + 1 evenly-spaced sample points along the entire spline.
 * The spline passes through every control point. The first and last control points
 * are used only for tangent computation (the spline spans from points[1] to points[n-2]).
 * For a spline that starts at points[0], pass the first point twice at the beginning
 * and the last point twice at the end.
 *
 * @param points At least 4 control points.
 * @param segments Number of line segments per span (between consecutive interior points).
 * @param alpha Knot parameterization forwarded to [catmullRom]. Default 0.5 (centripetal);
 * 0.0 = uniform, 1.0 = chordal.
 * @return List of sampled positions along the spline.
 */
fun catmullRomSpline(
    points: List<Float3>,
    segments: Int = 16,
    alpha: Float = 0.5f
): List<Float3> {
    require(points.size >= 4) { "Catmull-Rom spline requires at least 4 control points" }
    require(segments >= 1) { "segments must be >= 1" }

    val result = mutableListOf<Float3>()
    for (i in 0 until points.size - 3) {
        val p0 = points[i]
        val p1 = points[i + 1]
        val p2 = points[i + 2]
        val p3 = points[i + 3]
        for (s in 0 until segments) {
            val t = s.toFloat() / segments
            result.add(catmullRom(p0, p1, p2, p3, t, alpha))
        }
    }
    // Add the final point
    result.add(catmullRom(
        points[points.size - 4], points[points.size - 3],
        points[points.size - 2], points[points.size - 1], 1f, alpha
    ))
    return result
}

/**
 * Cubic Bezier curve evaluation.
 *
 * Given four control points, evaluates the Bezier curve at parameter [t] in [0..1].
 * The curve passes through p0 (at t=0) and p3 (at t=1); p1 and p2 are control handles.
 *
 * @param p0 Start point.
 * @param p1 First control point (handle).
 * @param p2 Second control point (handle).
 * @param p3 End point.
 * @param t Parameter in [0..1].
 * @return Position on the Bezier curve.
 */
fun cubicBezier(
    p0: Float3, p1: Float3, p2: Float3, p3: Float3,
    t: Float
): Float3 {
    val u = 1f - t
    val u2 = u * u
    val u3 = u2 * u
    val t2 = t * t
    val t3 = t2 * t

    return Float3(
        x = u3 * p0.x + 3f * u2 * t * p1.x + 3f * u * t2 * p2.x + t3 * p3.x,
        y = u3 * p0.y + 3f * u2 * t * p1.y + 3f * u * t2 * p2.y + t3 * p3.y,
        z = u3 * p0.z + 3f * u2 * t * p1.z + 3f * u * t2 * p2.z + t3 * p3.z
    )
}

/**
 * Tangent (first derivative) of a cubic Bezier curve at parameter [t].
 *
 * @return Non-normalized tangent vector at the given parameter.
 */
fun cubicBezierTangent(
    p0: Float3, p1: Float3, p2: Float3, p3: Float3,
    t: Float
): Float3 {
    val u = 1f - t
    val u2 = u * u
    val t2 = t * t

    return Float3(
        x = 3f * u2 * (p1.x - p0.x) + 6f * u * t * (p2.x - p1.x) + 3f * t2 * (p3.x - p2.x),
        y = 3f * u2 * (p1.y - p0.y) + 6f * u * t * (p2.y - p1.y) + 3f * t2 * (p3.y - p2.y),
        z = 3f * u2 * (p1.z - p0.z) + 6f * u * t * (p2.z - p1.z) + 3f * t2 * (p3.z - p2.z)
    )
}

/**
 * Samples a cubic Bezier curve at evenly spaced parameter values.
 *
 * @param p0 Start point.
 * @param p1 First control point.
 * @param p2 Second control point.
 * @param p3 End point.
 * @param segments Number of segments (returns segments+1 points).
 * @return List of sampled positions.
 */
fun cubicBezierSpline(
    p0: Float3, p1: Float3, p2: Float3, p3: Float3,
    segments: Int = 16
): List<Float3> {
    require(segments >= 1) { "segments must be >= 1" }
    return (0..segments).map { i ->
        cubicBezier(p0, p1, p2, p3, i.toFloat() / segments)
    }
}

/**
 * Quadratic Bezier curve evaluation (3 control points).
 *
 * @param p0 Start point.
 * @param p1 Control point (handle).
 * @param p2 End point.
 * @param t Parameter in [0..1].
 * @return Position on the curve.
 */
fun quadraticBezier(
    p0: Float3, p1: Float3, p2: Float3,
    t: Float
): Float3 {
    val u = 1f - t
    return Float3(
        x = u * u * p0.x + 2f * u * t * p1.x + t * t * p2.x,
        y = u * u * p0.y + 2f * u * t * p1.y + t * t * p2.y,
        z = u * u * p0.z + 2f * u * t * p1.z + t * t * p2.z
    )
}
