package io.github.sceneview.demo.demos.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One of the room's directions: its walls run along [direction] (radians on the floor, `[0, π)`),
 * [weight] metres of them in all. [square] when it is the room's main axis or square to it.
 */
class WallFamily(val direction: Float, val weight: Float, val square: Boolean)

/**
 * What makes a vertical surface a wall for the live walls ([PlaneWallSource], shared by 3D Scan and
 * Measure), on the room survey's terms. The survey ([RoomSurveyor]) does not call these rules: its
 * structure engine reads the depth cloud's walls as lines, not planes. What they share is its thresholds — how high a wall
 * rises ([MIN_TOP_M]), how much of its height is seen ([MIN_SPAN_M]), how long it is seen
 * ([MIN_SEEN_M], [OBLIQUE_MIN_SEEN_M]), how much lower furniture is ([FURNITURE_UNDER_M]) — and a
 * wall seen only above furniture is a wall for both. Where a plane can say more than a depth cell,
 * the rule is the live walls' own, and says so ([DUPLICATE_M]).
 *
 * - It rises past standing height and is seen over [MIN_SPAN_M] of its height, or over the
 *   furniture before it ([standsAsWall], [furnitureTop]). Its foot is not looked for, as the
 *   survey does not look for it: furniture ARCore fitted no plane to hides it as often as not.
 * - It was seen long enough ([seenEnough]), along one of the room's directions ([families]).
 * - It is neither a second fit of a wall nearby ([duplicates]), nor a piece of furniture before a
 *   wall ([standsBefore], [isSideOf]); a piece fitted a little askew along a wall is that wall
 *   ([shardOf]).
 * - It is drawn as tall as the room ([height]), never as the top of what was seen of it.
 */
object WallRules {
    /** A wall is seen over this much of its height at least, unless furniture before it hides the rest: the survey's [RoomSurveyor.WALL_CELL_SPAN_M]. */
    const val MIN_SPAN_M = RoomSurveyor.WALL_CELL_SPAN_M

    /** A wall's highest point seen is at least this high over the floor: the survey's [RoomSurveyor.WALL_MIN_TOP_M]. */
    const val MIN_TOP_M = RoomSurveyor.WALL_MIN_TOP_M

    /** The height drawn when the ceiling was neither seen nor measured: a common room's. */
    const val DEFAULT_HEIGHT_M = 2.5f

    /** Shorter than this along the floor, it is not a wall yet: the survey's [RoomSurveyor.MIN_WALL_SEEN_M]. */
    const val MIN_SEEN_M = RoomSurveyor.MIN_WALL_SEEN_M

    /** A wall off the room's square needs this much seen, the survey's [RoomSurveyor.OBLIQUE_MIN_M]: a short slanted piece is furniture. */
    const val OBLIQUE_MIN_SEEN_M = RoomSurveyor.OBLIQUE_MIN_M

    /**
     * Lower than the wall behind it by more than this, it is furniture: [RoomSurveyor.FURNITURE_UNDER_M].
     * Strictly more, as the survey's structure engine cuts: exactly this much lower is still a wall.
     */
    const val FURNITURE_UNDER_M = RoomSurveyor.FURNITURE_UNDER_M

    /** Two walls further apart than this in direction are two of the room's directions. */
    const val APART_DEGREES = 15f

    /** A direction this close to square with the room's main axis is square. */
    const val SNAP_DEGREES = 7f

    /**
     * Two near-parallel fits whose lines are this close are one wall seen twice. The live walls'
     * own, wider than the survey's 0.3 m between layers of one wall (`WallLines.WALL_MERGE_M`): ARCore
     * fitted one wall twice 0.30 m apart, and a plane hanging 0.35 m before a wall along it, upper
     * cabinets' fronts, is that wall rather than one of its own (at 0.3 m, the replayed kitchen shows
     * five walls for four). Two real walls 0.3 to 0.5 m apart, parallel and along each other, a thin
     * partition's two faces, read as one.
     */
    const val DUPLICATE_M = 0.5f

    /** Furniture stands this far before a wall, at most: further, it is a wall of its own. */
    const val BEHIND_MAX_M = 1f

    /** Closer than this, it is the wall itself, a picture or a door on it. */
    const val BEHIND_MIN_M = 0.1f

    /** This much of the shorter of two pieces lies along the other: they face each other. */
    const val OVERLAP_SHARE = 0.5f

