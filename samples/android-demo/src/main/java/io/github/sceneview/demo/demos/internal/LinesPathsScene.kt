package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import io.github.sceneview.math.catmullRomSpline
import io.github.sceneview.math.quadraticBezier
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The three ways [LinesPathsScene] turns the same eight control points into a route, and the
 * chip label each one shows in [io.github.sceneview.demo.demos.LinesPathsDemo]'s picker.
 *
 * They are the three curve families a 3D app actually needs, in increasing order of smoothness:
 * a raw **polyline**, a polyline with **rounded corners** (quadratic Bezier fillets on straight
 * runs — the shape of a road or a wire loom), and an interpolating **spline** through every
 * point (centripetal Catmull-Rom). Picking between them is the demo's central lesson, so it is
 * a control rather than a hard-coded choice.
 */
internal enum class CurveKind(val label: String) {
    Linear("Polyline"),
    Rounded("Rounded"),
    Smooth("Spline"),
}

/**
 * Geometry, arc-length sampling and framing arithmetic behind
 * [io.github.sceneview.demo.demos.LinesPathsDemo].
 *
 * Everything here is pure Kotlin over [Position] — no Compose, no Filament — so
 * `LinesPathsSceneTest` can assert the curves close, the dashes stay inside the path and the
 * whole composition fits a phone-portrait frame, none of which is observable from a screenshot
 * that "renders something".
 *
 * ### What the scene is
 *
 * Eight control points on a circle of [LOOP_RADIUS], lifted by a two-period sine wave of
 * [LOOP_WAVE] so the loop is genuinely three-dimensional rather than a ring seen edge-on. From
 * them the demo draws four things, each demonstrating one primitive:
 *
 * | Element | What it shows |
 * |---|---|
 * | Route | a closed path — polyline, rounded or spline — swept as a tube |
 * | Marker + trail | a point and a sub-path sampled by **arc length** along that route |
 * | Ground track | a straight-segment polyline, drawn **dashed** and marching |
 * | Control points | the point set the whole thing is built from |
 *
 * ### Why arc length and not parameter
 *
 * A marker driven by "index into the sample list" crawls through the tight parts of a spline
 * and sprints through the straight ones, because Catmull-Rom samples are evenly spaced in
 * *parameter*, not in distance. [sampleAt] walks the polyline's cumulative length instead, so
 * the marker moves at constant speed and the dashes keep a constant pitch whichever curve is
 * selected — and [span] returns a **fixed** number of samples, which is what lets the demo
 * re-upload a dash every frame without reallocating its index buffer.
 *
 * ### Framing
 *
 * Same frustum model as [GeometryLayout] (which owns it): visible half-height at distance `d`
 * is `d · 12 / focalLength`. The route sweeps a circle about +Y, so its horizontal half-extent
 * is rotation-invariant and equals [LOOP_RADIUS] plus the stroke radius — the widest the scene
 * can ever be. [CAMERA_DISTANCE] is derived from that, and `LinesPathsSceneTest` pins the
 * margin it delivers.
 *
 * **Precondition:** the demo passes `autoCenterContent = false` and lifts the scene by
 * [SCENE_LIFT] itself. The dashes move every frame, so letting the library re-centre on a
 * bounding box that changes 60 times a second would make the whole composition breathe.
 */
internal object LinesPathsScene {

    // ── The point set ─────────────────────────────────────────────────────────────────────────

    /** Number of control points the route is built from. */
    const val CONTROL_COUNT = 8

    /** Radius of the control-point circle in the XZ plane, in metres. */
    const val LOOP_RADIUS = 0.62f

    /** Peak vertical excursion of the control points, in metres. */
    const val LOOP_WAVE = 0.20f

    /** Height of the dashed ground track below the loop's centre plane, in metres. */
    const val GROUND_Y = -0.42f

    /**
     * Vertical offset applied to the whole scene so its bounding box straddles the origin.
     *
     * The route spans ±[LOOP_WAVE] and the ground track sits at [GROUND_Y], so the union's
     * centre is `(LOOP_WAVE + GROUND_Y) / 2` ≈ −0.11 m; lifting by the negation of that puts
     * the composition on the camera's axis without asking the library to re-centre it.
     */
    const val SCENE_LIFT = 0.11f

    /**
     * The eight control points: a circle in XZ lifted by two full sine periods.
     *
     * Two periods (not one) so that every quarter of the loop reads differently from a 3/4
     * camera — one period gives a tilted ellipse, which is indistinguishable from a flat ring.
     */
    val controlPoints: List<Position> = List(CONTROL_COUNT) { i ->
        val angle = i.toFloat() / CONTROL_COUNT * TAU
        Position(
            x = cos(angle) * LOOP_RADIUS,
            y = sin(angle * 2f) * LOOP_WAVE,
            z = sin(angle) * LOOP_RADIUS,
        )
    }

