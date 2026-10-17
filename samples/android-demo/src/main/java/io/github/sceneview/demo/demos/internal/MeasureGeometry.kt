@file:Suppress("TooManyFunctions")

package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Pure geometry of the `ar-measure` demo: snapping, rising walls, floor polygons.
 *
 * World space is ARCore's: metres, `+Y` is gravity-up. Nothing here touches ARCore or
 * Filament, so every rule the reticle and the walls follow runs in a JVM unit test.
 */

/** What the reticle locked onto. Drives the reticle's shape, a chip and a haptic tick. */
enum class MeasureSnapKind {
    /** The raw surface hit, nothing nearby to lock onto. */
    None,

    /** An existing measured point — tapping there closes a loop or re-uses the corner. */
    Point,

    /** A sharp corner of the detected plane's outline (a table corner). */
    Corner,

    /** The outline of the detected plane (a table edge). */
    Edge,

    /** Straight above or below the previous point: the vertical guide. */
    Vertical,

    /** At the previous point's height: the level guide. */
    Level,

    /** A floor corner squared at 90° to the previous wall: the square guide. */
    Square,
}

/** A snapped reticle target. [guideFrom] is where the dashed guide starts, for the two guides. */
data class MeasureSnap(
    val position: Position,
    val kind: MeasureSnapKind,
    val guideFrom: Position? = null,
)

/** Everything the snapper may lock onto near one hit, gathered once per camera frame. */
data class MeasureSnapContext(
    val points: List<Position> = emptyList(),
    val corners: List<Position> = emptyList(),
    val edges: List<Pair<Position, Position>> = emptyList(),
    val previous: Position? = null,
)

private const val POINT_TOLERANCE_FACTOR = 1.5f
private const val GUIDE_MIN_SPAN_FACTOR = 2f

/**
 * Locks [raw] onto the most meaningful thing within [tolerance] metres, in the order a person
 * expects: a point they already placed, a corner, an edge, then the vertical and level
 * guides from the previous point. Returns [raw] unchanged with [MeasureSnapKind.None]
 * when nothing is close.
 */
fun snapMeasureTarget(raw: Position, context: MeasureSnapContext, tolerance: Float): MeasureSnap =
    snapToPlaced(raw, context, tolerance)
        ?: snapToGuide(raw, context.previous, tolerance)
        ?: MeasureSnap(raw, MeasureSnapKind.None)

/** A placed point, then a plane corner, then a plane edge. */
private fun snapToPlaced(raw: Position, context: MeasureSnapContext, tolerance: Float): MeasureSnap? {
    val point = context.points.minByOrNull { distance(it, raw) }
        ?.takeIf { distance(it, raw) <= tolerance * POINT_TOLERANCE_FACTOR }
    val corner = context.corners.minByOrNull { distance(it, raw) }
        ?.takeIf { distance(it, raw) <= tolerance }
    val edge = context.edges
        .map { (a, b) -> closestPointOnSegment(raw, a, b) }
        .minByOrNull { distance(it, raw) }
        ?.takeIf { distance(it, raw) <= tolerance }
    return when {
        point != null -> MeasureSnap(point, MeasureSnapKind.Point)
        corner != null -> MeasureSnap(corner, MeasureSnapKind.Corner)
        edge != null -> MeasureSnap(edge, MeasureSnapKind.Edge)
        else -> null
    }
}

/** Plumb line or spirit level from the previous point. */
private fun snapToGuide(raw: Position, previous: Position?, tolerance: Float): MeasureSnap? {
    if (previous == null) return null
    val horizontal = horizontalDistance(previous, raw)
    val vertical = abs(raw.y - previous.y)
    return when {
        horizontal <= tolerance && vertical >= tolerance * GUIDE_MIN_SPAN_FACTOR -> MeasureSnap(
            Position(previous.x, raw.y, previous.z),
            MeasureSnapKind.Vertical,
            guideFrom = previous,
        )
        vertical <= tolerance && horizontal >= tolerance * GUIDE_MIN_SPAN_FACTOR -> MeasureSnap(
            Position(raw.x, previous.y, raw.z),
            MeasureSnapKind.Level,
            guideFrom = previous,
        )
        else -> null
    }
}

/**
 * Squares a floor corner: when the line from [corner] to [raw] is within [toleranceDegrees]
 * of perpendicular to the previous wall `from`–`corner`, [raw] is moved onto the exact
 * perpendicular (keeping its distance along it). Rooms are square far more often than the
 * aim of a hand-held phone is; magicplan and RoomPlan square corners the same way.
 */
