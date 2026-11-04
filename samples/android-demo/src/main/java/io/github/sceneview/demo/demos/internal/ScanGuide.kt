package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The side of the phone the guide's target is on, in words (#4540 live walls): "turn left, this
 * wall is missing". [Ahead] is within view. Read off [ScanHeading.degrees] with hysteresis
 * ([ScanGuide.turnOf]), so a phone swaying across a boundary does not flip it.
 */
enum class ScanTurn { Left, Right, Behind, Ahead }

/** The open floor [edge] (`ax, az, bx, bz`) the scan should go to next, and the floor point ([x], [z]) the guide leads to. */
class ScanCue(val edge: FloatArray, val x: Float, val z: Float)

/**
 * Where the guide leads, on the floor: ([x], [z]), the middle of the next gap no wall stands on
 * yet, or — [fix] — the part Fix now is filling in.
 */
class ScanTarget(val x: Float, val z: Float, val fix: Boolean = false)

/**
 * From the phone to a target on the floor: [degrees] to turn, clockwise (to the right) from where
 * the phone faces on the floor, in `(-180, 180]`; and [distance], in metres on the floor from
 * the phone's feet.
 */
data class ScanHeading(val degrees: Float, val distance: Float)

/**
 * What the coaching line says of the guide: the [turn] to the target, how far it is ([metres],
 * in [ScanGuide.STEP_M] steps; `null` once there), and whether it is a part to fix ([fix]).
 */
data class ScanGuideHint(val turn: ScanTurn, val metres: Float?, val fix: Boolean)

/**
 * Where a room's scan goes next, and how far along it is: pure functions of the walls found, the
 * floor seen and the phone's pose, so the HUD's arrow, its ribbon and its gauge run in a JVM test.
 */
object ScanGuide {

    /**
     * The next open edge to scan from [pose] among [openEdges] ([openFloorEdges]), and the floor
     * point the guide leads to, its middle: the longest gap, a nearer one winning a tie of length —
     * the wall most worth finding. The gap led to so far ([previous]) is kept while it is still
     * open and scores at least [STICK_SHARE] of the best: a step should not swap the target for
     * another gap a few centimetres better. `null` without an edge or a pose.
     */
    fun cue(pose: DebugPose?, openEdges: List<FloatArray>, previous: FloatArray? = null): ScanCue? {
        if (pose == null || openEdges.isEmpty()) return null
        fun score(e: FloatArray): Float {
            val length = hypot(e[2] - e[0], e[3] - e[1])
            val mx = (e[0] + e[2]) / 2f - pose.x
            val mz = (e[1] + e[3]) / 2f - pose.z
            return length / (1f + NEARNESS * hypot(mx, mz))
        }
        val best = openEdges.maxBy(::score)
        // The edges are rebuilt whenever the walls or the floor change: the same gap is a new array.
        val kept = previous?.let { p ->
            openEdges.minBy { middleGap(it, p) }.takeIf { middleGap(it, p) <= STICK_M && score(it) >= STICK_SHARE * score(best) }
        }
        val edge = kept ?: best
        return ScanCue(edge, (edge[0] + edge[2]) / 2f, (edge[1] + edge[3]) / 2f)
    }

    /**
     * The heading from [pose] to the floor point ([x], [z]): pitch and roll do not count. The
     * phone faces where its forward points on the floor, held pitched down or not; pointed
     * straight down, where its top edge points.
     */
    fun heading(pose: DebugPose, x: Float, z: Float): ScanHeading {
        val forward = pose.rotate(0f, 0f, -1f)
        val top = pose.rotate(0f, 1f, 0f)
        var fx = forward.x
        var fz = forward.z
        if (hypot(fx, fz) < FLAT_EPSILON) {
            fx = top.x
            fz = top.z
        }
        val dx = x - pose.x
        val dz = z - pose.z
        val distance = hypot(dx, dz)
        // On the floor, the phone's right is its forward turned a quarter clockwise seen from above.
        val ahead = dx * fx + dz * fz
        val side = dx * -fz + dz * fx
        if (abs(ahead) < EPSILON && abs(side) < EPSILON) return ScanHeading(0f, distance)
        val degrees = Math.toDegrees(atan2(side, ahead).toDouble()).toFloat()
        return ScanHeading(if (degrees <= -HALF_TURN) HALF_TURN else degrees, distance)
    }

