package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.lookTowards
import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/*
 * What Measure (#4732) adds on top of the shared live walls: the reticle telling a wall's foot,
 * labels that read the right way up, why a point is refused, and what the add button does next.
 * Pure, so each rule runs in a JVM test.
 */

/** A point on a recognised wall's foot: [position], on the wall [a]–[b] at the point's level. */
data class MeasureWallFoot(val position: Position, val a: Position, val b: Position)

/**
 * The recognised wall whose foot [point] stands on, within [tolerance] of it in plan and within
 * [WALL_FOOT_LEVEL_M] of the walls' floor [floorY], or `null`. A point the same distance from the
 * wall but on a table or halfway up the wall is not on its foot. Display only: the reticle draws the seam and says "Wall foot", while the point itself stays
 * where ARCore measured and the snapper put it — the live walls are squared and extended, so
 * they never move a point (#4750 review). The nearest wall wins.
 */
fun measureWallFootAt(
    point: Position,
    walls: List<LiveWall>,
    floorY: Float,
    tolerance: Float = WALL_FOOT_NEAR_M,
): MeasureWallFoot? {
    if (abs(point.y - floorY) > WALL_FOOT_LEVEL_M) return null
    var best: MeasureWallFoot? = null
    var bestDistance = tolerance
    for (wall in walls) {
        val ex = wall.bx - wall.ax
        val ez = wall.bz - wall.az
        val length = sqrt(ex * ex + ez * ez)
        if (length < WALL_MIN_LENGTH_M) continue
        val along = ((point.x - wall.ax) * ex + (point.z - wall.az) * ez) / length
        if (along < -SPAN_SLACK_M || along > length + SPAN_SLACK_M) continue
        val distance = abs((point.x - wall.ax) * ez - (point.z - wall.az) * ex) / length
        if (distance > bestDistance) continue
        bestDistance = distance
        best = MeasureWallFoot(
            position = point,
            a = Position(wall.ax, point.y, wall.az),
            b = Position(wall.bx, point.y, wall.bz),
        )
    }
    return best
}

/**
 * The wall height shown while raising, from this frame's [read] and the [shown] one: `null` as
 * soon as the aim leaves the wall — no stale height to lock (#4751) — and [shown] kept while the
 * read stays within [epsilon] of it, so the walls don't jitter.
 */
fun measureRaisingHeight(read: Float?, shown: Float?, epsilon: Float): Float? = when {
    read == null -> null
    shown != null && abs(read - shown) <= epsilon -> shown
    else -> read
}

/**
 * The orientation of a length label at [label] seen from [eye]: its front (local `+Z`) toward the
 * eye and its top along the screen's up ([cameraUp], the camera's world `+Y`), so the text reads
 * the right way up whatever the angle — looking straight down on a floor segment included, where
 * a world-up billboard flips (#4732). `null` with no direction to face.
 */
fun measureLabelQuaternion(label: Position, eye: Position, cameraUp: Float3): Quaternion? {
    val tx = label.x - eye.x
    val ty = label.y - eye.y
    val tz = label.z - eye.z
    val lengthSq = tx * tx + ty * ty + tz * tz
    if (!(lengthSq > MIN_FACING_SQ)) return null
    val inv = 1f / sqrt(lengthSq)
    val fx = tx * inv
    val fy = ty * inv
    val fz = tz * inv
    // The screen's up, with whatever of it runs along the line of sight taken out.
    val along = cameraUp.x * fx + cameraUp.y * fy + cameraUp.z * fz
    val ux = cameraUp.x - fx * along
    val uy = cameraUp.y - fy * along
    val uz = cameraUp.z - fz * along
    val upLength = sqrt(ux * ux + uy * uy + uz * uz)
    if (!(upLength > MIN_UP)) return null
    return lookTowards(
        eye = label,
        forward = Float3(tx, ty, tz),
        up = Float3(ux / upLength, uy / upLength, uz / upLength),
    ).toQuaternion()
}

/** Why a tap on the add button, or on Plan, would do nothing right now — said on screen instead of a silent refusal. */
enum class MeasureRefusal {
    /** The camera lost its place: no point can be anchored. */
    NotTracking,

    /** Nothing under the reticle: no surface, depth or feature point. */
    NoSurface,