fun snapSquareCorner(
    from: Position,
    corner: Position,
    raw: Position,
    toleranceDegrees: Float = SQUARE_TOLERANCE_DEGREES,
): MeasureSnap? {
    val px = corner.x - from.x
    val pz = corner.z - from.z
    val pl = sqrt(px * px + pz * pz)
    val qx = raw.x - corner.x
    val qz = raw.z - corner.z
    val ql = sqrt(qx * qx + qz * qz)
    if (pl < EPSILON || ql < EPSILON) return null
    val cos = (px * qx + pz * qz) / (pl * ql)
    val offDegrees = abs(Math.toDegrees(kotlin.math.asin(cos.coerceIn(-1f, 1f).toDouble())).toFloat())
    if (offDegrees > toleranceDegrees) return null
    // Unit perpendicular to the previous wall, on the side [raw] is on.
    var nx = -pz / pl
    var nz = px / pl
    val along = qx * nx + qz * nz
    if (along < 0f) {
        nx = -nx
        nz = -nz
    }
    val length = abs(along)
    return MeasureSnap(
        Position(corner.x + nx * length, raw.y, corner.z + nz * length),
        MeasureSnapKind.Square,
        guideFrom = corner,
    )
}

private const val SQUARE_TOLERANCE_DEGREES = 6f

/**
 * Where the camera ray meets the horizontal plane at height [floorY] — the floor a wall or a
 * room is traced on. Aiming at the foot of a wall, the hit test often lands on the wall
 * itself, a few centimetres up; the floor plane gives the true corner. `null` when the ray
 * does not go down to it.
 */
fun measureRayFloorHit(origin: Position, direction: Position, floorY: Float, maxDistance: Float = 12f): Position? {
    if (direction.y > -PARALLEL_EPSILON) return null
    val t = (floorY - origin.y) / direction.y
    if (t <= 0f) return null
    val hit = Position(origin.x + direction.x * t, floorY, origin.z + direction.z * t)
    return hit.takeIf { horizontalDistance(origin, it) <= maxDistance }
}

/**
 * Snap radius for a hit [cameraDistance] metres away: the same few pixels on screen whether
 * the user aims at a cup or at the far wall, clamped so it never swallows a whole table.
 */
fun measureSnapTolerance(cameraDistance: Float): Float =
    (cameraDistance * SNAP_TOLERANCE_PER_METER).coerceIn(SNAP_TOLERANCE_MIN, SNAP_TOLERANCE_MAX)

private const val SNAP_TOLERANCE_PER_METER = 0.035f
private const val SNAP_TOLERANCE_MIN = 0.015f
private const val SNAP_TOLERANCE_MAX = 0.08f

/**
 * The sharp corners of a plane outline: vertices where the outline turns by at least
 * [minTurnDegrees]. ARCore's plane polygons are convex hulls with many near-collinear
 * vertices; only the real turns are worth snapping to.
 */
fun measureOutlineCorners(outline: List<Position>, minTurnDegrees: Float = 50f): List<Position> {
    if (outline.size < 3) return emptyList()
    val corners = ArrayList<Position>()
    for (i in outline.indices) {
        val prev = outline[(i - 1 + outline.size) % outline.size]
        val curr = outline[i]
        val next = outline[(i + 1) % outline.size]
        val ax = curr.x - prev.x
        val ay = curr.y - prev.y
        val az = curr.z - prev.z
        val bx = next.x - curr.x
        val by = next.y - curr.y
        val bz = next.z - curr.z
        val la = sqrt(ax * ax + ay * ay + az * az)
        val lb = sqrt(bx * bx + by * by + bz * bz)
        if (la < EPSILON || lb < EPSILON) continue
        val cos = ((ax * bx + ay * by + az * bz) / (la * lb)).coerceIn(-1f, 1f)
        val turn = Math.toDegrees(acos(cos).toDouble()).toFloat()
        if (turn >= minTurnDegrees) corners += curr
    }
    return corners
}

/** The edges of a closed outline, as consecutive pairs. */
fun measureOutlineEdges(outline: List<Position>): List<Pair<Position, Position>> =
    if (outline.size < 2) emptyList() else outline.indices.map { outline[it] to outline[(it + 1) % outline.size] }

/** Closest point to [p] on segment `a`–`b`. */
fun closestPointOnSegment(p: Position, a: Position, b: Position): Position {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val abz = b.z - a.z
    val lengthSquared = abx * abx + aby * aby + abz * abz
    if (lengthSquared < EPSILON) return a
    val t = (((p.x - a.x) * abx + (p.y - a.y) * aby + (p.z - a.z) * abz) / lengthSquared).coerceIn(0f, 1f)
    return Position(a.x + abx * t, a.y + aby * t, a.z + abz * t)
}

/** [p] moved onto the horizontal plane at height [y] — floor lines stay flat on the floor. */
fun measureOnLevel(p: Position, y: Float): Position = Position(p.x, y, p.z)

// ── Rising walls ────────────────────────────────────────────────────────────────────────────