    /**
     * The dashed ground track: the control points flattened onto `y = `[GROUND_Y].
     *
     * Deliberately *not* re-sampled — it stays an eight-segment polyline with visible corners,
     * so the screen shows a straight-segment polyline next to a smooth spline built from the
     * very same points.
     */
    val groundTrack: List<Position> = controlPoints.map { Position(it.x, GROUND_Y, it.z) }

    // ── Curves ────────────────────────────────────────────────────────────────────────────────

    /**
     * Samples every curve kind emits **per control point**.
     *
     * Deliberately shared by all three, so `route()` always returns exactly
     * `CONTROL_COUNT * SAMPLES_PER_SPAN` positions whichever kind is selected. That is not
     * cosmetic: a `Tube`'s Filament vertex buffer is allocated for a fixed vertex count, so a
     * curve change that kept the same count re-uploads into the buffers it already owns, while
     * one that changed it would need the whole renderable rebuilt. Switching curve type is the
     * demo's headline control — it has to be instant, and it has to not leak GPU buffers.
     */
    const val SAMPLES_PER_SPAN = 24

    /**
     * How far into each adjacent segment a [CurveKind.Rounded] fillet reaches, as a fraction of
     * that segment. Must stay below `0.5` or neighbouring fillets would overlap and the
     * "straight run between corners" the mode exists to show would disappear.
     */
    const val FILLET_FRACTION = 0.35f

    /** Total samples in a route, whatever its [CurveKind]. */
    const val ROUTE_SAMPLES = CONTROL_COUNT * SAMPLES_PER_SPAN

    /**
     * The closed route through [controlPoints] for a given [kind].
     *
     * The returned list is the *open* form of a closed loop — the first point is not repeated
     * at the end, because the tube that draws it is built with `closed = true` and joins the
     * ends itself. Every element of the demo that walks the route ([sampleAt], [span],
     * [dashes]) therefore also passes `closed = true`.
     *
     * Always [ROUTE_SAMPLES] long. See [SAMPLES_PER_SPAN].
     */
    fun route(kind: CurveKind): List<Position> = when (kind) {
        CurveKind.Linear -> linear(controlPoints, SAMPLES_PER_SPAN)
        CurveKind.Rounded -> rounded(controlPoints, FILLET_FRACTION, SAMPLES_PER_SPAN)
        CurveKind.Smooth -> smooth(controlPoints, SAMPLES_PER_SPAN)
    }

    /**
     * The closed polyline through [points], subdivided into [segments] evenly spaced samples per
     * side.
     *
     * Subdivision — rather than re-sampling the whole loop at a constant arc length — is what
     * keeps every control point *exactly* on a sample, so the corners stay sharp. A constant-
     * arc-length resampling would step over most corners and quietly round them off, turning the
     * one mode that is supposed to look like a polyline into a bad spline.
     */
    fun linear(points: List<Position>, segments: Int = SAMPLES_PER_SPAN): List<Position> {
        require(points.size >= 2) { "a polyline needs at least 2 control points" }
        require(segments >= 1) { "segments must be >= 1" }
        val count = points.size
        return buildList {
            for (i in 0 until count) {
                val from = points[i]
                val to = points[(i + 1) % count]
                // Half-open: the segment's end point is the next segment's start, so emitting it
                // here would duplicate every control point and desynchronise the sample count.
                for (s in 0 until segments) {
                    add(from + (to - from) * (s.toFloat() / segments))
                }
            }
        }
    }

    /**
     * A closed centripetal Catmull-Rom spline through every point of [points], [segments]
     * samples per span.
     *
     * `catmullRomSpline` needs one control point of context on each side and returns a curve
     * that *ends* on its last interior point, so the ring is padded by wrapping — last point in
     * front, first two behind. Its final sample lands back on `points[0]`, which the closed tube
     * would draw twice, so it is dropped.
     */
    fun smooth(points: List<Position>, segments: Int = SAMPLES_PER_SPAN): List<Position> {
        require(points.size >= 3) { "a closed spline needs at least 3 control points" }
        require(segments >= 1) { "segments must be >= 1" }
        val padded = listOf(points.last()) + points + listOf(points[0], points[1])
        return catmullRomSpline(padded, segments).dropLast(1)
    }

