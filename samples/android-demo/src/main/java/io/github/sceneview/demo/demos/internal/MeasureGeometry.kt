@file:Suppress("TooManyFunctions")

package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import io.github.sceneview.triangulation.Earcut
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

// ── Tracked planes ──────────────────────────────────────────────────────────────────────────

/** How a tracked plane faces, as far as snapping cares. */
enum class MeasurePlaneKind {
    /** Floor, table top, seat: a horizontal plane facing up. */
    Upward,

    /** A ceiling. */
    Downward,

    /** A wall, a cupboard front, a door. */
    Vertical,
}

/**
 * A plane ARCore is tracking, with its real boundary polygon in world space. These polygons
 * are the only outlines the reticle snaps to: an edge or a corner is never guessed.
 */
data class MeasurePlane(val kind: MeasurePlaneKind, val outline: List<Position>)

/** Heights within this of the floor count as the floor (ARCore's floor patches differ a little). */
private const val LEVEL_TOLERANCE = 0.1f

/** The floor's height: the lowest upward-facing plane, `null` until one is tracked. */
fun measureFloorLevel(planes: List<MeasurePlane>): Float? =
    planes.filter { it.kind == MeasurePlaneKind.Upward && it.outline.size >= 3 }
        .minOfOrNull { plane -> plane.outline.sumOf { it.y.toDouble() }.toFloat() / plane.outline.size }

/**
 * Where the first point of a wall or a room goes for a hit on a [kind] plane (`null` for a
 * depth or feature point). Aiming at the foot of a wall often lands on the wall itself, a
 * few centimetres up: a wall hit drops straight down to the floor at [floorY], which is the
 * corner the user means. Without a tracked floor, and for any other hit, the hit is kept.
 */
fun measureFirstFloorPoint(hit: Position, kind: MeasurePlaneKind?, floorY: Float?): Position =
    if (kind == MeasurePlaneKind.Vertical && floorY != null) measureOnLevel(hit, floorY) else hit

/**
 * The corners and edges the reticle may lock onto, taken from tracked [planes] only.
 *
 * - [MeasureMode.Distance]: every plane's outline as it is in space — table edges, the floor's
 *   edge, a wall's or a door's edge.
 * - [MeasureMode.Wall] and [MeasureMode.Room] trace on the level [level] (the figure's floor):
 *   the outlines of the upward planes at that level, and the foot of every vertical plane —
 *   its real polygon dropped onto the level, an edge along the wall whose two ends are the
 *   corners. `null` [level] (nothing to trace on yet) gives no target.
 *
 * Same snap radius for all of them: the caller passes one tolerance to [snapMeasureTarget].
 */
fun measurePlaneSnapTargets(mode: MeasureMode, planes: List<MeasurePlane>, level: Float?): MeasureSnapContext {
    val corners = ArrayList<Position>()
    val edges = ArrayList<Pair<Position, Position>>()
    for (plane in planes) {
        if (plane.outline.size < 3) continue
        when {
            mode == MeasureMode.Distance -> {
                corners += measureOutlineCorners(plane.outline)
                edges += measureOutlineEdges(plane.outline)
            }
            level == null -> Unit
            plane.kind == MeasurePlaneKind.Upward && plane.outline.all { abs(it.y - level) <= LEVEL_TOLERANCE } -> {
                val flat = plane.outline.map { measureOnLevel(it, level) }
                corners += measureOutlineCorners(flat)
                edges += measureOutlineEdges(flat)
            }
            plane.kind == MeasurePlaneKind.Vertical -> measureWallFootprint(plane.outline, level)?.let { foot ->
                corners += foot.first
                corners += foot.second
                edges += foot
            }
        }
    }
    return MeasureSnapContext(corners = corners, edges = edges)
}

/**
 * The foot of a vertical plane on the level [y]: its polygon dropped straight down, which is a
 * segment along the wall. Its ends are the polygon's two horizontally farthest vertices.
 */
fun measureWallFootprint(outline: List<Position>, y: Float): Pair<Position, Position>? {
    if (outline.size < 2) return null
    val a = outline.maxByOrNull { horizontalDistance(outline.first(), it) } ?: return null
    val b = outline.maxByOrNull { horizontalDistance(a, it) } ?: return null
    if (horizontalDistance(a, b) < WALL_FOOT_MIN_LENGTH) return null
    return measureOnLevel(a, y) to measureOnLevel(b, y)
}

private const val WALL_FOOT_MIN_LENGTH = 0.05f