    /** What reaches this close to the ceiling is a wall, however much higher the one behind it is. */
    const val CEILING_TOUCH_M = 0.25f

    /** A piece fitted this far askew of a wall at most, with both ends on it, is a piece of that wall. */
    const val SHARD_MIN_DEGREES = 12f
    const val SHARD_MAX_DEGREES = 25f

    /** How far a shard's ends, or a side's, may be off the line they sit on. */
    const val ASIDE_M = 0.3f

    /**
     * A gap along a wall no longer than this, between a shard and the wall, was not seen yet: the
     * same as between two pieces on one line ([PlaneWallSource.SAME_WALL_GAP_M]). The survey's
     * structure engine lets the shards of a line stand up to 1 m apart; the live walls keep one gap.
     */
    const val SHARD_GAP_M = PlaneWallSource.SAME_WALL_GAP_M

    /** Furniture's side, from its front back to the wall, is no longer than this. */
    const val SIDE_MAX_M = 1.3f

    /** A side is square to the front it closes, to within this. */
    const val SIDE_DEGREES = 20f

    /** A direction holding less than this share of the walls seen is not one of the room's. */
    const val MIN_SHARE = 0.03f

    /** The same for a direction square to the room: a short return wall is expected. */
    const val SQUARE_SHARE = 0.01f

    /** Measurement noise on the thresholds above, in metres. */
    const val TOLERANCE_M = 0.01f

    /**
     * How far past a threshold in metres a surface must go to lose the verdict it had, wall or
     * furniture: ARCore refits a plane a few centimetres either way, and a wall does not flicker.
     */
    const val HOLD_M = 0.05f

    /** The same for a threshold in degrees. */
    const val HOLD_DEGREES = 2f

    /**
     * Whether a vertical surface seen from [bottom] to [top] (world heights) stands as a wall over a
     * floor at [floorY], as the survey reads a wall cell: it rises past standing height
     * ([MIN_TOP_M]), and it is seen over [MIN_SPAN_M] of its height — or it stands over the furniture
     * before it, up to [underY] ([furnitureTop]), which hides the rest. Where its foot is does not
     * count: a panel standing in the room with no wall within [BEHIND_MAX_M] behind it is a wall here
     * as in the survey. A first verdict takes both thresholds as they are: a cabinet whose top ARCore
     * fits a centimetre short of standing height is not a wall. One that [held], that stood the last
     * time, keeps standing until it is [TOLERANCE_M] and [HOLD_M] past either threshold.
     */
    fun standsAsWall(bottom: Float, top: Float, floorY: Float, held: Boolean = false, underY: Float = floorY): Boolean {
        val slack = if (held) TOLERANCE_M + HOLD_M else 0f
        val tall = top - floorY >= MIN_TOP_M - slack
        return tall && (top - bottom >= MIN_SPAN_M - slack || underY > floorY)
    }

    /**
     * How high (world height) the surface [polygon] (world xyz, flat) reaches when it is furniture
     * before [wall], hiding its foot — within [BEHIND_MAX_M] of its line, along at least
     * [OVERLAP_SHARE] of it, lower than its top by more than [FURNITURE_UNDER_M]: a sofa's front or
     * its top, a sideboard. `null` when it is not.
     */
    fun furnitureTop(wall: Segment, polygon: FloatArray): Float? {
        var near = Float.MAX_VALUE
        var from = Float.MAX_VALUE
        var to = -Float.MAX_VALUE
        var top = -Float.MAX_VALUE
        for (i in 0 until polygon.size / 3) {
            val x = polygon[i * 3]
            val z = polygon[i * 3 + 2]
            near = min(near, wall.distanceAcross(x, z))
            val t = wall.along(x, z)
            from = min(from, t)
            to = max(to, t)
            top = max(top, polygon[i * 3 + 1])
        }
        if (near > BEHIND_MAX_M || wall.top - top <= FURNITURE_UNDER_M) return null
        return top.takeIf { min(wall.to, to) - max(wall.from, from) >= OVERLAP_SHARE * wall.length }
    }

    /**
     * The room's height, floor to ceiling, drawn for its walls: the ceiling seen ([ceilingSeen],
     * metres over the floor), else the one measured in the same session ([ceilingMeasured]), else
     * [DEFAULT_HEIGHT_M].
     */
    fun height(ceilingSeen: Float?, ceilingMeasured: Float?): Float = ceilingSeen ?: ceilingMeasured ?: DEFAULT_HEIGHT_M