    /**
     * [points] with every corner replaced by a quadratic Bezier fillet, [segments] samples each.
     *
     * Each corner contributes its samples from [fraction] of the way back along the incoming
     * segment, through the corner itself as the Bezier handle, to [fraction] of the way along
     * the outgoing one. The straight runs between corners are left implicit — consecutive
     * fillets are joined by a single segment, which is exactly the straight line the mode is
     * meant to show.
     */
    fun rounded(
        points: List<Position>,
        fraction: Float = FILLET_FRACTION,
        segments: Int = SAMPLES_PER_SPAN,
    ): List<Position> {
        require(points.size >= 3) { "a closed rounded path needs at least 3 control points" }
        require(fraction > 0f && fraction < 0.5f) { "fraction must be in (0, 0.5), was $fraction" }
        require(segments >= 2) { "segments must be >= 2, was $segments" }
        val count = points.size
        return buildList {
            for (i in 0 until count) {
                val corner = points[i]
                val previous = points[(i - 1 + count) % count]
                val next = points[(i + 1) % count]
                val entry = corner + (previous - corner) * fraction
                val exit = corner + (next - corner) * fraction
                for (s in 0 until segments) {
                    add(quadraticBezier(entry, corner, exit, s.toFloat() / (segments - 1)))
                }
            }
        }
    }

    // ── Arc-length sampling ───────────────────────────────────────────────────────────────────

    /** Total length of the polyline through [points], including the closing segment when [closed]. */
    fun totalLength(points: List<Position>, closed: Boolean): Float {
        require(points.size >= 2) { "a path needs at least 2 points" }
        var total = 0f
        for (i in 0 until segmentCount(points.size, closed)) {
            total += segmentLength(points[i], points[(i + 1) % points.size])
        }
        return total
    }

    /**
     * The position [distance] metres along the polyline through [points].
     *
     * The distance wraps when [closed] and clamps to the ends otherwise, so a caller animating a
     * loop can just keep adding without a modulo of its own.
     */
    fun sampleAt(points: List<Position>, closed: Boolean, distance: Float): Position {
        require(points.size >= 2) { "a path needs at least 2 points" }
        val total = totalLength(points, closed)
        if (total <= 0f) return points[0]
        var remaining = if (closed) {
            // `rem` keeps the sign of the dividend, so a negative offset (a trail behind the
            // marker) would land outside the path; the extra `+ total` folds it back in.
            ((distance % total) + total) % total
        } else {
            distance.coerceIn(0f, total)
        }
        for (i in 0 until segmentCount(points.size, closed)) {
            val from = points[i]
            val to = points[(i + 1) % points.size]
            val length = segmentLength(from, to)
            if (remaining <= length || length <= 0f) {
                val t = if (length > 0f) remaining / length else 0f
                return from + (to - from) * t
            }
            remaining -= length
        }
        return points[if (closed) 0 else points.size - 1]
    }

    /**
     * A sub-path of [points]: [samples] positions evenly spaced by arc length, covering
     * [length] metres from [startDistance].
     *
     * The sample count is fixed by the caller and independent of where the span falls, which is
     * the whole point: a dash re-generated every frame keeps the same vertex count, so
     * `Tube.update` re-uploads into the buffers it already owns instead of reallocating them.
     */
    fun span(
        points: List<Position>,
        closed: Boolean,
        startDistance: Float,
        length: Float,
        samples: Int,
    ): List<Position> {
        require(samples >= 2) { "a span needs at least 2 samples, was $samples" }
        return List(samples) { i ->
            sampleAt(points, closed, startDistance + length * i / (samples - 1))
        }
    }

    /**
     * [count] evenly pitched dashes along the polyline through [points].
     *
     * @param dutyCycle Fraction of each pitch that is ink rather than gap, in `(0, 1)`.
     * @param phase Offset along the path expressed in pitches — advancing it by 1 marches the
     *              pattern forward by exactly one dash, so an animation that loops `0..1` is
     *              seamless.
     * @param samples Positions per dash. 2 is enough on a straight run; 3 keeps a dash that
     *                straddles a corner from cutting it.
     */
    fun dashes(
        points: List<Position>,
        closed: Boolean,
        count: Int,
        dutyCycle: Float,
        phase: Float,
        samples: Int,
    ): List<List<Position>> {
        require(count >= 1) { "count must be >= 1, was $count" }
        require(dutyCycle > 0f && dutyCycle < 1f) { "dutyCycle must be in (0, 1), was $dutyCycle" }
        val pitch = totalLength(points, closed) / count
        val ink = pitch * dutyCycle
        return List(count) { i ->
            span(points, closed, (phase + i) * pitch, ink, samples)
        }
    }

    // ── Animation ─────────────────────────────────────────────────────────────────────────────

    /** Dashes on the ground track. Eight corners, two dashes per side reads as a track. */
    const val DASH_COUNT = 16

    /** Ink fraction of each dash pitch — a dash slightly longer than its gap. */
    const val DASH_DUTY_CYCLE = 0.55f

    /** Samples per dash: a mid-point so a dash straddling a corner bends instead of cutting it. */
    const val DASH_SAMPLES = 3

    /** Length of the marker's trail as a fraction of the route, so it scales with the curve. */
    const val TRAIL_FRACTION = 0.10f

    /** Samples in the trail. Enough to hug the tightest part of a spline without faceting. */
    const val TRAIL_SAMPLES = 14