/** [p] moved onto the horizontal plane at height [y] — floor lines stay flat on the floor. */
fun measureOnLevel(p: Position, y: Float): Position = Position(p.x, y, p.z)

/**
 * The snap of one aim at [raw], in the order the reticle applies it: the placed [points], the
 * planes' corners and edges ([measurePlaneSnapTargets]), the guides from the previous point of
 * a Distance chain, then — in Room, when none of those holds — the square corner on the last
 * two corners of [activeOutline]: within 6° when a tracked wall confirms the right angle, within
 * 3° otherwise, so a room that is not square stays as it is traced. Without a [level] to stand a
 * wall on, nothing confirms it.
 */
@Suppress("LongParameterList") // the reticle's whole input, one frame
fun measureSnapAim(
    mode: MeasureMode,
    planes: List<MeasurePlane>,
    level: Float?,
    points: List<Position>,
    activeOutline: List<Position>,
    raw: Position,
    tolerance: Float,
): MeasureSnap {
    val context = measurePlaneSnapTargets(mode, planes, level).copy(
        points = points,
        previous = activeOutline.lastOrNull().takeIf { mode == MeasureMode.Distance },
    )
    val snap = snapMeasureTarget(raw, context, tolerance)
    if (snap.kind != MeasureSnapKind.None || mode != MeasureMode.Room || activeOutline.size < 2) return snap
    val from = activeOutline[activeOutline.size - 2]
    val corner = activeOutline.last()
    val squareTolerance = if (level != null && squareConfirmed(from, corner, planes, level)) {
        SQUARE_TOLERANCE_DEGREES
    } else {
        SQUARE_UNCONFIRMED_TOLERANCE_DEGREES
    }
    return snapSquareCorner(from, corner, raw, squareTolerance) ?: snap
}

/**
 * Whether a tracked wall confirms that the room turns square at [corner], after the wall
 * [from]–[corner]. A vertical plane stands square by a corner as often as a wall does — an open
 * door, a cupboard's side, a wall cupboard, a wall further along the same line — so it counts as
 * a wall only when it stands on the floor at [level], rises at least [SQUARE_CONFIRM_MIN_HEIGHT_M]
 * and runs at least [SQUARE_CONFIRM_MIN_LENGTH_M] along it; then its foot confirms the corner when
 * it runs within [SQUARE_CONFIRM_DEGREES] of square to the last wall and comes within
 * [SQUARE_CONFIRM_REACH_M] of the corner.
 */
private fun squareConfirmed(from: Position, corner: Position, planes: List<MeasurePlane>, level: Float): Boolean {
    val px = corner.x - from.x
    val pz = corner.z - from.z
    val pl = sqrt(px * px + pz * pz)
    if (pl < EPSILON) return false
    return planes.any { plane ->
        if (plane.kind != MeasurePlaneKind.Vertical || !standsLikeAWall(plane.outline, level)) return@any false
        val (a, b) = measureWallFootprint(plane.outline, level) ?: return@any false
        val fx = b.x - a.x
        val fz = b.z - a.z
        val fl = sqrt(fx * fx + fz * fz)
        if (fl < SQUARE_CONFIRM_MIN_LENGTH_M) return@any false
        val cos = abs(px * fx + pz * fz) / (pl * fl)
        val offDegrees = Math.toDegrees(kotlin.math.asin(cos.coerceAtMost(1f).toDouble())).toFloat()
        // How far the corner is from the foot itself: a wall further along its line is no help.
        val reach = horizontalDistance(corner, closestPointOnSegment(corner, a, b))
        offDegrees <= SQUARE_CONFIRM_DEGREES && reach <= SQUARE_CONFIRM_REACH_M
    }
}

/** Whether a vertical plane's [outline] stands on the floor at [level] and rises as a wall does. */
private fun standsLikeAWall(outline: List<Position>, level: Float): Boolean {
    if (outline.isEmpty()) return false
    val bottom = outline.minOf { it.y }
    val top = outline.maxOf { it.y }
    return bottom <= level + SQUARE_CONFIRM_FLOOR_GAP_M && top - bottom >= SQUARE_CONFIRM_MIN_HEIGHT_M
}

/** Without a tracked wall confirming it, a corner is squared within this only. */
private const val SQUARE_UNCONFIRMED_TOLERANCE_DEGREES = 3f

/**
 * How close to square to the last wall a tracked wall runs to confirm a right angle. The last
 * wall is the traced one, so after a short one (1 m or less, its corners a few centimetres off)
 * its own noise takes most of this, and the corner falls back to the unconfirmed 3°.
 */