    /** Raising a wall, and the aim is off it: there is no height to read. */
    OffWall,

    /** ARCore would not anchor the point; a retry usually works. */
    AnchorFailed,

    /** Marking a door or a window, and the aim is off the wall it goes on. */
    OpeningOffWall,

    /** The opening's second corner is too close to its first: no door or window there. */
    OpeningTooSmall,

    /** Plan, on a raised room whose corners enclose no floor: there is no plan to draw. */
    NoPlan,

    /** A wall or room corner on the one just placed: it would add a side of length 0. */
    SameCorner,

    /** Plan, with no room traced yet: trace one, close it and set its height. */
    PlanNeedsRoom,

    /** Plan, on a room still open: close it and set its height. */
    PlanNeedsClose,

    /** Plan, on a closed room whose height is not set yet. */
    PlanNeedsHeight,
}

/** The [MeasureRefusal] for this frame, `null` when the add button would act. */
fun measureRefusal(tracking: Boolean, phase: MeasurePhase, aimFound: Boolean, heightFound: Boolean): MeasureRefusal? =
    when {
        !tracking -> MeasureRefusal.NotTracking
        phase == MeasurePhase.Raising -> if (heightFound) null else MeasureRefusal.OffWall
        !aimFound -> MeasureRefusal.NoSurface
        else -> null
    }

/** What the add button does next, which its caption says. */
enum class MeasureAction {
    /** The first point of the first measure. */
    Start,

    /** Another point on the measure in progress. */
    Continue,

    /** Back on the first point: closes the shape. */
    Close,

    /** Locks the rising walls. */
    SetHeight,

    /** The last measure is done: the next point starts another one beside it. */
    StartNew,
}

/** The add button's next action on [board], with the reticle on the first point when [closes]. */
fun <P> measureAction(board: MeasureBoard<P>, closes: Boolean): MeasureAction = when {
    board.phase == MeasurePhase.Raising -> MeasureAction.SetHeight
    board.active != null && closes -> MeasureAction.Close
    board.active != null -> MeasureAction.Continue
    board.figures.any { it.mode == board.mode && it.points.isNotEmpty() } -> MeasureAction.StartNew
    else -> MeasureAction.Start
}

/**
 * The shortest side a wall or a room takes, metres: a corner closer than this to the one just
 * placed is the same corner tapped twice, not a new side. Of the order of the snapper's own
 * margins, and well under any wall worth measuring.
 */
const val MEASURE_MIN_SIDE_M = 0.05f

/**
 * Why a point at [at] would not be added to [board], `null` when it would, with [where] each
 * anchored point's position (`null` when not known yet): [MeasureRefusal.SameCorner] when it
 * stands within [MEASURE_MIN_SIDE_M] of the last corner of the wall or room being traced, on the
 * floor (#4765). A Distance chain keeps its short segments; closing an outline reuses its first
 * point and never comes here.
 */
fun <P> measurePointRefusal(board: MeasureBoard<P>, at: Position, where: (P) -> Position?): MeasureRefusal? {
    val figure = board.active?.takeIf { it.mode != MeasureMode.Distance && !it.raising } ?: return null
    val last = figure.points.lastOrNull()?.let(where) ?: return null
    // On the floor line: walls and rooms are levelled on their first point (measureFigureOutline),
    // so a corner caught a few centimetres up the skirting board is still the same corner.
    return if (hypot(at.x - last.x, at.z - last.z) < MEASURE_MIN_SIDE_M) MeasureRefusal.SameCorner else null
}

/** The add button's tap on a new point: the [board] after it, and why it did nothing, if it did nothing. */
data class MeasurePointTap<P>(val board: MeasureBoard<P>, val refusal: MeasureRefusal?)

/**
 * The add button's tap with the reticle at [at], not on the first point: [board] with a new point
 * from [anchor], or [board] as it is with the [MeasureRefusal] that says why. [anchor] runs only
 * once the point is accepted, so a refused point leaves no anchor behind; `null` from it is
 * [MeasureRefusal.AnchorFailed].
 */
fun <P> measureAddPoint(
    board: MeasureBoard<P>,
    at: Position,
    where: (P) -> Position?,
    anchor: () -> P?,
): MeasurePointTap<P> {
    measurePointRefusal(board, at, where)?.let { return MeasurePointTap(board, it) }
    val point = anchor() ?: return MeasurePointTap(board, MeasureRefusal.AnchorFailed)
    return MeasurePointTap(board.tap(point, closesOutline = false), null)
}