    /** Radius of the travelling marker, in metres. */
    const val MARKER_RADIUS = 0.045f

    /** Radius of a control-point marker, in metres. */
    const val POINT_RADIUS = 0.032f

    /**
     * Progress the animation freezes at in `DemoSettings.qaMode`.
     *
     * Not `0`: at zero the marker sits exactly on `controlPoints[0]`, hiding the control-point
     * marker underneath it — a screenshot golden that silently stops testing one of the four
     * elements. A quarter-turn-ish offset keeps every element visible in every capture.
     */
    const val STATIC_PROGRESS = 0.22f

    // ── Stroke width ──────────────────────────────────────────────────────────────────────────

    /**
     * Thinnest stroke the slider offers, in millimetres of **diameter**.
     *
     * The floor is a product decision, not a range: at [CAMERA_DISTANCE] on a 1080-wide portrait
     * phone this projects to ~5 px, so even the thinnest setting is unambiguously a line. A
     * slider that bottoms out at "invisible" would reintroduce, as a user choice, exactly the
     * defect this screen was rebuilt for (#3397).
     */
    const val MIN_STROKE_MM = 8f

    /** Thickest stroke the slider offers, in millimetres of **diameter** — ~22 px. */
    const val MAX_STROKE_MM = 36f

    /**
     * Default stroke, in millimetres of diameter.
     *
     * 16 mm at [CAMERA_DISTANCE] projects to ~10 px on a 1080-wide portrait phone — a stroke you
     * cannot miss, which is the entire point of the rebuild: the previous demo drew its route
     * with `PrimitiveType.LINES`, 1 px wide at any distance and at any camera (#3397).
     */
    const val DEFAULT_STROKE_MM = 16f

    /** Sides of the tube cross-section. 10 reads as round at the thickest stroke. */
    const val TUBE_SEGMENTS = 10

    /** Converts a stroke **diameter** in millimetres to the tube **radius** in metres. */
    fun strokeRadius(millimetres: Float): Float {
        require(millimetres > 0f) { "stroke must be > 0 mm, was $millimetres" }
        return millimetres / 2000f
    }

    /** The ground track is drawn quieter than the route — this fraction of the main stroke. */
    const val GROUND_STROKE_RATIO = 0.6f

    // ── Framing ───────────────────────────────────────────────────────────────────────────────

    /**
     * Camera-to-scene distance, in metres.
     *
     * Chosen so the loop fills ~72 % of the frame width at
     * [GeometryLayout.PHONE_PORTRAIT_ASPECT] at the default stroke — big enough to read at Play
     * Store thumbnail size, with margin on every side — and still clears the frame at
     * [GeometryLayout.NARROWEST_EXPECTED_ASPECT] with the stroke slider at maximum.
     */
    const val CAMERA_DISTANCE = 3.9f

    /**
     * Sine of the camera's elevation above the scene centre: ~18°, high enough to show the
     * ground track as a separate plane below the route rather than as a line through it.
     */
    const val ELEVATION_RATIO = 0.31f

    /**
     * Horizontal orbit radius to pass to `rememberHeroOrbitCameraManipulator` for a camera
     * [distance] metres from the scene. Paired with [orbitHeight] the eye is exactly [distance]
     * away, because the manipulator places it at `(radius·sin θ, height, radius·cos θ)`.
     */
    fun orbitRadius(distance: Float = CAMERA_DISTANCE): Float {
        require(distance > 0f) { "distance must be > 0, was $distance" }
        return distance * sqrt(1f - ELEVATION_RATIO * ELEVATION_RATIO)
    }

    /** Orbit height paired with [orbitRadius] — see there. */
    fun orbitHeight(distance: Float = CAMERA_DISTANCE): Float {
        require(distance > 0f) { "distance must be > 0, was $distance" }
        return distance * ELEVATION_RATIO
    }

    /**
     * Widest half-extent of the scene about the orbit axis, in metres, for a given stroke
     * radius. The route sweeps a circle about +Y, so this is rotation-invariant.
     */
    fun halfWidth(strokeRadius: Float): Float = LOOP_RADIUS + strokeRadius

    /**
     * Fraction of the frame **width** the scene occupies at [distance] on an [aspect] viewport —
     * `1.0` exactly touches both edges, above `1.0` is clipped.
     */
    fun horizontalFillRatio(distance: Float, aspect: Float, strokeRadius: Float): Float =
        halfWidth(strokeRadius) / GeometryLayout.frameHalfWidth(distance, aspect)

    // ── Internals ─────────────────────────────────────────────────────────────────────────────

    private const val TAU = 2f * PI.toFloat()

    private fun segmentCount(pointCount: Int, closed: Boolean) =
        if (closed) pointCount else pointCount - 1

    private fun segmentLength(a: Position, b: Position): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
