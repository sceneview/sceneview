package io.github.sceneview.math

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.length
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SplinesTest {

    // --- Catmull-Rom ---

    @Test
    fun catmullRomAtT0ReturnsP1() {
        val p0 = Float3(0f, 0f, 0f)
        val p1 = Float3(1f, 0f, 0f)
        val p2 = Float3(2f, 0f, 0f)
        val p3 = Float3(3f, 0f, 0f)
        val result = catmullRom(p0, p1, p2, p3, 0f)
        assertEquals(1f, result.x, 1e-5f)
        assertEquals(0f, result.y, 1e-5f)
    }

    @Test
    fun catmullRomAtT1ReturnsP2() {
        val p0 = Float3(0f, 0f, 0f)
        val p1 = Float3(1f, 0f, 0f)
        val p2 = Float3(2f, 0f, 0f)
        val p3 = Float3(3f, 0f, 0f)
        val result = catmullRom(p0, p1, p2, p3, 1f)
        assertEquals(2f, result.x, 1e-5f)
    }

    @Test
    fun catmullRomMidpointOnLine() {
        val p0 = Float3(0f)
        val p1 = Float3(1f, 0f, 0f)
        val p2 = Float3(2f, 0f, 0f)
        val p3 = Float3(3f, 0f, 0f)
        val mid = catmullRom(p0, p1, p2, p3, 0.5f)
        assertEquals(1.5f, mid.x, 1e-4f)
    }

    @Test
    fun catmullRomSplineReturnsCorrectPointCount() {
        val points = listOf(
            Float3(0f), Float3(1f, 0f, 0f),
            Float3(2f, 1f, 0f), Float3(3f, 0f, 0f)
        )
        val spline = catmullRomSpline(points, segments = 10)
        // 1 span * 10 segments + 1 final point = 11
        assertEquals(11, spline.size)
    }

    @Test
    fun catmullRomSplineMultiSpan() {
        val points = listOf(
            Float3(0f), Float3(1f, 0f, 0f),
            Float3(2f, 0f, 0f), Float3(3f, 0f, 0f),
            Float3(4f, 0f, 0f)
        )
        val spline = catmullRomSpline(points, segments = 8)
        // 2 spans * 8 segments + 1 = 17
        assertEquals(17, spline.size)
    }

    @Test
    fun catmullRomSplineRequiresFourPoints() {
        var threw = false
        try { catmullRomSpline(listOf(Float3(0f), Float3(1f, 0f, 0f), Float3(2f, 0f, 0f))) }
        catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun catmullRomEndpointsHoldForEveryAlpha() {
        // Endpoints must interpolate p1 (t=0) and p2 (t=1) regardless of the
        // knot parameterization — true for uniform, centripetal and chordal.
        val p0 = Float3(0f, 0f, 0f)
        val p1 = Float3(1f, 2f, 0f)
        val p2 = Float3(2f, -1f, 1f)
        val p3 = Float3(4f, 0f, 0f)
        for (alpha in listOf(0f, 0.5f, 1f)) {
            val start = catmullRom(p0, p1, p2, p3, 0f, alpha)
            val end = catmullRom(p0, p1, p2, p3, 1f, alpha)
            assertEquals(p1.x, start.x, 1e-4f, "alpha=$alpha start.x")
            assertEquals(p1.y, start.y, 1e-4f, "alpha=$alpha start.y")
            assertEquals(p2.x, end.x, 1e-4f, "alpha=$alpha end.x")
            assertEquals(p2.y, end.y, 1e-4f, "alpha=$alpha end.y")
        }
    }

    @Test
    fun catmullRomAlphaChangesTheCurve() {
        // A sharp, non-uniformly-spaced control polygon: the centripetal (0.5) and
        // chordal (1.0) parameterizations must yield a different midpoint than the
        // uniform (0.0) one. If alpha were dead code all three would be identical.
        val p0 = Float3(0f, 0f, 0f)
        val p1 = Float3(1f, 0f, 0f)
        val p2 = Float3(1.05f, 3f, 0f)
        val p3 = Float3(4f, 3f, 0f)
        val uniform = catmullRom(p0, p1, p2, p3, 0.5f, alpha = 0f)
        val centripetal = catmullRom(p0, p1, p2, p3, 0.5f, alpha = 0.5f)
        val chordal = catmullRom(p0, p1, p2, p3, 0.5f, alpha = 1f)
        assertTrue(
            length(uniform - centripetal) > 1e-3f,
            "centripetal alpha must change the curve vs uniform"
        )
        assertTrue(
            length(centripetal - chordal) > 1e-3f,
            "chordal alpha must change the curve vs centripetal"
        )
    }

    @Test
    fun catmullRomCentripetalAvoidsCusp() {
        // The classic centripetal Catmull-Rom property: on a control polygon that
        // makes the uniform spline loop/overshoot, the centripetal spline stays
        // within a tighter bound. Here uniform overshoots past x of p1/p2; the
        // centripetal sample stays closer to the polygon.
        val p0 = Float3(0f, 0f, 0f)
        val p1 = Float3(10f, 0f, 0f)
        val p2 = Float3(10.5f, 5f, 0f)
        val p3 = Float3(11f, 0f, 0f)
        val uniformMax = (0..20).maxOf { abs(catmullRom(p0, p1, p2, p3, it / 20f, 0f).x - 10.25f) }
        val centripetalMax =
            (0..20).maxOf { abs(catmullRom(p0, p1, p2, p3, it / 20f, 0.5f).x - 10.25f) }
        assertTrue(
            centripetalMax <= uniformMax + 1e-4f,
            "centripetal should not overshoot more than uniform"
        )
    }

    @Test
    fun catmullRomSplineForwardsAlpha() {
        // catmullRomSpline must forward alpha to catmullRom — sampling the same
        // polygon with two alphas must produce different sample sets.
        val points = listOf(
            Float3(0f, 0f, 0f), Float3(1f, 0f, 0f),
            Float3(1.1f, 4f, 0f), Float3(5f, 4f, 0f)
        )
        val uniform = catmullRomSpline(points, segments = 8, alpha = 0f)
        val centripetal = catmullRomSpline(points, segments = 8, alpha = 0.5f)
        assertEquals(uniform.size, centripetal.size)
        val differs = uniform.indices.any { length(uniform[it] - centripetal[it]) > 1e-3f }
        assertTrue(differs, "catmullRomSpline must forward alpha")
    }

    @Test
    fun catmullRomHandlesCoincidentControlPoints() {
        // Degenerate input (duplicated points) collapses a knot interval to zero;
        // the non-uniform path must not divide by zero / produce NaN.
        val p = Float3(2f, 2f, 0f)
        val result = catmullRom(p, p, p, p, 0.5f, alpha = 0.5f)
        assertTrue(result.x.isFinite() && result.y.isFinite() && result.z.isFinite())
        assertEquals(2f, result.x, 1e-4f)
    }

    // --- Cubic Bezier ---

    @Test
    fun cubicBezierAtT0ReturnsP0() {
        val p0 = Float3(0f)
        val p3 = Float3(1f, 0f, 0f)
        val result = cubicBezier(p0, Float3(0.3f, 0.5f, 0f), Float3(0.7f, 0.5f, 0f), p3, 0f)
        assertEquals(0f, result.x, 1e-5f)
    }

    @Test
    fun cubicBezierAtT1ReturnsP3() {
        val p0 = Float3(0f)
        val p3 = Float3(1f, 0f, 0f)
        val result = cubicBezier(p0, Float3(0.3f, 0.5f, 0f), Float3(0.7f, 0.5f, 0f), p3, 1f)
        assertEquals(1f, result.x, 1e-5f)
    }

    @Test
    fun cubicBezierStraightLine() {
        val p0 = Float3(0f)
        val p1 = Float3(1f / 3f, 0f, 0f)
        val p2 = Float3(2f / 3f, 0f, 0f)
        val p3 = Float3(1f, 0f, 0f)
        val mid = cubicBezier(p0, p1, p2, p3, 0.5f)
        assertEquals(0.5f, mid.x, 1e-4f)
    }

    @Test
    fun cubicBezierTangentNonZero() {
        val tan = cubicBezierTangent(
            Float3(0f), Float3(0f, 1f, 0f), Float3(1f, 1f, 0f), Float3(1f, 0f, 0f), 0.5f
        )
        assertTrue(length(tan) > 0.01f, "Tangent should be non-zero")
    }

    @Test
    fun cubicBezierSplinePointCount() {
        val spline = cubicBezierSpline(
            Float3(0f), Float3(0f, 1f, 0f), Float3(1f, 1f, 0f), Float3(1f, 0f, 0f),
            segments = 20
        )
        assertEquals(21, spline.size)
    }

    // --- Quadratic Bezier ---

    @Test
    fun quadraticBezierEndpoints() {
        val p0 = Float3(0f)
        val p1 = Float3(0.5f, 1f, 0f)
        val p2 = Float3(1f, 0f, 0f)
        val start = quadraticBezier(p0, p1, p2, 0f)
        val end = quadraticBezier(p0, p1, p2, 1f)
        assertEquals(0f, start.x, 1e-5f)
        assertEquals(1f, end.x, 1e-5f)
    }
}