/** Walls never rise below a few centimetres (so they stay visible) nor above a tall ceiling. */
const val WALL_MIN_HEIGHT = 0.05f
const val WALL_MAX_HEIGHT = 4.5f

/** How far beyond a wall's ends the aim may land and still drive that wall's height. */
private const val WALL_SPAN_SLACK = 0.25f

/**
 * Height the user is aiming at on the vertical wall standing on floor line `a`–`b`: the
 * camera ray ([origin], [direction]) is intersected with that wall's vertical plane and the
 * hit's height above the line is returned, clamped to [WALL_MIN_HEIGHT]..[WALL_MAX_HEIGHT].
 *
 * `null` when the ray runs parallel to the wall, points away from it, or lands far past its
 * ends — the caller keeps the last height then, rather than jumping.
 */
fun measureWallHeightFromRay(
    origin: Position,
    direction: Position,
    a: Position,
    b: Position,
    spanSlack: Float = WALL_SPAN_SLACK,
): Float? {
    val ex = b.x - a.x
    val ez = b.z - a.z
    val length = sqrt(ex * ex + ez * ez)
    if (length < EPSILON) return null
    // Horizontal normal of the vertical plane through a and b.
    val nx = -ez / length
    val nz = ex / length
    val denominator = direction.x * nx + direction.z * nz
    if (abs(denominator) < PARALLEL_EPSILON) return null
    val t = ((a.x - origin.x) * nx + (a.z - origin.z) * nz) / denominator
    if (t <= 0f) return null
    val hx = origin.x + direction.x * t
    val hy = origin.y + direction.y * t
    val hz = origin.z + direction.z * t
    val along = ((hx - a.x) * ex + (hz - a.z) * ez) / length
    if (along < -spanSlack || along > length + spanSlack) return null
    return (hy - a.y).coerceIn(WALL_MIN_HEIGHT, WALL_MAX_HEIGHT)
}

/**
 * Height aimed at across every wall of a floor outline: the nearest wall the ray actually
 * crosses wins, the way the eye reads "the wall I am looking at".
 */
fun measureRoomHeightFromRay(
    origin: Position,
    direction: Position,
    outline: List<Position>,
    closed: Boolean,
): Float? {
    if (outline.size < 2) return null
    val segmentCount = if (closed && outline.size > 2) outline.size else outline.size - 1
    var best: Float? = null
    var bestDistance = Float.MAX_VALUE
    for (i in 0 until segmentCount) {
        val a = outline[i]
        val b = outline[(i + 1) % outline.size]
        val height = measureWallHeightFromRay(origin, direction, a, b, spanSlack = 0f) ?: continue
        val mid = Position((a.x + b.x) / 2f, a.y, (a.z + b.z) / 2f)
        val d = horizontalDistance(origin, mid)
        if (d < bestDistance) {
            bestDistance = d
            best = height
        }
    }
    return best
}

/**
 * Vertices of the walls standing on [outline], [height] metres tall: four per wall, in the
 * order bottom-start, bottom-end, top-end, top-start. The vertex count depends only on the
 * number of walls, so a rising wall updates its buffer in place every frame.
 */
fun measureWallVertices(outline: List<Position>, closed: Boolean, height: Float): List<Position> {
    if (outline.size < 2) return emptyList()
    val segmentCount = if (closed && outline.size > 2) outline.size else outline.size - 1
    val vertices = ArrayList<Position>(segmentCount * 4)
    for (i in 0 until segmentCount) {
        val a = outline[i]
        val b = outline[(i + 1) % outline.size]
        vertices += a
        vertices += b
        vertices += Position(b.x, b.y + height, b.z)
        vertices += Position(a.x, a.y + height, a.z)
    }
    return vertices
}

/** Triangle indices for [measureWallVertices]: two triangles per wall quad. */
fun measureWallIndices(wallCount: Int): IntArray {
    val indices = IntArray(wallCount * 6)
    for (w in 0 until wallCount) {
        val base = w * 4
        val o = w * 6
        indices[o] = base
        indices[o + 1] = base + 1
        indices[o + 2] = base + 2
        indices[o + 3] = base
        indices[o + 4] = base + 2
        indices[o + 5] = base + 3
    }
    return indices
}

// ── Lengths and areas ───────────────────────────────────────────────────────────────────────

/** Area of a planar polygon in 3D (Newell's method), any orientation, square metres. */
fun measurePolygonArea(points: List<Position>): Float {
    if (points.size < 3) return 0f
    var nx = 0f
    var ny = 0f
    var nz = 0f
    for (i in points.indices) {
        val c = points[i]
        val n = points[(i + 1) % points.size]
        nx += (c.y - n.y) * (c.z + n.z)
        ny += (c.z - n.z) * (c.x + n.x)
        nz += (c.x - n.x) * (c.y + n.y)
    }
    return sqrt(nx * nx + ny * ny + nz * nz) / 2f
}