    /** Whether a wall seen along [length] metres is seen enough, on the room's square or off it. */
    fun seenEnough(length: Float, square: Boolean): Boolean =
        length >= (if (square) MIN_SEEN_M else OBLIQUE_MIN_SEEN_M) - TOLERANCE_M

    /**
     * The room's directions, from walls running along [directions] (radians on the floor) seen
     * along [weights] metres: grouped within [APART_DEGREES], the heaviest first and the main axis,
     * those within [SNAP_DEGREES] of square to it squared, those holding too small a share left out.
     * [squareBefore] says, wall by wall, whether its direction was square the last time (`null`:
     * not judged): a direction keeps that verdict until it is [HOLD_DEGREES] past [SNAP_DEGREES].
     */
    fun families(directions: FloatArray, weights: FloatArray, squareBefore: List<Boolean?> = emptyList()): List<WallFamily> {
        val gathered = ArrayList<Gathered>()
        for (i in directions.indices.sortedByDescending { weights[it] }) {
            val direction = directions[i]
            val near = gathered.minByOrNull { offDegrees(it.direction, direction) }
                ?.takeIf { offDegrees(it.direction, direction) <= APART_DEGREES }
            (near ?: Gathered().also { gathered += it }).add(direction, weights[i], squareBefore.getOrNull(i))
        }
        if (gathered.isEmpty()) return emptyList()
        val total = gathered.sumOf { it.weight.toDouble() }.toFloat()
        val axis = gathered.maxBy { it.weight }.direction
        return gathered.map { group ->
            val residual = offSquare(group.direction, axis)
            val hold = when (group.squareBefore) {
                null -> 0f
                true -> HOLD_DEGREES
                false -> -HOLD_DEGREES
            }
            val square = abs(degrees(residual)) <= SNAP_DEGREES + hold
            WallFamily(if (square) positive(group.direction - residual) else group.direction, group.weight, square)
        }.filter { it.weight >= total * (if (it.square) SQUARE_SHARE else MIN_SHARE) }
    }

    /** The family a wall running along [direction] belongs to: the nearest within [APART_DEGREES], `null` for none. */
    fun familyOf(direction: Float, families: List<WallFamily>): WallFamily? = families
        .filter { offDegrees(it.direction, direction) <= APART_DEGREES }
        .minByOrNull { offDegrees(it.direction, direction) }

    /**
     * Whether [a] and [b] are one wall fitted twice, a little apart and a little askew: near
     * parallel, the shorter's middle within [DUPLICATE_M] of the longer's line, and lying along it
     * for at least [OVERLAP_SHARE] of its length.
     */
    fun duplicates(a: Segment, b: Segment): Boolean {
        val (short, long) = if (a.length <= b.length) a to b else b to a
        if (offDegrees(a.angle, b.angle) > PlaneWallSource.SAME_WALL_DEGREES) return false
        if (long.distanceAcross(short.centreX, short.centreZ) > DUPLICATE_M) return false
        return long.overlapAlong(short) >= OVERLAP_SHARE * short.length
    }

    /**
     * Whether [front] is furniture standing before the wall [behind]: near parallel, shorter,
     * lower by more than [FURNITURE_UNDER_M], [BEHIND_MIN_M] to [BEHIND_MAX_M] before it and
     * mostly along it — unless it reaches the ceiling at [ceilingY], as a wall does. [wasFurniture]
     * is the verdict the last time (`null`: not judged), which holds until it is [HOLD_M] or
     * [HOLD_DEGREES] past a threshold.
     */
    fun standsBefore(front: Segment, behind: Segment, ceilingY: Float, wasFurniture: Boolean? = null): Boolean {
        val m = lean(wasFurniture) * HOLD_M
        if (front.top >= ceilingY - CEILING_TOUCH_M - m) return false
        if (behind.length <= front.length + m || behind.top - front.top <= FURNITURE_UNDER_M + m) return false
        if (offDegrees(front.angle, behind.angle) > APART_DEGREES - lean(wasFurniture) * HOLD_DEGREES) return false
        val across = behind.distanceAcross(front.centreX, front.centreZ)
        if (across < BEHIND_MIN_M + m || across > BEHIND_MAX_M - m) return false
        return behind.overlapAlong(front) >= OVERLAP_SHARE * front.length + m
    }

