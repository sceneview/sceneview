package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Doors and windows marked on a measured wall or on the walls of a measured room, so the net
 * wall area — what a painter or a plasterer covers — leaves them out. Pure, so each rule runs in
 * a JVM test.
 *
 * A figure's walls stand on its floor outline, each from one point to the next, and on a room
 * from the last corner back to the first; they are counted in that order. An opening is marked on
 * one of them, in that wall's own frame: along it from its first point, up from the floor. A
 * measured wall is a figure of one wall.
 */

/**
 * A door or a window on a figure's walls: a rectangle on the [wall] it stands on (counted from the
 * first point's), [from]..[to] metres along that wall from its first point, [bottom]..[top] metres
 * above the floor. Kept by its wall, it stays where it was marked, as wide, when the walls before
 * it are re-measured longer or shorter.
 */
data class MeasureOpening(val from: Float, val to: Float, val bottom: Float, val top: Float, val wall: Int = 0) {
    val width: Float get() = to - from
    val height: Float get() = top - bottom
}

/**
 * A point on a figure's walls: on the [wall] it was read on (counted from the first point's),
 * [along] that wall from its first point, [up] from the floor.
 */
data class MeasureWallPoint(val along: Float, val up: Float, val wall: Int = 0)

/**
 * Where the camera ray ([origin], [direction]) meets the wall standing [height] metres on the
 * floor line [a]–[b], in the wall's frame and kept on the wall. `null` when the ray runs along
 * the wall, points away from it, or lands more than [MEASURE_OPENING_SLACK_M] off its edges.
 */
fun measureWallPointFromRay(
    origin: Position,
    direction: Position,
    a: Position,
    b: Position,
    height: Float,
): MeasureWallPoint? = measureFigureWallPointFromRay(origin, direction, listOf(a, b), closed = false, height)

/**
 * Where the camera ray ([origin], [direction]) meets the walls standing [height] metres on a
 * figure's floor [outline] — looping back to its first point when [closed] — in the frame of the
 * wall it meets, and kept on it. `null` when it meets none within [MEASURE_OPENING_SLACK_M] of its
 * edges, or meets the nearest one above or below it.
 *
 * The nearest wall the ray crosses wins; only when it crosses none does an aim just past a
 * wall's end read on that end. With [onWall], the ray reads that wall only: an opening's second
 * corner stays on the wall of its first.
 */
fun measureFigureWallPointFromRay(
    origin: Position,
    direction: Position,
    outline: List<Position>,
    closed: Boolean,
    height: Float,
    onWall: Int? = null,
): MeasureWallPoint? {
    val walls = wallCount(outline, closed)
    var best: WallHit? = null
    var bestWall = -1
    var bestOnWall = false
    for (i in 0 until walls) {
        if (onWall != null && i != onWall) continue
        val hit = wallRayHit(origin, direction, outline[i], outline[(i + 1) % outline.size]) ?: continue
        if (hit.along < -MEASURE_OPENING_SLACK_M || hit.along > hit.length + MEASURE_OPENING_SLACK_M) continue
        val within = hit.along >= 0f && hit.along <= hit.length
        val current = best
        if (current == null || (within && !bestOnWall) || (within == bestOnWall && hit.t < current.t)) {
            best = hit
            bestWall = i
            bestOnWall = within
        }
    }
    val hit = best ?: return null
    if (hit.up < -MEASURE_OPENING_SLACK_M || hit.up > height + MEASURE_OPENING_SLACK_M) return null
    return MeasureWallPoint(hit.along.coerceIn(0f, hit.length), hit.up.coerceIn(0f, height), bestWall)
}

/**
 * The wall of a figure's floor [outline] (looping back when [closed]) that [opening] stands on.
 * `null` when the outline has no such wall.
 */
fun measureOpeningWall(opening: MeasureOpening, outline: List<Position>, closed: Boolean): Int? =
    opening.wall.takeIf { it in 0 until wallCount(outline, closed) }

/**
 * [opening] kept on the wall it stands on ([measureOpeningWall]), as long as that wall is now.
 * `null` without that wall.
 */
fun measureOpeningOnWall(opening: MeasureOpening, outline: List<Position>, closed: Boolean): MeasureOpening? {
    val wall = measureOpeningWall(opening, outline, closed) ?: return null
    val length = measureDistanceMeters(outline[wall], outline[(wall + 1) % outline.size])
    return opening.copy(from = opening.from.coerceIn(0f, length), to = opening.to.coerceIn(0f, length))
}

/**
 * The opening with [first] and [second] at opposite corners, or `null` when it is narrower or
 * lower than [MEASURE_OPENING_MIN_SIDE_M] — two taps on the same spot mark nothing — or when
 * they were read on two walls.
 */
fun measureOpening(first: MeasureWallPoint, second: MeasureWallPoint): MeasureOpening? {
    if (first.wall != second.wall) return null
    val opening = MeasureOpening(
        from = min(first.along, second.along),
        to = max(first.along, second.along),
        bottom = min(first.up, second.up),
        top = max(first.up, second.up),
        wall = first.wall,
    )
    return opening.takeIf { it.width >= MEASURE_OPENING_MIN_SIDE_M && it.height >= MEASURE_OPENING_MIN_SIDE_M }
}