    /**
     * The words for [degrees], given the side said so far ([previous]): within [AHEAD_DEGREES] it
     * is ahead, past [BEHIND_DEGREES] behind, else left or right — each boundary moved
     * [TURN_MARGIN_DEGREES] out for the side already said, so a sway across it keeps the words.
     */
    fun turnOf(degrees: Float, previous: ScanTurn?): ScanTurn {
        val a = abs(degrees)
        val ahead = AHEAD_DEGREES + if (previous == ScanTurn.Ahead) TURN_MARGIN_DEGREES else 0f
        val behind = BEHIND_DEGREES - if (previous == ScanTurn.Behind) TURN_MARGIN_DEGREES else 0f
        return when {
            a <= ahead -> ScanTurn.Ahead
            a >= behind -> ScanTurn.Behind
            degrees > 0f -> ScanTurn.Right
            else -> ScanTurn.Left
        }
    }

    /**
     * Whether the phone faces the target ([degrees] off it): within [ALIGN_IN_DEGREES] it lines
     * up, and once lined up ([was]) it stays so until past [ALIGN_OUT_DEGREES].
     */
    fun aligned(degrees: Float, was: Boolean): Boolean = abs(degrees) <= if (was) ALIGN_OUT_DEGREES else ALIGN_IN_DEGREES

    /**
     * Whether the phone stands where the target is scanned from ([distance] metres off it): within
     * [REACHED_M], and once there ([was]) until past [LEAVE_M].
     */
    fun reached(distance: Float, was: Boolean): Boolean = distance <= if (was) LEAVE_M else REACHED_M

    /**
     * [distance] as said, in [STEP_M] steps: [shown], the one said so far, until [distance] is
     * [STEP_SLACK_M] past the middle between two steps, so a phone standing on a boundary does not
     * say both. `NaN` [shown] says nothing yet.
     */
    fun stepped(distance: Float, shown: Float): Float {
        val nearest = max(STEP_M, (distance / STEP_M).roundToInt() * STEP_M)
        if (shown.isNaN() || nearest == shown) return nearest
        return if (abs(distance - shown) > STEP_M / 2f + STEP_SLACK_M) nearest else shown
    }

    /**
     * Whether the walls close the room: [ROOM_WALLS] or more found, and what is left open of the
     * floor seen ([openEdges]) under [CLOSED_GAP_M] in all — a doorway's width.
     */
    fun closed(walls: Int, openEdges: List<FloatArray>): Boolean =
        walls >= ROOM_WALLS && openEdges.sumOf { hypot(it[2] - it[0], it[3] - it[1]).toDouble() } < CLOSED_GAP_M

    /**
     * The room's scan done so far, `0..1`, the one gauge the HUD shows: the walls found (up to
     * [ROOM_WALLS]) for [WALLS_SHARE] of it, the floor seen covered ([floorShare],
     * [CoverageGrid.coveredShareOf]) for the rest, weighed by the walls found: the floor seen early is
     * a corner of the room, so covering it all is not most of the room. A room whose walls [closed] it is done.
     */
    fun completion(walls: Int, floorShare: Float, closed: Boolean): Float {
        if (closed) return 1f
        val wallsPart = min(walls, ROOM_WALLS).toFloat() / ROOM_WALLS
        val floorPart = floorShare.coerceIn(0f, 1f) * wallsPart
        return max(0f, WALLS_SHARE * wallsPart + (1f - WALLS_SHARE) * floorPart).coerceAtMost(1f)
    }

    /** A room has four walls more often than not. */
    const val ROOM_WALLS = 4

    /** What the walls weigh in [completion]: a room is its walls first, its floor second. */
    const val WALLS_SHARE = 0.6f

    /** Less open than this, in metres, the room is closed: a door is left open. */
    const val CLOSED_GAP_M = 1f

    /** Within this of straight ahead (degrees), the target is ahead. */
    const val AHEAD_DEGREES = 30f

    /** Past this, it is behind the phone. */
    const val BEHIND_DEGREES = 135f

    /** How far a boundary of [turnOf] moves out for the side already said, degrees. */
    const val TURN_MARGIN_DEGREES = 10f

    /** Within this of the target, degrees, the phone lines up with it: the tick, and the arrow goes. */
    const val ALIGN_IN_DEGREES = 12f