    /**
     * Whether [side] is the side of furniture [front] standing before the wall [behind]: short,
     * square to the front, lower than the wall by more than [FURNITURE_UNDER_M], one end on the front's line by its end and the
     * other on the wall's. [wasFurniture] holds the last verdict, as for [standsBefore].
     */
    fun isSideOf(side: Segment, front: Segment, behind: Segment, wasFurniture: Boolean? = null): Boolean {
        val m = lean(wasFurniture) * HOLD_M
        if (side.length > SIDE_MAX_M - m || behind.top - side.top <= FURNITURE_UNDER_M + m) return false
        val square = abs(offDegrees(side.angle, front.angle) - RIGHT_ANGLE_DEGREES)
        if (square > SIDE_DEGREES - lean(wasFurniture) * HOLD_DEGREES) return false
        val aside = ASIDE_M - m
        fun onFront(x: Float, z: Float) = front.distanceAcross(x, z) <= aside &&
            front.along(x, z) in (front.from - aside)..(front.to + aside)
        fun onBehind(x: Float, z: Float) = behind.distanceAcross(x, z) <= aside
        return (onFront(side.ax, side.az) && onBehind(side.bx, side.bz)) ||
            (onFront(side.bx, side.bz) && onBehind(side.ax, side.az))
    }

    /**
     * Whether [piece] is a piece of the longer wall [host] fitted askew: [SHARD_MIN_DEGREES] to
     * [SHARD_MAX_DEGREES] off it, both its ends within [ASIDE_M] of the host's line, and no more
     * than [SHARD_GAP_M] from it along that line.
     */
    fun shardOf(piece: Segment, host: Segment): Boolean {
        val off = offDegrees(piece.angle, host.angle)
        if (off < SHARD_MIN_DEGREES || off > SHARD_MAX_DEGREES || host.length <= piece.length) return false
        if (host.distanceAcross(piece.ax, piece.az) > ASIDE_M || host.distanceAcross(piece.bx, piece.bz) > ASIDE_M) return false
        return host.gapAlong(piece) <= SHARD_GAP_M
    }

    /** How far apart two directions are, in degrees, `0..90`: a wall's direction has no sign. */
    fun offDegrees(a: Float, b: Float): Float = abs(degrees(WallMesh.wrapHalfTurn(a - b)))

    /**
     * Which way a verdict held the last time moves the furniture thresholds: `1` it was a wall, so
     * it takes plainly furniture to hide it; `-1` it was furniture, so it takes plainly a wall to
     * show it; `0` it is new.
     */
    private fun lean(wasFurniture: Boolean?): Float = when (wasFurniture) {
        null -> 0f
        true -> -1f
        false -> 1f
    }

    /** Walls' directions summed as doubled angles, weighted: their mean has no sign either. */
    private class Gathered {
        private var c = 0f
        private var s = 0f
        var weight = 0f
            private set
        val direction: Float get() = positive(atan2(s, c) / 2f)

        /** Whether one of its walls was square the last time; `false` when only askew; `null` when none was judged. */
        var squareBefore: Boolean? = null
            private set

        fun add(direction: Float, weight: Float, wasSquare: Boolean?) {
            c += cos(direction * 2f) * weight
            s += sin(direction * 2f) * weight
            this.weight += weight
            if (wasSquare == true || squareBefore == null) squareBefore = wasSquare ?: squareBefore
        }
    }

    /** How far [direction] is off square with [axis], in radians, `-π/4..π/4`. */
    private fun offSquare(direction: Float, axis: Float): Float {
        val quarter = (PI / 2).toFloat()
        val off = WallMesh.wrapHalfTurn(direction - axis)
        return off - (off / quarter).roundToInt() * quarter
    }

    private fun positive(angle: Float): Float {
        val a = WallMesh.wrapHalfTurn(angle)
        return if (a < 0f) a + PI.toFloat() else a
    }

    private fun degrees(radians: Float): Float = radians * HALF_TURN_DEGREES / PI.toFloat()

    private const val HALF_TURN_DEGREES = 180f
    private const val RIGHT_ANGLE_DEGREES = 90f
}

/** The middle of what was seen of this wall, on the floor. */
val Segment.centreX: Float get() = (ax + bx) / 2f
val Segment.centreZ: Float get() = (az + bz) / 2f

/** How much of [other], projected on this line, lies along this wall's span: `0` or less where it does not. */
fun Segment.overlapAlong(other: Segment): Float {
    val s0 = along(other.ax, other.az)
    val s1 = along(other.bx, other.bz)
    return min(to, max(s0, s1)) - max(from, min(s0, s1))
}