/**
 * The area the [openings] cover on a wall [width] × [height]: each one kept on the wall, and
 * where two overlap the overlap counted once — a window marked twice is one window.
 */
fun measureOpeningsArea(openings: List<MeasureOpening>, width: Float, height: Float): Float {
    val boxes = openings.mapNotNull {
        val from = it.from.coerceIn(0f, width)
        val to = it.to.coerceIn(0f, width)
        val bottom = it.bottom.coerceIn(0f, height)
        val top = it.top.coerceIn(0f, height)
        if (to > from && top > bottom) MeasureOpening(from, to, bottom, top) else null
    }
    if (boxes.isEmpty()) return 0f
    // Union of rectangles: in each strip between two vertical edges, the union of the spans.
    val xs = boxes.flatMap { listOf(it.from, it.to) }.distinct().sorted()
    var area = 0f
    for (i in 0 until xs.size - 1) {
        val left = xs[i]
        val right = xs[i + 1]
        val spans = boxes.filter { it.from <= left && it.to >= right }.map { it.bottom to it.top }.sortedBy { it.first }
        var covered = 0f
        var start = Float.NaN
        var end = Float.NaN
        for ((bottom, top) in spans) {
            if (start.isNaN() || bottom > end) {
                if (!start.isNaN()) covered += end - start
                start = bottom
                end = top
            } else if (top > end) {
                end = top
            }
        }
        if (!start.isNaN()) covered += end - start
        area += covered * (right - left)
    }
    return area
}

/**
 * The area the [openings] cover on a figure's walls, each [segments] wide (in the figure's wall
 * order) and [height] high: wall by wall ([measureOpeningsArea]), each opening on its own wall.
 */
fun measureFigureOpeningsArea(openings: List<MeasureOpening>, segments: List<Float>, height: Float): Float =
    segments.indices.sumOf { wall ->
        measureOpeningsArea(openings.filter { it.wall == wall }, segments[wall], height).toDouble()
    }.toFloat()

/** The four corners of [opening] on the wall [a]–[b] (its foot), in world space, for drawing. */
fun measureOpeningCorners(opening: MeasureOpening, a: Position, b: Position): List<Position> {
    val ex = b.x - a.x
    val ez = b.z - a.z
    val length = sqrt(ex * ex + ez * ez).takeIf { it >= MIN_WALL_LENGTH_M } ?: return emptyList()
    fun at(along: Float, up: Float) = Position(a.x + ex / length * along, a.y + up, a.z + ez / length * along)
    return listOf(
        at(opening.from, opening.bottom),
        at(opening.to, opening.bottom),
        at(opening.to, opening.top),
        at(opening.from, opening.top),
    )
}

/**
 * The four corners of [opening], on the wall of a figure's floor [outline] (looping back when
 * [closed]) it stands on, in world space, for drawing.
 */
fun measureFigureOpeningCorners(opening: MeasureOpening, outline: List<Position>, closed: Boolean): List<Position> {
    val wall = measureOpeningWall(opening, outline, closed) ?: return emptyList()
    val local = measureOpeningOnWall(opening, outline, closed) ?: return emptyList()
    return measureOpeningCorners(local, outline[wall], outline[(wall + 1) % outline.size])
}

/** How far off a wall's edges an aim still marks a corner on the edge itself. */
const val MEASURE_OPENING_SLACK_M = 0.1f

/** The smallest side of an opening: under it, the two taps were the same spot. */
const val MEASURE_OPENING_MIN_SIDE_M = 0.1f

private const val MIN_WALL_LENGTH_M = 0.05f
private const val RAY_PARALLEL_EPSILON = 1e-4f

/** How many walls stand on [outline]: one per pair of points, and the closing one on a loop. */
private fun wallCount(outline: List<Position>, closed: Boolean): Int = when {
    closed && outline.size >= MeasureBoard.MIN_OUTLINE_POINTS -> outline.size
    else -> (outline.size - 1).coerceAtLeast(0)
}

/** Where a ray meets the plane of the wall [a]–[b]: how far along the ray, and where on the wall. */
private class WallHit(val t: Float, val along: Float, val up: Float, val length: Float)

/** `null` when the wall is too short, or the ray runs along its plane or points away from it. */
private fun wallRayHit(origin: Position, direction: Position, a: Position, b: Position): WallHit? {
    val ex = b.x - a.x
    val ez = b.z - a.z
    val length = sqrt(ex * ex + ez * ez)
    if (length < MIN_WALL_LENGTH_M) return null
    val nx = -ez / length
    val nz = ex / length
    val denominator = direction.x * nx + direction.z * nz
    if (abs(denominator) < RAY_PARALLEL_EPSILON) return null
    val t = ((a.x - origin.x) * nx + (a.z - origin.z) * nz) / denominator
    if (t <= 0f) return null
    val hx = origin.x + direction.x * t
    val hy = origin.y + direction.y * t
    val hz = origin.z + direction.z * t
    return WallHit(t = t, along = ((hx - a.x) * ex + (hz - a.z) * ez) / length, up = hy - a.y, length = length)
}