private const val SQUARE_CONFIRM_DEGREES = 3f

/** How far from the corner a confirming wall's foot may stop: ARCore's planes rarely reach into one. */
private const val SQUARE_CONFIRM_REACH_M = 0.2f

/**
 * A confirming wall's foot runs at least this: longer than an open door is wide or a cupboard is
 * deep, with room for ARCore's plane to run a few centimetres past each end of a 0.93 m door
 * leaf, and long enough for those centimetres to leave its direction within a couple of degrees.
 * The risk is lopsided: a wrong confirmation squares a corner up to 6° off without a word, a
 * missed one only falls back to 3°.
 */
private const val SQUARE_CONFIRM_MIN_LENGTH_M = 1.2f

/** A confirming wall's bottom is at most this above the floor: a wall cupboard is not a wall. */
private const val SQUARE_CONFIRM_FLOOR_GAP_M = 0.3f

/** A confirming wall rises at least this: a sideboard's or a counter's side is no wall. */
private const val SQUARE_CONFIRM_MIN_HEIGHT_M = 1.5f

// ── Rising walls ────────────────────────────────────────────────────────────────────────────

/** Walls never rise below a few centimetres (so they stay visible) nor above a tall ceiling. */
const val WALL_MIN_HEIGHT = 0.05f
const val WALL_MAX_HEIGHT = 4.5f

/** How far beyond a wall's ends the aim may land and still drive that wall's height. */
private const val WALL_SPAN_SLACK = 0.25f

/**
 * Height the user is aiming at on the vertical wall standing on floor line `a`–`b`: the
 * camera ray ([origin], [direction]) is intersected with that wall's vertical plane and the
 * hit's height above the line is returned, capped at [WALL_MAX_HEIGHT].
 *
 * `null` when the ray runs parallel to the wall, points away from it, lands far past its ends,
 * or crosses the wall's plane under [WALL_MIN_HEIGHT] — aiming at the floor at the wall's foot
 * reads no height at all, so Set height has nothing to lock (#4751) instead of the minimum.
 */
fun measureWallHeightFromRay(
    origin: Position,
    direction: Position,
    a: Position,
    b: Position,
    spanSlack: Float = WALL_SPAN_SLACK,
): Float? = wallRayHit(origin, direction, a, b, spanSlack)?.height

/** Where a camera ray crosses one wall: the height it reaches and how far along the ray. */
private class WallRayHit(val height: Float, val rayDistance: Float)

private fun wallRayHit(origin: Position, direction: Position, a: Position, b: Position, spanSlack: Float): WallRayHit? {
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
    // At or under the foot the aim is on the floor, not on the wall: no height (#4751).
    val height = hy - a.y
    if (height < WALL_MIN_HEIGHT) return null
    val directionLength = sqrt(direction.x * direction.x + direction.y * direction.y + direction.z * direction.z)
    return WallRayHit(height.coerceAtMost(WALL_MAX_HEIGHT), t * directionLength)
}

/**
 * Height aimed at across every wall of a floor outline: the wall the ray crosses first — the
 * shortest distance along the aim — wins, the way the eye reads "the wall I am looking at".
 * A long wall whose middle is far away still wins over a short one behind it.
 */
fun measureRoomHeightFromRay(
    origin: Position,
    direction: Position,
    outline: List<Position>,
    closed: Boolean,
): Float? {
    if (outline.size < 2) return null
    val segmentCount = if (closed && outline.size > 2) outline.size else outline.size - 1
    return (0 until segmentCount)
        .mapNotNull { i -> wallRayHit(origin, direction, outline[i], outline[(i + 1) % outline.size], spanSlack = 0f) }
        .minByOrNull { it.rayDistance }
        ?.height
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
 * Triangulation of a simple polygon seen from above (x/z), by SceneView's own
 * [Earcut] (sceneview-core). Works for the L- and T-shaped rooms a rectangle model cannot
 * read. Returns indices into [outline]; empty for a degenerate outline.
 */
fun measureTriangulateXZ(outline: List<Position>): IntArray {
    if (outline.size < 3) return IntArray(0)
    val flat = DoubleArray(outline.size * 2)
    outline.forEachIndexed { i, p ->
        flat[i * 2] = p.x.toDouble()
        flat[i * 2 + 1] = p.z.toDouble()
    }
    return Earcut.triangulate(flat).toIntArray()
}

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