    /** Lined up, the phone stays so until this far off, degrees. */
    const val ALIGN_OUT_DEGREES = 25f

    /**
     * The words follow the heading smoothed over about this long, seconds: a scan's sweep of the
     * phone either side of the target (±35° every 3 s) is not a turn to say. The arrow, the
     * line-up and the tick follow the phone itself.
     */
    const val WORDS_SMOOTHING_S = 1f

    /** Closer than this, metres, the target is scanned from where the phone stands: the ribbon goes. */
    const val REACHED_M = 1.5f

    /** There, the phone leaves the target once this far from it, metres. */
    const val LEAVE_M = 2f

    /** The distance is said in steps of this, metres: about a stride. */
    const val STEP_M = 0.5f

    /** How far past the middle of two steps the distance said moves on, metres. */
    const val STEP_SLACK_M = 0.1f

    /** A gap whose middle moved less than this, metres, is the same gap rebuilt. */
    const val STICK_M = 0.4f

    /** The gap led to keeps the guide while it scores this share of the best. */
    const val STICK_SHARE = 0.75f

    private const val HALF_TURN = 180f

    /** Under this, the phone's forward has no direction on the floor: it points straight down or up. */
    private const val FLAT_EPSILON = 0.05f

    /** How much a metre's distance shortens a gap in [cue]'s choice. */
    private const val NEARNESS = 0.25f
    private const val EPSILON = 1e-4f

    private fun middleGap(a: FloatArray, b: FloatArray): Float =
        hypot((a[0] + a[2]) / 2f - (b[0] + b[2]) / 2f, (a[1] + a[3]) / 2f - (b[1] + b[3]) / 2f)

    internal fun hypot(x: Float, z: Float): Float = sqrt(x * x + z * z)
}

/**
 * The guide of a capture, frame by frame (#4540, QA-24): the target it leads to ([ScanTarget]), the
 * [heading] to it, the words for its side ([turn]), whether the phone is [aligned] with it — a tick
 * then ([AlignTick]), and the arrow goes — and whether it is [reached], when the floor ribbon goes.
 * Pure: the live view feeds it each frame observed, a JVM test a trace.
 */
class ScanGuidance(private val tick: AlignTick = AlignTick()) {

    var target: ScanTarget? = null
        private set
    var heading: ScanHeading? = null
        private set
    var turn: ScanTurn? = null
        private set
    var aligned = false
        private set
    var reached = false
        private set

    /** The heading the words read, smoothed over [ScanGuide.WORDS_SMOOTHING_S]: its direction on the floor, `x` right and `y` ahead. */
    private var smoothRight = 0f
    private var smoothAhead = 0f
    private var smoothedAt = Long.MIN_VALUE

    /** The distance as said ([ScanGuide.stepped]), `NaN` without a target. */
    var said = Float.NaN
        private set

    /** The floor ribbon, `fx, fz, tx, tz`: from the phone's feet to the target; `null` without one or once there. */
    var ribbon: FloatArray? = null
        private set

    /** What the coaching line says of the guide, `null` without a target. */
    val hint: ScanGuideHint?
        get() {
            val t = target ?: return null
            val side = turn ?: return null
            return ScanGuideHint(side, said.takeUnless { reached || it.isNaN() }, t.fix)
        }

    /**
     * The phone at [pose], leading to [next] at [nowMs]: the tick's event when the phone has just
     * lined up with it ([AlignTick.update]), else `null`. A new target — another gap, or a part to
     * fix — starts the words and the line-up over.
     */
    fun update(pose: DebugPose?, next: ScanTarget?, nowMs: Long): MeasureHapticEvent? {
        if (pose == null || next == null) {
            clear()
            return tick.update(false, nowMs)
        }
        val old = target
        if (old == null || old.fix != next.fix || ScanGuide.hypot(old.x - next.x, old.z - next.z) > ScanGuide.STICK_M) {
            turn = null
            aligned = false
            reached = false
            said = Float.NaN
            smoothedAt = Long.MIN_VALUE
        }
        target = next
        val h = ScanGuide.heading(pose, next.x, next.z)
        heading = h
        aligned = ScanGuide.aligned(h.degrees, aligned)
        turn = ScanGuide.turnOf(smoothed(h.degrees, nowMs), turn)
        reached = ScanGuide.reached(h.distance, reached)
        said = ScanGuide.stepped(h.distance, said)
        ribbon = if (reached) null else floatArrayOf(pose.x, pose.z, next.x, next.z)
        return tick.update(aligned, nowMs)
    }

