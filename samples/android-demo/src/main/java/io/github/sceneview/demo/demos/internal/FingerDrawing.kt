package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.cross
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One point of a stroke: where the finger was, and how thick the paint is there.
 *
 * @param position Centre of the stroke, in the space the stroke is drawn in (world space in
 *                 the 3D mode, the stroke's anchor in AR).
 * @param radius   Half the thickness at that point, same unit.
 */
internal data class StrokePoint(val position: Position, val radius: Float)

/**
 * One mesh of paint: a stroke from pen-down to pen-up, or one piece of a stroke too long for
 * a single mesh (see [Drawing.penMove]).
 *
 * A value type: adding a point makes a new [Stroke], so a `remember(stroke)` around the mesh
 * rebuilds exactly when the stroke changed and never otherwise.
 *
 * @param id         Stable across the stroke's life — the Compose `key` of its mesh.
 * @param colorIndex Index into the demo's palette.
 * @param points     The filtered touch samples, at least [spacing] apart.
 * @param frameHint  The direction the cross-section's first axis leans towards — the view
 *                   direction at pen-down. It only chooses where the seam of the tube starts;
 *                   the tube is round, so it never shows.
 * @param spacing    Distance between two generated rings, in the stroke's unit. Fixed at
 *                   pen-down from the screen density, so a stroke looks equally smooth
 *                   whatever the camera distance was.
 * @param leadIn     Non-null when this piece continues another: the sample just before its
 *                   first point. The curve leaves the first point heading the way the previous
 *                   piece arrived.
 * @param leadOut    Non-null when another piece continues this one: the sample just after
 *                   its last point.
 */
internal data class Stroke(
    val id: Int,
    val colorIndex: Int,
    val points: List<StrokePoint>,
    val frameHint: Direction,
    val spacing: Float,
    val leadIn: StrokePoint? = null,
    val leadOut: StrokePoint? = null,
) {
    /** Sum of the distances between the control points. */
    val length: Float by lazy {
        var sum = 0f
        for (i in 1 until points.size) sum += length(points[i].position - points[i - 1].position)
        sum
    }
}

/**
 * What the Finger Drawing demo has on stage: the finished strokes, oldest first, and the one
 * under the finger. Every edit returns a new value, so undo is "drop the last element" and
 * the whole behaviour is unit-tested without a touch screen.
 */
internal data class Drawing(
    val strokes: List<Stroke> = emptyList(),
    val active: Stroke? = null,
    val nextId: Int = 0,
) {
    val isEmpty: Boolean get() = strokes.isEmpty() && active == null

    /**
     * Number of strokes on stage as the person drawing counts them — pen-down to pen-up, the
     * one being drawn included. A long stroke held in several meshes is still one.
     */
    val strokeCount: Int
        get() = strokes.count { it.leadIn == null } + if (active != null && active.leadIn == null) 1 else 0

    /**
     * Starts a stroke at [point]. A stroke left open by a lost `ACTION_UP` is closed first,
     * never dropped.
     */
    fun penDown(
        point: Position,
        radius: Float,
        colorIndex: Int,
        frameHint: Direction,
        spacing: Float,
    ): Drawing {
        val closed = penUp()
        return closed.copy(
            active = Stroke(
                id = closed.nextId,
                colorIndex = colorIndex,
                points = listOf(StrokePoint(point, radius)),
                frameHint = frameHint,
                spacing = spacing,
            ),
            nextId = closed.nextId + 1,
        )
    }

    /**
     * Extends the stroke under the finger to [point]. [targetRadius] is where the thickness
     * is heading — [FingerDrawing.radiusFor] of the finger's current speed — and is eased in
     * over a length of stroke ([FingerDrawing.RADIUS_EASING_SPACINGS]), so the paint swells
     * and thins instead of stepping, the same way whatever the touch sampling rate.
     *
     * A sample closer than the stroke's spacing to the previous one is ignored: it would add
     * a ring the eye cannot see and, on a resting finger, a knot of them.
     *
     * A stroke that outgrows [FingerDrawing.MAX_RINGS] is continued in a new mesh, so no
     * single one grows without bound. The piece that is closed ends on the previous sample
     * and the new one starts there; each keeps the other's neighbouring sample as
     * [Stroke.leadOut] / [Stroke.leadIn], so both follow the very curve one mesh would have
     * drawn, at the same thickness, with no taper at the join: it does not show.
     */
    fun penMove(point: Position, targetRadius: Float): Drawing {
        val stroke = active ?: return this
        val last = stroke.points.last()
        val distance = length(point - last.position)
        if (distance < stroke.spacing * FingerDrawing.MIN_SAMPLE_SPACINGS) return this
        val easing = 1f - exp(-distance / (stroke.spacing * FingerDrawing.RADIUS_EASING_SPACINGS))
        val sample = StrokePoint(point, last.radius + (targetRadius - last.radius) * easing)
        val extended = stroke.copy(points = stroke.points + sample)
        if (stroke.points.size < 2 || FingerDrawing.ringEstimate(extended) < FingerDrawing.MAX_RINGS) {
            return copy(active = extended)
        }
        return copy(
            strokes = strokes + stroke.copy(leadOut = sample),
            active = Stroke(
                id = nextId,
                colorIndex = stroke.colorIndex,
                points = listOf(last, sample),
                frameHint = stroke.frameHint,
                spacing = stroke.spacing,
                leadIn = stroke.points[stroke.points.size - 2],
            ),
            nextId = nextId + 1,
        )
    }

    /** Lifts the pen: the stroke under the finger joins the finished ones. */
    fun penUp(): Drawing {
        val stroke = active ?: return this
        return copy(strokes = strokes + stroke, active = null)
    }

    /**
     * Removes the newest stroke — the one being drawn if there is one — whole: every mesh a
     * long stroke was held in goes with it.
     */
    fun undo(): Drawing {
        val all = strokes + listOfNotNull(active)
        val start = all.indexOfLast { it.leadIn == null }
        if (start < 0) return this
        return copy(strokes = all.subList(0, start).toList(), active = null)
    }

    /** Wipes the stage. Ids keep counting, so a new stroke never reuses a disposed mesh. */
    fun clear(): Drawing = copy(strokes = emptyList(), active = null)

    /**
     * The stage without the strokes whose id is in [ids] — the AR mode drops the strokes
     * whose anchor ARCore gave up on. Ids keep counting, like after [clear].
     */
    fun without(ids: Set<Int>): Drawing {
        if (ids.isEmpty()) return this
        return copy(strokes = strokes.filter { it.id !in ids }, active = active?.takeIf { it.id !in ids })
    }
}

/**
 * The geometry behind the *Finger Drawing* demo: touch samples in, a smooth round tube out.
 *
 * ## Why the demo generates its own mesh
 *
 * SceneView's `TubeNode` sweeps a circle of one constant radius with flat ends, and
 * rebuilding it for every finger move would rebuild its GPU buffers too. Paint wants three
 * things it does not offer: a thickness that changes along the stroke, round ends, and a
 * mesh that grows in place. So this object produces plain [Geometry.Vertex] lists and the
 * demo uploads them with `Geometry.Builder` + `MeshNode`, exactly as
 * [io.github.sceneview.demo.demos.CustomGeometryDemo] does. No engine type appears below,
 * which is what lets `FingerDrawingTest` check every property on the JVM.
 *
 * ## The pipeline, in the order the code runs
 *
 * 0. [SteadyPen] — before any geometry, the touch samples are steadied: the paint follows
 *    the finger a few milliseconds late, which is what turns the polygon a touch screen
 *    reports into a curve. Where the samples land is [FingerDrawingPlacement]'s business.
 * 1. [centerline] — a centripetal Catmull-Rom spline through the steadied samples, resampled
 *    every `spacing`. Touch events arrive 5–40 px apart; joined by straight segments they
 *    draw a polygon. The centripetal form is the one that never overshoots or loops between
 *    unevenly spaced samples.
 *    A stroke keeps its thickness right to its ends: there is no taper. The ends are closed
 *    by full hemispherical caps (step 3), so a stroke reads as paint of the chosen
 *    thickness, not as a brush lifting off (#4504).
 * 2. [frames] — a cross-section orientation per ring, by parallel transport (the double
 *    reflection method). A naive "up vector" frame flips when the stroke turns past it and
 *    pinches the tube; a transported frame cannot twist.
 * 3. [vertices] — [RADIAL_SEGMENTS] vertices per ring, plus [CAP_RINGS] rings closing each
 *    end in a hemisphere of the end's own radius. Normals follow the thickness slope, so a swelling stroke shades as
 *    a cone, not as a stack of cylinders.
 * 4. [triangleIndices] — a quad strip between consecutive rings. It depends on the ring
 *    count only.
 *
 * ## Growing in place
 *
 * The stroke under the finger gains rings at every move. [vertices] takes a `capacity` and
 * pads the list to it with rings collapsed on the end pole — zero-area triangles, which the
 * GPU discards. As long as the capacity holds ([capacityFor] grows it in steps), the vertex
 * count is constant and `Geometry.update` rewrites the same buffers instead of allocating.
 */
internal object FingerDrawing {

    /** Vertices around one ring. Twelve reads as round at any thickness the slider allows. */
    const val RADIAL_SEGMENTS: Int = 12

    /** Rings in each hemispherical end cap, the pole included. */
    const val CAP_RINGS: Int = 4

    /** A spline segment is split in at most this many pieces, however far apart its ends. */
    const val MAX_SUBDIVISIONS: Int = 8

    /** A stroke is continued in a new mesh past this many centreline rings. */
    const val MAX_RINGS: Int = 600

    /** Ring capacities the stroke under the finger steps through as it grows. */
    private val CAPACITY_STEPS = intArrayOf(48, 96, 192, 384, 640)

    /** A touch sample closer than this many [Stroke.spacing]s to the previous one is dropped. */
    const val MIN_SAMPLE_SPACINGS: Float = 1.5f

    /**
     * Length of stroke, in [Stroke.spacing]s, over which the thickness covers 63 % of the way
     * to what the finger's speed asks for. Sixteen is 40 dp on screen: long enough that the
     * thickness reads as a brush swelling, short enough that a flick still thins.
     */
    const val RADIUS_EASING_SPACINGS: Float = 16f

    /**
     * Time constant of [smoothedSpeed]. Two touch samples a few milliseconds apart measure a
     * speed that jumps by a third from one to the next; a tenth of a second averages a dozen
     * of them and is still shorter than a deliberate change of pace.
     */
    const val SPEED_SMOOTHING_SECONDS: Float = 0.1f

    /** At and below this finger speed the stroke has its full thickness. */
    const val SLOW_SPEED_DP_PER_SECOND: Float = 180f

    /** At and above this finger speed the stroke is at its thinnest. */
    const val FAST_SPEED_DP_PER_SECOND: Float = 1_600f

    /** Thinnest a fast stroke gets, as a share of the chosen thickness. */
    const val FAST_WIDTH_FACTOR: Float = 0.45f

    /** Distance between two generated rings, in dp on screen at pen-down. */
    const val RING_SPACING_DP: Float = 2.5f

    const val MIN_THICKNESS_DP: Float = 4f
    const val MAX_THICKNESS_DP: Float = 28f
    const val DEFAULT_THICKNESS_DP: Float = 12f

    private const val EPSILON = 1e-6f

    // ── Input ─────────────────────────────────────────────────────────────────

    /**
     * Finger speed between two touch samples, in dp per second. A zero or negative interval
     * (two events with one timestamp) reads as "not moving" rather than as infinitely fast.
     */
    fun speedDpPerSecond(dxPx: Float, dyPx: Float, dtMillis: Long, density: Float): Float {
        if (dtMillis <= 0L || density <= 0f) return 0f
        return sqrt(dxPx * dxPx + dyPx * dyPx) / density / (dtMillis / 1000f)
    }

    /**
     * The finger's speed with the jitter of single touch samples averaged out: [previous]
     * moved towards [measured] by the share of [SPEED_SMOOTHING_SECONDS] that [dtSeconds]
     * covers. Weighting by time makes a sample that arrived a millisecond after the last one —
     * the least reliable measure of all — count for almost nothing.
     */
    fun smoothedSpeed(previous: Float, measured: Float, dtSeconds: Float): Float {
        if (dtSeconds <= 0f) return previous
        return previous + (measured - previous) * (1f - exp(-dtSeconds / SPEED_SMOOTHING_SECONDS))
    }

    /**
     * Share of the chosen thickness a stroke has at finger speed [dpPerSecond]: `1` when
     * slow, [FAST_WIDTH_FACTOR] when fast, smoothstep in between — the way a marker drags
     * thick and flicks thin.
     */
    fun widthFactorForSpeed(dpPerSecond: Float): Float {
        val t = ((dpPerSecond - SLOW_SPEED_DP_PER_SECOND) /
            (FAST_SPEED_DP_PER_SECOND - SLOW_SPEED_DP_PER_SECOND)).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t)
        return 1f + (FAST_WIDTH_FACTOR - 1f) * eased
    }

    /**
     * Stroke radius for a thickness chosen in dp and a finger speed.
     *
     * @param unitsPerDp How long one dp on screen is on the surface being painted — see
     *                   [FingerDrawingPlacement.unitsPerDp].
     */
    fun radiusFor(thicknessDp: Float, unitsPerDp: Float, speedDpPerSecond: Float): Float =
        thicknessDp * 0.5f * unitsPerDp * widthFactorForSpeed(speedDpPerSecond)

    /**
     * Speed of the paint between two points of the painted surface, in dp of screen per
     * second. The AR mode measures the stroke this way rather than from the touch events: a
     * finger held still on a phone that sweeps through the room is drawing fast.
     */
    fun speedDpPerSecond(from: Position, to: Position, unitsPerDp: Float, dtSeconds: Float): Float {
        if (dtSeconds <= 0f || unitsPerDp <= 0f) return 0f
        return length(to - from) / unitsPerDp / dtSeconds
    }

    // ── Centreline ────────────────────────────────────────────────────────────

    /**
     * Upper bound on [centerline]`(stroke).size`, without building it — what [Drawing.penMove]
     * compares against [MAX_RINGS] at every sample.
     */
    fun ringEstimate(stroke: Stroke): Int =
        (stroke.length / stroke.spacing).toInt() + stroke.points.size

    /**
     * The smooth curve through [points], resampled about every [spacing].
     *
     * Each span between two samples is a centripetal Catmull-Rom segment — it depends on the
     * sample before and the sample after only, so the part of a stroke already drawn does not
     * move when the finger goes on. An end's missing outer control point is [leadIn] /
     * [leadOut] when the stroke is one piece of a longer one, and its neighbour mirrored
     * through it otherwise. The thickness runs straight from one sample to the next: a spline
     * would overshoot it into bumps. The samples themselves are always on the curve.
     */
    fun centerline(
        points: List<StrokePoint>,
        spacing: Float,
        leadIn: StrokePoint? = null,
        leadOut: StrokePoint? = null,
    ): List<StrokePoint> {
        if (points.size < 2) return points
        val result = ArrayList<StrokePoint>(points.size * 3)
        for (i in 0 until points.size - 1) {
            val p1 = points[i]
            val p2 = points[i + 1]
            val p0 = if (i > 0) points[i - 1] else leadIn ?: mirrored(p1, p2)
            val p3 = if (i + 2 < points.size) points[i + 2] else leadOut ?: mirrored(p2, p1)
            val span = length(p2.position - p1.position)
            val pieces = ceil(span / max(spacing, EPSILON)).toInt().coerceIn(1, MAX_SUBDIVISIONS)
            for (step in 0 until pieces) {
                result += if (step == 0) p1 else catmullRom(p0, p1, p2, p3, step / pieces.toFloat())
            }
        }
        result += points.last()
        return result
    }

    /** The control point a stroke end is missing: its neighbour mirrored through it. */
    private fun mirrored(end: StrokePoint, neighbour: StrokePoint) =
        StrokePoint(end.position * 2f - neighbour.position, end.radius)

    /** Centripetal Catmull-Rom between [p1] and [p2] (Barry–Goldman form), `t` in 0..1. */
    private fun catmullRom(
        p0: StrokePoint,
        p1: StrokePoint,
        p2: StrokePoint,
        p3: StrokePoint,
        t: Float,
    ): StrokePoint {
        val t0 = 0f
        val t1 = t0 + knotInterval(p0, p1)
        val t2 = t1 + knotInterval(p1, p2)
        val t3 = t2 + knotInterval(p2, p3)
        val u = t1 + (t2 - t1) * t
        val a1 = blend(p0, p1, (t1 - u) / (t1 - t0), (u - t0) / (t1 - t0))
        val a2 = blend(p1, p2, (t2 - u) / (t2 - t1), (u - t1) / (t2 - t1))
        val a3 = blend(p2, p3, (t3 - u) / (t3 - t2), (u - t2) / (t3 - t2))
        val b1 = blend(a1, a2, (t2 - u) / (t2 - t0), (u - t0) / (t2 - t0))
        val b2 = blend(a2, a3, (t3 - u) / (t3 - t1), (u - t1) / (t3 - t1))
        val c = blend(b1, b2, (t2 - u) / (t2 - t1), (u - t1) / (t2 - t1))
        return c.copy(radius = p1.radius + (p2.radius - p1.radius) * t)
    }

    /** Centripetal parameterisation: the square root of the chord. */
    private fun knotInterval(a: StrokePoint, b: StrokePoint): Float =
        max(sqrt(length(b.position - a.position)), 1e-4f)

    private fun blend(a: StrokePoint, b: StrokePoint, wa: Float, wb: Float) =
        StrokePoint(a.position * wa + b.position * wb, a.radius * wa + b.radius * wb)

    // ── Frames ────────────────────────────────────────────────────────────────

    /** The orientation of one ring: the curve's direction and two axes across it. */
    data class Frame(val tangent: Direction, val normal: Direction, val binormal: Direction)

    /**
     * A twist-free frame per centreline point.
     *
     * The first frame leans its normal towards [hint]; every next one is the previous frame
     * carried along the curve by two reflections (Wang et al., "Computation of Rotation
     * Minimizing Frames", 2008) — the cheapest construction that neither flips at an
     * inflection, as a Frenet frame does, nor drifts, as repeated projection does.
     */
    fun frames(centerline: List<StrokePoint>, hint: Direction): List<Frame> {
        if (centerline.isEmpty()) return emptyList()
        val tangents = tangents(centerline, hint)
        var normal = perpendicularTo(tangents[0], hint)
        val result = ArrayList<Frame>(centerline.size)
        result += Frame(tangents[0], normal, cross(tangents[0], normal))
        for (i in 0 until centerline.size - 1) {
            val v1 = centerline[i + 1].position - centerline[i].position
            val c1 = dot(v1, v1)
            var reflectedNormal = normal
            var reflectedTangent = tangents[i]
            if (c1 > EPSILON * EPSILON) {
                reflectedNormal = normal - v1 * (2f / c1 * dot(v1, normal))
                reflectedTangent = tangents[i] - v1 * (2f / c1 * dot(v1, tangents[i]))
            }
            val v2 = tangents[i + 1] - reflectedTangent
            val c2 = dot(v2, v2)
            normal = if (c2 > EPSILON * EPSILON) {
                reflectedNormal - v2 * (2f / c2 * dot(v2, reflectedNormal))
            } else {
                reflectedNormal
            }
            // Re-orthogonalise: float error must not accumulate over hundreds of rings.
            normal = perpendicularTo(tangents[i + 1], normal)
            result += Frame(tangents[i + 1], normal, cross(tangents[i + 1], normal))
        }
        return result
    }

    private fun tangents(centerline: List<StrokePoint>, hint: Direction): List<Direction> {
        if (centerline.size == 1) {
            // A dot has no direction. Lay its axis across the view so the poles of the little
            // sphere sit on its sides, not in the viewer's eye.
            return listOf(perpendicularTo(hint, Direction(x = 1f)))
        }
        var previous: Direction? = null
        return centerline.indices.map { i ->
            val before = centerline[max(i - 1, 0)].position
            val after = centerline[min(i + 1, centerline.size - 1)].position
            val delta = after - before
            val tangent = if (length(delta) > EPSILON) {
                normalize(delta)
            } else {
                previous ?: perpendicularTo(hint, Direction(x = 1f))
            }
            previous = tangent
            tangent
        }
    }

    /** The unit vector closest to [preferred] that is perpendicular to unit [axis]. */
    private fun perpendicularTo(axis: Direction, preferred: Direction): Direction {
        val projected = preferred - axis * dot(preferred, axis)
        if (length(projected) > 1e-3f) return normalize(projected)
        // `preferred` runs along the axis: any perpendicular will do, pick a stable one.
        val fallback = if (kotlin.math.abs(axis.y) < 0.9f) Direction(y = 1f) else Direction(x = 1f)
        return normalize(fallback - axis * dot(fallback, axis))
    }

    // ── Mesh ──────────────────────────────────────────────────────────────────

    /** Rings of a mesh holding [centerlineCount] centreline points: them and the two caps. */
    fun ringCount(centerlineCount: Int): Int = centerlineCount + 2 * CAP_RINGS

    fun vertexCount(centerlineCount: Int): Int = ringCount(centerlineCount) * RADIAL_SEGMENTS

    fun triangleCount(centerlineCount: Int): Int =
        (ringCount(centerlineCount) - 1) * RADIAL_SEGMENTS * 2

    /**
     * The centreline capacity to allocate for a stroke of [centerlineCount] points that is
     * still growing: the first step that holds it. Past the last step — which [MAX_RINGS]
     * keeps a stroke from reaching — the count itself.
     */
    fun capacityFor(centerlineCount: Int): Int =
        CAPACITY_STEPS.firstOrNull { it >= centerlineCount } ?: centerlineCount

    /**
     * The tube around [centerline] — already resampled — as
     * [vertexCount]`(capacity)` vertices: start cap, one ring per centreline point, end cap,
     * then padding collapsed on the end pole.
     *
     * @param capacity Centreline points the vertex list is sized for; at least
     *                 `centerline.size`. Pass the stroke's own size for a finished stroke.
     */
    fun vertices(
        centerline: List<StrokePoint>,
        hint: Direction,
        capacity: Int = centerline.size,
    ): List<Geometry.Vertex> {
        require(centerline.isNotEmpty()) { "a stroke has at least one point" }
        require(capacity >= centerline.size) { "capacity $capacity < ${centerline.size} points" }
        val frames = frames(centerline, hint)
        val slopes = radiusSlopes(centerline)
        val result = ArrayList<Geometry.Vertex>(vertexCount(capacity))

        // Start cap, pole first, bulging backwards along the first tangent.
        val first = centerline.first()
        for (k in CAP_RINGS downTo 1) {
            val phi = k / CAP_RINGS.toFloat() * (PI.toFloat() / 2f)
            result.addRing(
                center = first.position - frames.first().tangent * (first.radius * sin(phi)),
                radius = first.radius * cos(phi),
                frame = frames.first(),
                alongTangent = -sin(phi),
                acrossTangent = cos(phi),
            )
        }
        // Body. A ring whose radius grows along the stroke leans its normals backwards by
        // the slope, which is what makes a swelling stroke catch the light as a cone.
        for (i in centerline.indices) {
            val slope = slopes[i]
            val scale = 1f / sqrt(1f + slope * slope)
            result.addRing(
                center = centerline[i].position,
                radius = centerline[i].radius,
                frame = frames[i],
                alongTangent = -slope * scale,
                acrossTangent = scale,
            )
        }
        // End cap, bulging forwards, pole last.
        val last = centerline.last()
        for (k in 1..CAP_RINGS) {
            val phi = k / CAP_RINGS.toFloat() * (PI.toFloat() / 2f)
            result.addRing(
                center = last.position + frames.last().tangent * (last.radius * sin(phi)),
                radius = last.radius * cos(phi),
                frame = frames.last(),
                alongTangent = sin(phi),
                acrossTangent = cos(phi),
            )
        }
        // Padding: whole rings on the end pole. Their triangles have no area.
        val pole = Geometry.Vertex(
            position = last.position + frames.last().tangent * last.radius,
            normal = frames.last().tangent,
        )
        repeat((capacity - centerline.size) * RADIAL_SEGMENTS) { result += pole }
        return result
    }

    private fun ArrayList<Geometry.Vertex>.addRing(
        center: Position,
        radius: Float,
        frame: Frame,
        alongTangent: Float,
        acrossTangent: Float,
    ) {
        for (j in 0 until RADIAL_SEGMENTS) {
            val theta = j / RADIAL_SEGMENTS.toFloat() * (2f * PI.toFloat())
            val radial = frame.normal * cos(theta) + frame.binormal * sin(theta)
            add(
                Geometry.Vertex(
                    position = center + radial * radius,
                    normal = normalize(radial * acrossTangent + frame.tangent * alongTangent),
                )
            )
        }
    }

    /** Change of radius per unit of length at each centreline point, by central difference. */
    private fun radiusSlopes(centerline: List<StrokePoint>): FloatArray =
        FloatArray(centerline.size) { i ->
            val before = centerline[max(i - 1, 0)]
            val after = centerline[min(i + 1, centerline.size - 1)]
            val run = length(after.position - before.position)
            if (run < EPSILON) 0f else ((after.radius - before.radius) / run).coerceIn(-1f, 1f)
        }

    /**
     * Triangle list of a mesh sized for [capacity] centreline points: two triangles per quad
     * between consecutive rings, wound counter-clockwise seen from outside.
     */
    fun triangleIndices(capacity: Int): List<Int> {
        val rings = ringCount(capacity)
        return buildList(triangleCount(capacity) * 3) {
            for (ring in 0 until rings - 1) {
                val a = ring * RADIAL_SEGMENTS
                val b = a + RADIAL_SEGMENTS
                for (j in 0 until RADIAL_SEGMENTS) {
                    val next = (j + 1) % RADIAL_SEGMENTS
                    add(a + j); add(a + next); add(b + j)
                    add(a + next); add(b + next); add(b + j)
                }
            }
        }
    }

    /**
     * The centreline [vertices] sweeps for [stroke]: resampled, at the thickness the finger
     * gave it right to both ends — the caps [vertices] adds close it with a full half-sphere.
     */
    fun line(stroke: Stroke): List<StrokePoint> =
        centerline(stroke.points, stroke.spacing, stroke.leadIn, stroke.leadOut)
}