/** Floor area of an outline seen from above (shoelace on x/z), square metres. */
fun measureFloorArea(outline: List<Position>): Float = abs(signedAreaXZ(outline))

/** Total wall area of the outline's walls at [height], square metres. */
fun measureWallArea(outline: List<Position>, closed: Boolean, height: Float): Float =
    measurePerimeterMeters(outline, closed) * height

/**
 * True when [candidate] lands on the outline's first point while at least three points are
 * down — the tap that closes a room.
 */
fun measureClosesOutline(outline: List<Position>, candidate: Position, tolerance: Float): Boolean =
    outline.size >= 3 && distance(outline.first(), candidate) <= tolerance * POINT_TOLERANCE_FACTOR

/**
 * Ear-clipping triangulation of a simple polygon seen from above (x/z). Works for the
 * L- and T-shaped rooms a rectangle model cannot read. Returns indices into [outline];
 * empty for a degenerate or self-intersecting outline.
 */
fun measureTriangulateXZ(outline: List<Position>): IntArray {
    val n = outline.size
    if (n < 3) return IntArray(0)
    val ccw = signedAreaXZ(outline) > 0f
    val remaining = MutableList(n) { it }
    val result = ArrayList<Int>((n - 2) * 3)
    var guard = 0
    while (remaining.size > 3 && guard < n * n) {
        guard++
        val size = remaining.size
        val ear = remaining.indices.firstOrNull { i ->
            isEar(outline, remaining, remaining[(i - 1 + size) % size], remaining[i], remaining[(i + 1) % size], ccw)
        } ?: return IntArray(0)
        result += remaining[(ear - 1 + size) % size]
        result += remaining[ear]
        result += remaining[(ear + 1) % size]
        remaining.removeAt(ear)
    }
    if (remaining.size == 3) result.addAll(remaining)
    return result.toIntArray()
}

private fun isEar(outline: List<Position>, remaining: List<Int>, ia: Int, ib: Int, ic: Int, ccw: Boolean): Boolean {
    val a = outline[ia]
    val b = outline[ib]
    val c = outline[ic]
    val cross = crossXZ(a, b, c)
    if (if (ccw) cross <= EPSILON else cross >= -EPSILON) return false
    for (j in remaining) {
        if (j == ia || j == ib || j == ic) continue
        if (pointInTriangleXZ(outline[j], a, b, c)) return false
    }
    return true
}

/** z-x cross product so that a counter-clockwise turn seen from +Y is positive. */
private fun crossXZ(a: Position, b: Position, c: Position): Float =
    (b.z - a.z) * (c.x - b.x) - (b.x - a.x) * (c.z - b.z)

private fun signedAreaXZ(outline: List<Position>): Float {
    if (outline.size < 3) return 0f
    var sum = 0f
    for (i in outline.indices) {
        val c = outline[i]
        val n = outline[(i + 1) % outline.size]
        sum += c.z * n.x - n.z * c.x
    }
    return sum / 2f
}

private fun pointInTriangleXZ(p: Position, a: Position, b: Position, c: Position): Boolean {
    val d1 = crossXZ(a, b, p)
    val d2 = crossXZ(b, c, p)
    val d3 = crossXZ(c, a, p)
    val hasNegative = d1 < 0f || d2 < 0f || d3 < 0f
    val hasPositive = d1 > 0f || d2 > 0f || d3 > 0f
    return !(hasNegative && hasPositive)
}

// ── Motion and labels ───────────────────────────────────────────────────────────────────────

/** `a` + (`b` − `a`)·[t] — a segment growing from its start as [t] runs 0 → 1. */
fun measureLerp(a: Position, b: Position, t: Float): Position =
    Position(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t)

/**
 * World scale of a floating label [cameraDistance] metres away, so it reads at roughly the
 * same size on screen up close and across a room.
 */
fun measureLabelScale(cameraDistance: Float): Float =
    (cameraDistance * LABEL_SCALE_PER_METER).coerceIn(LABEL_SCALE_MIN, LABEL_SCALE_MAX)

private const val LABEL_SCALE_PER_METER = 0.75f
private const val LABEL_SCALE_MIN = 0.45f
private const val LABEL_SCALE_MAX = 3f

// ── Helpers ─────────────────────────────────────────────────────────────────────────────────

private const val EPSILON = 1e-6f
private const val PARALLEL_EPSILON = 1e-4f

private fun distance(a: Position, b: Position): Float = measureDistanceMeters(a, b)

private fun horizontalDistance(a: Position, b: Position): Float {
    val dx = b.x - a.x
    val dz = b.z - a.z
    return sqrt(dx * dx + dz * dz)
}