    /** [degrees] eased into the words' heading over [ScanGuide.WORDS_SMOOTHING_S], as a direction so it wraps behind. */
    private fun smoothed(degrees: Float, nowMs: Long): Float {
        val rad = Math.toRadians(degrees.toDouble())
        val r = kotlin.math.sin(rad).toFloat()
        val a = kotlin.math.cos(rad).toFloat()
        if (smoothedAt == Long.MIN_VALUE) {
            smoothRight = r
            smoothAhead = a
        } else {
            val dt = (nowMs - smoothedAt).coerceAtLeast(0L) / MS_PER_S
            val k = 1f - kotlin.math.exp(-dt / ScanGuide.WORDS_SMOOTHING_S)
            smoothRight += (r - smoothRight) * k
            smoothAhead += (a - smoothAhead) * k
        }
        smoothedAt = nowMs
        if (ScanGuide.hypot(smoothRight, smoothAhead) < 1e-4f) return degrees
        return Math.toDegrees(atan2(smoothRight, smoothAhead).toDouble()).toFloat()
    }

    fun clear() {
        smoothedAt = Long.MIN_VALUE
        target = null
        heading = null
        turn = null
        aligned = false
        reached = false
        said = Float.NaN
        ribbon = null
    }

    private companion object {
        const val MS_PER_S = 1000f
    }
}

/**
 * The floor ribbon from the phone's feet to the guide's target: chevrons pointing at the target,
 * flowing towards it when motion is on. Pure, so its shape runs in a JVM test.
 */
object ScanRibbon {
    /**
     * [ribbon] (`fx, fz, tx, tz`, [ScanGuidance.ribbon]) as chevrons on the floor at [y] into
     * [mesh], shifted [phase] metres towards the target — `0` under reduced motion — and starting
     * [START_M] ahead of the feet, which the phone never sees. Returns the chevrons drawn.
     */
    fun chevrons(mesh: DebugMesh, ribbon: FloatArray, y: Float, phase: Float = 0f): Int {
        val dx = ribbon[2] - ribbon[0]
        val dz = ribbon[3] - ribbon[1]
        val length = ScanGuide.hypot(dx, dz)
        if (length <= START_M + CHEVRON_M) return 0
        val ux = dx / length
        val uz = dz / length
        // Across the ribbon, on the floor.
        val sx = -uz * WIDTH_M / 2f
        val sz = ux * WIDTH_M / 2f
        val shift = phase - floor(phase / PERIOD_M) * PERIOD_M
        var count = 0
        var t = START_M + shift
        while (t + CHEVRON_M <= length) {
            val ax = ribbon[0] + ux * t
            val az = ribbon[1] + uz * t
            val tipX = ax + ux * CHEVRON_M
            val tipZ = az + uz * CHEVRON_M
            // A chevron: two arms, each a band of [STROKE_M], meeting at the tip.
            for (s in listOf(-1f, 1f)) {
                val ex = ax + s * sx
                val ez = az + s * sz
                val a = mesh.vertex(ex, y, ez)
                val b = mesh.vertex(tipX, y, tipZ)
                val c = mesh.vertex(tipX - ux * STROKE_M, y, tipZ - uz * STROKE_M)
                val d = mesh.vertex(ex - ux * STROKE_M, y, ez - uz * STROKE_M)
                mesh.quad(a, b, c, d)
            }
            count++
            t += PERIOD_M
        }
        return count
    }

    /** The ribbon's width on the floor, metres: read from standing height. */
    const val WIDTH_M = 0.24f

    /** A chevron's depth along the ribbon, and its stroke. */
    const val CHEVRON_M = 0.14f
    const val STROKE_M = 0.05f

    /** One chevron every this, metres. */
    const val PERIOD_M = 0.35f

    /** The ribbon starts this far ahead of the feet, metres. */
    const val START_M = 0.3f

    /** How fast the chevrons flow towards the target, metres a second: a slow walk. */
    const val FLOW_M_PER_S = 0.5f
}