/**
 * The refusal the chip under the reticle says this frame, `null` for none: a refused tap's
 * ([tapped], for a moment), else aiming off the wall being read or marked ([live], all along) —
 * not while the coaching overlay already says what to do ([coaching]). [reading]: a wall height
 * reads this frame; [read]: one has read since the walls started rising.
 */
fun measureRefusalChip(
    tapped: MeasureRefusal?,
    live: MeasureRefusal?,
    coaching: Boolean,
    reading: Boolean,
    read: Boolean,
): MeasureRefusal? {
    // A refused Set height off the wall stops being true the moment the aim finds it again.
    val shownTap = tapped?.takeIf { !(it == MeasureRefusal.OffWall && reading) }
    // "Aim at the wall to read its height" teaches how heights read: once one has, it only
    // flickers each time the aim crosses a door or the ceiling line (#4765).
    val shownLive = live?.takeIf {
        !coaching && (it == MeasureRefusal.OpeningOffWall || (it == MeasureRefusal.OffWall && !read))
    }
    return shownTap ?: shownLive
}

/** A label on a figure's walls in AR: its [text], at [position]. */
data class MeasureWallLabel(val text: String, val position: Position)

/**
 * The labels on [figure]'s walls in AR along its [outline], the walls drawn [drawn] metres high
 * right now: the height halfway up the first corner and, on a wall, its area halfway up the wall
 * — net of the doors and windows marked on it. The words are the readout card's, from the same
 * [numbers] and the same [measureWallText] (#4765): the height read or set, not the height the
 * walls are drawn at while they rise to it. None before a height reads, as on the card.
 */
fun measureWallLabels(
    figure: MeasureFigure<*>,
    outline: List<Position>,
    numbers: MeasureFigureNumbers,
    drawn: Float,
    len: (Float) -> String,
    area: (Float) -> String,
): List<MeasureWallLabel> {
    if (outline.size < 2) return emptyList()
    val text = measureWallText(numbers, len, area)
    val height = text.height ?: return emptyList()
    val first = outline.first()
    val labels = ArrayList<MeasureWallLabel>(2)
    labels += MeasureWallLabel(height, Position(first.x, first.y + drawn / 2f, first.z))
    val wallArea = text.net ?: text.area
    if (figure.mode == MeasureMode.Wall && wallArea != null) {
        val mid = measureMidpoint(outline[0], outline[1])
        labels += MeasureWallLabel(wallArea, Position(mid.x, mid.y + drawn / 2f, mid.z))
    }
    return labels
}

/**
 * The walls' drawn height one frame of [dt] seconds after [drawn], on its way to [target]: the
 * height read or set, which moves every frame while the walls follow the aim. Eased toward the
 * latest target each frame, without overshoot, and exactly on it once within a millimetre, so a
 * target that keeps moving is still reached (#4765) — an animation restarted on each new target
 * never left its start.
 */
fun measureWallRise(drawn: Float, target: Float, dt: Float): Float {
    if (!(dt > 0f)) return drawn
    val next = target + (drawn - target) * exp(-dt / WALL_RISE_TIME_S)
    return if (abs(next - target) <= WALL_RISE_LANDED_M) target else next
}

/** The walls' rise time constant, seconds: two thirds of the way to the height in this long. */
private const val WALL_RISE_TIME_S = 0.08f

/** Within this of the height, metres, the walls are on it. */
private const val WALL_RISE_LANDED_M = 0.001f
private const val WALL_MIN_LENGTH_M = 0.05f
private const val SPAN_SLACK_M = 0.1f
private const val WALL_FOOT_NEAR_M = 0.1f

/**
 * How far above or below the walls' floor a point still stands on a wall's foot. The floor is one
 * plane's mean height and ARCore's floor planes are a few centimetres apart across a room, so
 * 10 cm absorbs that noise and a skirting board; the lowest furniture worth telling apart (a
 * low table, ~40 cm) or a point on the wall itself stays well outside it.
 */
private const val WALL_FOOT_LEVEL_M = 0.1f
private const val MIN_FACING_SQ = 1e-8f
private const val MIN_UP = 1e-3f
