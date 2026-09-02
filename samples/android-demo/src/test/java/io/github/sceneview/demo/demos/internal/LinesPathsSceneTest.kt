package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * JVM coverage for [LinesPathsScene] — the curves, arc-length sampling and framing behind the
 * `lines-paths` demo (#3425).
 *
 * Three families of claim are pinned here, because none of them is observable from a screenshot
 * that merely "renders something":
 *
 *  1. **Constant sample counts.** Every curve kind returns exactly the same number of points, so
 *     the tubes on screen never reallocate their Filament buffers. A change that broke this
 *     would look fine and quietly leak GPU memory on every curve switch.
 *  2. **Arc-length honesty.** The marker and the dashes are placed by distance travelled, not by
 *     sample index — the difference is invisible on a circle and glaring on a spline.
 *  3. **Framing.** The loop must clear a phone-portrait frame at the widest stroke, the same
 *     arithmetic [GeometryLayout] owns.
 */
class LinesPathsSceneTest {

    // ── The point set ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `there are eight control points`() {
        assertEquals(LinesPathsScene.CONTROL_COUNT, LinesPathsScene.controlPoints.size)
    }

    @Test
    fun `control points sit on a circle of the loop radius`() {
        LinesPathsScene.controlPoints.forEach { point ->
            assertEquals(
                LinesPathsScene.LOOP_RADIUS,
                hypot(point.x, point.z),
                1e-4f,
            )
        }
    }

    @Test
    fun `the loop is three-dimensional, not a flat ring`() {
        val ys = LinesPathsScene.controlPoints.map { it.y }
        assertTrue("the loop is flat — it would read as a circle", ys.max() - ys.min() > 0.2f)
    }

    @Test
    fun `the ground track is the control points flattened to one plane`() {
        val track = LinesPathsScene.groundTrack
        assertEquals(LinesPathsScene.controlPoints.size, track.size)
        track.forEachIndexed { i, point ->
            assertEquals(LinesPathsScene.controlPoints[i].x, point.x, 0f)
            assertEquals(LinesPathsScene.controlPoints[i].z, point.z, 0f)
            assertEquals(LinesPathsScene.GROUND_Y, point.y, 0f)
        }
    }

    @Test
    fun `the ground track keeps its corners — it is a polyline, not a resampled curve`() {
        assertEquals(LinesPathsScene.CONTROL_COUNT, LinesPathsScene.groundTrack.size)
    }

    // ── Constant sample counts ────────────────────────────────────────────────────────────────

    @Test
    fun `every curve kind produces the same number of samples`() {
        CurveKind.entries.forEach { kind ->
            assertEquals(
                "$kind changed the point count — the tube would reallocate its buffers",
                LinesPathsScene.ROUTE_SAMPLES,
                LinesPathsScene.route(kind).size,
            )
        }
    }

    @Test
    fun `route samples is control points times samples per span`() {
        assertEquals(
            LinesPathsScene.CONTROL_COUNT * LinesPathsScene.SAMPLES_PER_SPAN,
            LinesPathsScene.ROUTE_SAMPLES,
        )
    }

    @Test
    fun `the three curve kinds are genuinely different shapes`() {
        val linear = LinesPathsScene.route(CurveKind.Linear)
        val rounded = LinesPathsScene.route(CurveKind.Rounded)
        val smooth = LinesPathsScene.route(CurveKind.Smooth)
        assertNotEquals(linear, rounded)
        assertNotEquals(rounded, smooth)
        assertNotEquals(linear, smooth)
    }

    // ── Linear ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the polyline passes exactly through every control point`() {
        val linear = LinesPathsScene.route(CurveKind.Linear)
        LinesPathsScene.controlPoints.forEachIndexed { i, control ->
            val sample = linear[i * LinesPathsScene.SAMPLES_PER_SPAN]
            assertEquals("control $i x", control.x, sample.x, 1e-5f)
            assertEquals("control $i y", control.y, sample.y, 1e-5f)
            assertEquals("control $i z", control.z, sample.z, 1e-5f)
        }
    }

    @Test
    fun `polyline samples between two control points are collinear with them`() {
        // What makes the "Polyline" mode honest: the straight runs are actually straight, so the
        // subdivision only adds vertices, it does not bend the shape.
        val linear = LinesPathsScene.linear(
            listOf(
                Position(0f, 0f, 0f),
                Position(3f, 0f, 0f),
                Position(3f, 3f, 0f),
            ),
            segments = 3,
        )
        assertEquals(9, linear.size)
        assertEquals(1f, linear[1].x, 1e-5f)
        assertEquals(2f, linear[2].x, 1e-5f)
        assertEquals(0f, linear[1].y, 1e-5f)
    }

    // ── Rounded ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `rounding cuts every corner — no sample reaches the control point itself`() {
        val rounded = LinesPathsScene.route(CurveKind.Rounded)
        LinesPathsScene.controlPoints.forEach { control ->
            val nearest = rounded.minOf { distance(it, control) }
            assertTrue(
                "a rounded route still touches a corner — the fillet did nothing",
                nearest > 1e-3f,
            )
        }
    }

    @Test
    fun `a rounded corner stays inside the triangle it cuts`() {
        // The fillet is a quadratic Bezier over (entry, corner, exit): by the convex-hull
        // property every sample must lie within that triangle, so rounding can never bulge
        // outside the original polyline.
        val rounded = LinesPathsScene.route(CurveKind.Rounded)
        val maxRadius = rounded.maxOf { hypot(it.x, it.z) }
        assertTrue(
            "rounding pushed the route outside the control circle",
            maxRadius <= LinesPathsScene.LOOP_RADIUS + 1e-3f,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a fillet that reaches past half a segment is rejected`() {
        LinesPathsScene.rounded(LinesPathsScene.controlPoints, fraction = 0.6f)
    }

    // ── Smooth ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the spline passes through every control point`() {
        val smooth = LinesPathsScene.route(CurveKind.Smooth)
        LinesPathsScene.controlPoints.forEach { control ->
            val nearest = smooth.minOf { distance(it, control) }
            assertTrue("the spline misses a control point by $nearest", nearest < 1e-3f)
        }
    }

    @Test
    fun `the spline does not repeat its first point at the end`() {
        // The closed tube joins the ends itself; a duplicated point would give it a zero-length
        // final segment and an undefined tangent there.
        val smooth = LinesPathsScene.route(CurveKind.Smooth)
        assertTrue(
            "the spline closes on itself — the tube would see a zero-length segment",
            distance(smooth.first(), smooth.last()) > 1e-3f,
        )
    }

    @Test
    fun `only the polyline has corners`() {
        // The measure that actually separates the three chips: the sharpest direction change
        // between consecutive samples. A polyline turns through a full exterior angle at each of
        // its eight control points; the rounded and spline routes spread the same total turning
        // over their samples, so no single joint is sharp. This is what a user sees, and it is
        // what a regression in either curve function would break first.
        assertTrue(
            "the polyline has no corners — it is not a polyline",
            maxTurnDegrees(CurveKind.Linear) > 30f,
        )
        assertTrue(
            "the spline has a corner in it",
            maxTurnDegrees(CurveKind.Smooth) < 10f,
        )
        assertTrue(
            "rounding left a corner uncut",
            maxTurnDegrees(CurveKind.Rounded) < 15f,
        )
    }

    @Test
    fun `no curve leaves the control circle`() {
        // The polyline and the spline touch it (they interpolate their control points); rounding
        // stays strictly inside because a quadratic Bezier never leaves the hull of its three
        // points. Nothing may exceed it, or the framing arithmetic — which assumes a half-extent
        // of LOOP_RADIUS plus the stroke — would under-estimate the scene and clip it.
        CurveKind.entries.forEach { kind ->
            val maxRadius = LinesPathsScene.route(kind).maxOf { hypot(it.x, it.z) }
            assertTrue(
                "$kind reaches $maxRadius, outside the ${LinesPathsScene.LOOP_RADIUS} circle",
                maxRadius <= LinesPathsScene.LOOP_RADIUS + 1e-4f,
            )
        }
    }

    // ── Arc-length sampling ───────────────────────────────────────────────────────────────────

    @Test
    fun `total length of a unit square loop is four`() {
        assertEquals(4f, LinesPathsScene.totalLength(unitSquare(), closed = true), 1e-4f)
    }

    @Test
    fun `total length of an open unit square is three`() {
        assertEquals(3f, LinesPathsScene.totalLength(unitSquare(), closed = false), 1e-4f)
    }

    @Test
    fun `sampling at zero returns the first point`() {
        val at = LinesPathsScene.sampleAt(unitSquare(), closed = true, distance = 0f)
        assertEquals(0f, distance(at, unitSquare().first()), 1e-5f)
    }

    @Test
    fun `sampling wraps around a closed path`() {
        val loop = unitSquare()
        val at = LinesPathsScene.sampleAt(loop, closed = true, distance = 4f)
        assertEquals(0f, distance(at, loop.first()), 1e-4f)
    }

    @Test
    fun `a negative distance folds back onto a closed path`() {
        // The trail starts *behind* the marker, so this path is exercised every frame.
        val loop = unitSquare()
        val behind = LinesPathsScene.sampleAt(loop, closed = true, distance = -0.5f)
        val ahead = LinesPathsScene.sampleAt(loop, closed = true, distance = 3.5f)
        assertEquals(0f, distance(behind, ahead), 1e-4f)
    }

    @Test
    fun `sampling clamps at both ends of an open path`() {
        val open = unitSquare()
        val before = LinesPathsScene.sampleAt(open, closed = false, distance = -10f)
        val after = LinesPathsScene.sampleAt(open, closed = false, distance = 10f)
        assertEquals(0f, distance(before, open.first()), 1e-5f)
        assertEquals(0f, distance(after, open.last()), 1e-5f)
    }

    @Test
    fun `samples advance at constant speed, whatever the curve`() {
        // The point of arc-length sampling: equal distance steps must produce equal-length
        // moves. A parameter-indexed marker fails this badly on a spline.
        CurveKind.entries.forEach { kind ->
            val route = LinesPathsScene.route(kind)
            val total = LinesPathsScene.totalLength(route, closed = true)
            val step = total / 64f
            val moves = (0 until 64).map { i ->
                distance(
                    LinesPathsScene.sampleAt(route, closed = true, distance = i * step),
                    LinesPathsScene.sampleAt(route, closed = true, distance = (i + 1) * step),
                )
            }
            // Chord vs arc: a step across a curved span measures slightly short, never long.
            moves.forEach { move ->
                assertTrue("$kind: a step of $move is not ~$step", abs(move - step) < step * 0.05f)
            }
        }
    }

    // ── Spans and dashes ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a span returns exactly the sample count asked for`() {
        val span = LinesPathsScene.span(unitSquare(), closed = true, 0f, 1f, samples = 7)
        assertEquals(7, span.size)
    }

    @Test
    fun `a span covers the length asked for`() {
        val span = LinesPathsScene.span(unitSquare(), closed = true, 0f, 1f, samples = 33)
        val walked = LinesPathsScene.totalLength(span, closed = false)
        assertEquals(1f, walked, 1e-2f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a span of one sample is rejected`() {
        LinesPathsScene.span(unitSquare(), closed = true, 0f, 1f, samples = 1)
    }

    @Test
    fun `dashes are evenly pitched and all the same length`() {
        val loop = unitSquare()
        val dashes = LinesPathsScene.dashes(
            points = loop, closed = true, count = 8, dutyCycle = 0.5f, phase = 0f, samples = 3,
        )
        assertEquals(8, dashes.size)
        val lengths = dashes.map { LinesPathsScene.totalLength(it, closed = false) }
        val pitch = 4f / 8f
        lengths.forEach { assertEquals(pitch * 0.5f, it, 1e-2f) }
    }

    @Test
    fun `every dash has the demo's fixed sample count`() {
        val dashes = LinesPathsScene.dashes(
            points = LinesPathsScene.groundTrack,
            closed = true,
            count = LinesPathsScene.DASH_COUNT,
            dutyCycle = LinesPathsScene.DASH_DUTY_CYCLE,
            phase = 0.37f,
            samples = LinesPathsScene.DASH_SAMPLES,
        )
        assertEquals(LinesPathsScene.DASH_COUNT, dashes.size)
        dashes.forEach { assertEquals(LinesPathsScene.DASH_SAMPLES, it.size) }
    }

    @Test
    fun `advancing the phase by one pitch reproduces the pattern`() {
        // What makes a 0..1 animation loop seamless rather than jumping at the wrap.
        val loop = unitSquare()
        val at0 = LinesPathsScene.dashes(loop, true, 8, 0.5f, 0f, 3)
        val at1 = LinesPathsScene.dashes(loop, true, 8, 0.5f, 1f, 3)
        at0.forEachIndexed { i, dash ->
            // Dash i at phase 0 is dash i-1 at phase 1 — the whole set shifted by one slot.
            val shifted = at1[(i - 1 + 8) % 8]
            dash.forEachIndexed { j, point ->
                assertEquals("dash $i sample $j", 0f, distance(point, shifted[j]), 1e-4f)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a duty cycle of one — no gaps — is rejected`() {
        LinesPathsScene.dashes(unitSquare(), true, 8, dutyCycle = 1f, phase = 0f, samples = 3)
    }

    // ── Stroke ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `stroke millimetres convert to a radius in metres`() {
        // 16 mm of stroke *diameter* is 8 mm of tube radius.
        assertEquals(0.008f, LinesPathsScene.strokeRadius(16f), 1e-6f)
    }

    @Test
    fun `the default stroke is inside the slider range`() {
        assertTrue(LinesPathsScene.DEFAULT_STROKE_MM >= LinesPathsScene.MIN_STROKE_MM)
        assertTrue(LinesPathsScene.DEFAULT_STROKE_MM <= LinesPathsScene.MAX_STROKE_MM)
    }

    @Test
    fun `even the thinnest stroke is thicker than a device pixel`() {
        // The regression this whole rebuild exists to prevent. At CAMERA_DISTANCE a
        // 1080-wide portrait frame spans 2 * frameHalfWidth metres, so one pixel is that
        // over 1080. The thinnest stroke must be worth several of them.
        val frameWidthMetres = 2f * GeometryLayout.frameHalfWidth(
            LinesPathsScene.CAMERA_DISTANCE,
            GeometryLayout.PHONE_PORTRAIT_ASPECT,
        )
        val metresPerPixel = frameWidthMetres / 1080f
        val thinnest = 2f * LinesPathsScene.strokeRadius(LinesPathsScene.MIN_STROKE_MM)
        assertTrue(
            "the thinnest stroke is ${thinnest / metresPerPixel} px — barely better than LINES",
            thinnest / metresPerPixel > 4f,
        )
    }

    @Test
    fun `the default stroke reads at nearly ten pixels`() {
        val frameWidthMetres = 2f * GeometryLayout.frameHalfWidth(
            LinesPathsScene.CAMERA_DISTANCE,
            GeometryLayout.PHONE_PORTRAIT_ASPECT,
        )
        val metresPerPixel = frameWidthMetres / 1080f
        val default = 2f * LinesPathsScene.strokeRadius(LinesPathsScene.DEFAULT_STROKE_MM)
        val pixels = default / metresPerPixel
        assertTrue("the default stroke is only $pixels px wide", pixels > 8f)
    }

    // ── Framing ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the orbit radius and height give exactly the camera distance`() {
        val radius = LinesPathsScene.orbitRadius()
        val height = LinesPathsScene.orbitHeight()
        assertEquals(LinesPathsScene.CAMERA_DISTANCE, hypot(radius, height), 1e-3f)
    }

    @Test
    fun `the loop fills most of a phone-portrait frame without touching the edges`() {
        val fill = LinesPathsScene.horizontalFillRatio(
            LinesPathsScene.CAMERA_DISTANCE,
            GeometryLayout.PHONE_PORTRAIT_ASPECT,
            LinesPathsScene.strokeRadius(LinesPathsScene.DEFAULT_STROKE_MM),
        )
        assertTrue("the loop fills only ${fill * 100}% — too small to read", fill > 0.6f)
        assertTrue("the loop fills ${fill * 100}% — it is clipped", fill < 0.85f)
    }

    @Test
    fun `the widest stroke still clears the narrowest expected frame`() {
        val fill = LinesPathsScene.horizontalFillRatio(
            LinesPathsScene.CAMERA_DISTANCE,
            GeometryLayout.NARROWEST_EXPECTED_ASPECT,
            LinesPathsScene.strokeRadius(LinesPathsScene.MAX_STROKE_MM),
        )
        assertTrue("clipped at the widest stroke: ${fill * 100}%", fill < 1f)
    }

    @Test
    fun `the scene lift centres the composition on the camera axis`() {
        // Route top and ground track bottom must straddle y = 0 once lifted.
        val top = LinesPathsScene.LOOP_WAVE + LinesPathsScene.SCENE_LIFT
        val bottom = LinesPathsScene.GROUND_Y + LinesPathsScene.SCENE_LIFT
        assertEquals("the scene is not vertically centred", 0f, top + bottom, 0.02f)
    }

    // ── QA determinism ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the frozen QA phase does not park the marker on a control point`() {
        // A marker sitting on top of a control-point sphere would hide it, and the render
        // golden would silently stop covering one of the four elements on screen.
        val route = LinesPathsScene.route(CurveKind.Smooth)
        val total = LinesPathsScene.totalLength(route, closed = true)
        val marker = LinesPathsScene.sampleAt(
            route, closed = true, distance = LinesPathsScene.STATIC_PROGRESS * total,
        )
        val nearest = LinesPathsScene.controlPoints.minOf { distance(it, marker) }
        assertTrue(
            "the frozen marker sits $nearest m from a control point — it would hide it",
            nearest > LinesPathsScene.MARKER_RADIUS + LinesPathsScene.POINT_RADIUS,
        )
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────────────────────

    private fun unitSquare() = listOf(
        Position(0f, 0f, 0f),
        Position(1f, 0f, 0f),
        Position(1f, 0f, 1f),
        Position(0f, 0f, 1f),
    )

    /** Sharpest direction change, in degrees, between consecutive samples of a closed route. */
    private fun maxTurnDegrees(kind: CurveKind): Float {
        val route = LinesPathsScene.route(kind)
        val n = route.size
        return (0 until n).maxOf { i ->
            val a = direction(route[i], route[(i + 1) % n])
            val b = direction(route[(i + 1) % n], route[(i + 2) % n])
            val cosine = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f)
            Math.toDegrees(kotlin.math.acos(cosine).toDouble()).toFloat()
        }
    }

    private fun direction(a: Position, b: Position): FloatArray {
        val length = distance(a, b).coerceAtLeast(1e-9f)
        return floatArrayOf((b.x - a.x) / length, (b.y - a.y) / length, (b.z - a.z) / length)
    }

    private fun distance(a: Position, b: Position): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }
}
