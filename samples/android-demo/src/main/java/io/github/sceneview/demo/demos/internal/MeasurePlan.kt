package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * A room traced in Measure, as the same plan 3D Scan draws and shares: a [RoomSurvey] made from
 * the floor outline, so `RerunPlanView` and its PDF need nothing of their own for it.
 */

/**
 * The plan of a room traced corner by corner: its floor [outline] (world space, levelled, in
 * the order it was traced, either way round) with the walls [height] metres high.
 *
 * In the plan's frame, as [RoomSurvey] wants it: the outline is turned so its walls square up
 * with the page (the turn that boxes it tightest, one of its own walls along x), its longer
 * side runs along x, y goes down the page, and the walls run clockwise from the corner nearest
 * the top-left — wall A along the top. Every wall was measured end to end, so every wall is
 * seen, and no figure is an estimate. A traced figure has no scatter: every `±` it carries is 0.
 * Nothing is rounded or converted here: [SurveyFormat] writes the figures in the plan's units,
 * and where it prints a `±`, prints a zero one as one rounding step (`± 0.05` on a length in
 * metres).
 *
 * `null` under three corners, or for an outline with no area.
 */
fun measureRoomSurvey(outline: List<Position>, height: Float?): RoomSurvey? {
    val page = planCorners(outline) ?: return null
    val corners = page.corners
    val walls = corners.indices.map { i ->
        val (ax, ay) = corners[i]
        val (bx, by) = corners[(i + 1) % corners.size]
        val dx = bx - ax
        val dy = by - ay
        SurveyWall(
            tag = planWallTag(i),
            ax = ax,
            ay = ay,
            bx = bx,
            by = by,
            length = measured(sqrt(dx * dx + dy * dy)),
            seenShare = 1f,
            seen = true,
            unseen = emptyList(),
        )
    }
    return RoomSurvey(
        walls = walls,
        planLength = page.planLength,
        planWidth = page.planWidth,
        length = measured(page.planLength),
        width = measured(page.planWidth),
        area = measured(abs(page.signedArea)),
        perimeter = measured(walls.sumOf { it.length.value.toDouble() }.toFloat()),
        ceilingHeight = height?.let(::measured),
        gaps = if (height == null) listOf(SurveyGap.Ceiling) else emptyList(),
        floorY = outline.first().y,
        floorThickness = 0f,
        yaw = page.yaw,
    )
}

/**
 * A door or a window of a traced room, as its plan ([measureRoomSurvey]) shows it: on the wall
 * the plan letters [wall], [opening] in that wall's frame — along it from its start as the plan
 * runs it, clockwise from wall A.
 */
data class MeasurePlanOpening(val wall: String, val opening: MeasureOpening)

/**
 * The [openings] of the room traced along [outline], each on the wall it was marked on
 * ([MeasureOpening]), on its plan: each on the plan's wall it stands on, in the plan's wall order,
 * then along each wall. Empty when the outline makes no plan.
 */
fun measurePlanOpenings(outline: List<Position>, openings: List<MeasureOpening>): List<MeasurePlanOpening> {
    val page = planCorners(outline) ?: return emptyList()
    val n = outline.size
    return openings.mapNotNull { opening ->
        val traced = measureOpeningWall(opening, outline, closed = true) ?: return@mapNotNull null
        val local = measureOpeningOnWall(opening, outline, closed = true) ?: return@mapNotNull null
        // The plan walks the room clockwise: traced the other way round, each wall runs backwards
        // there, from the corner it ended on.
        val index = page.order.indexOf(if (page.reversed) (traced + 1) % n else traced)
        val length = measureDistanceMeters(outline[traced], outline[(traced + 1) % n])
        val onPlan = if (page.reversed) local.copy(from = length - local.to, to = length - local.from) else local
        index to MeasurePlanOpening(planWallTag(index), onPlan.copy(wall = index))
    }
        .sortedWith(compareBy({ it.first }, { it.second.opening.from }))
        .map { it.second }
}

/** One door or window on the card under a traced room's plan: the plan's [wall] letter, its [width] × [height]. */
data class MeasurePlanCardOpening(val wall: String, val width: String, val height: String)

/** The card under a traced room's plan: the walls' [netWallArea], then each of its [openings]. */
data class MeasurePlanCard(val netWallArea: String, val openings: List<MeasurePlanCardOpening>)

/**
 * The card Plan shows under a traced room's plan when it has doors or windows: [netWallArea]
 * (m²) and each of [openings], written under [locale] in [units], the plan's own, so one page
 * never says feet on the card and metres on the plan. In metres every length is in metres with two
 * decimals, as the plan writes them, but to the centimetre, where the plan's own lengths go in 5 cm
 * steps ([SurveyFormat.LENGTH_STEP_M]): `0,93 m × 2,04 m`, never `93 cm × 2,04 m`.
 */
fun measurePlanCard(
    netWallArea: Float,
    openings: List<MeasurePlanOpening>,
    units: MeasureUnitSystem,
    locale: Locale,
): MeasurePlanCard = MeasurePlanCard(
    netWallArea = formatMeasureArea(netWallArea, units, locale),
    openings = openings.map {
        MeasurePlanCardOpening(
            wall = it.wall,
            width = planCardLength(it.opening.width, units, locale),
            height = planCardLength(it.opening.height, units, locale),
        )
    },
)

/** A length on the plan's card: in metres below one too, `0.90 m`; feet and inches as the Measure screen writes them. */
private fun planCardLength(metres: Float, units: MeasureUnitSystem, locale: Locale): String = when (units) {
    MeasureUnitSystem.Metric -> String.format(locale, "%.2f m", (metres.coerceAtLeast(0f) * CENTIMETRES_PER_METRE).roundToInt() / CENTIMETRES_PER_METRE)
    MeasureUnitSystem.Imperial -> formatMeasureLength(metres, units, locale)
}

/** The last room traced and raised among [figures], the one Plan draws: its index, `-1` with none. */
fun measurePlanRoom(figures: List<MeasureFigure<*>>): Int =
    figures.indexOfLast { it.mode == MeasureMode.Room && it.closed && it.height != null }

/** What a tap on Plan does. */
sealed interface MeasurePlanTap {
    /** Opens [survey], the plan of the figure at [room] on the board. */
    data class Open(val room: Int, val survey: RoomSurvey) : MeasurePlanTap

    /** Opens nothing, for [reason]: said under the reticle, as any refused tap. */
    data class Refused(val reason: MeasureRefusal) : MeasurePlanTap
}

/**
 * What a tap on Plan does with the board's [figures], each along its outline in [outlines]: opens
 * the plan ([measureRoomSurvey]) of the last room traced and raised ([measurePlanRoom]). With no
 * such room Plan is greyed out, and a tap on it says the step still missing (#4765): trace a room
 * ([MeasureRefusal.PlanNeedsRoom]), close it ([MeasureRefusal.PlanNeedsClose]) or set its height
 * ([MeasureRefusal.PlanNeedsHeight]), read on the last room traced. When that room has no plan to
 * draw (corners in a line, no floor between them), the tap is refused with [MeasureRefusal.NoPlan]
 * and says so, rather than open an older room or do nothing.
 */
fun measurePlanTap(figures: List<MeasureFigure<*>>, outlines: List<List<Position>>): MeasurePlanTap {
    val room = measurePlanRoom(figures)
    if (room < 0) {
        val traced = figures.lastOrNull { it.mode == MeasureMode.Room && it.points.isNotEmpty() }
        val missing = when {
            traced == null -> MeasureRefusal.PlanNeedsRoom
            !traced.closed -> MeasureRefusal.PlanNeedsClose
            else -> MeasureRefusal.PlanNeedsHeight
        }
        return MeasurePlanTap.Refused(missing)
    }
    val survey = measureRoomSurvey(outlines.getOrNull(room).orEmpty(), figures[room].height)
        ?: return MeasurePlanTap.Refused(MeasureRefusal.NoPlan)
    return MeasurePlanTap.Open(room, survey)
}

/**
 * A traced outline on the plan's page: its [corners] clockwise from the one nearest the top-left,
 * each the outline's corner [order] names; [reversed] when the outline was traced the other way.
 */
private class PlanCorners(
    val corners: List<Pair<Float, Float>>,
    val order: List<Int>,
    val reversed: Boolean,
    val signedArea: Float,
    val yaw: Float,
    val planLength: Float,
    val planWidth: Float,
)

/** `null` under three corners, or for an outline with no area. */
private fun planCorners(outline: List<Position>): PlanCorners? {
    if (outline.size < MeasureBoard.MIN_OUTLINE_POINTS) return null
    val turn = squaringTurn(outline)
    val c = cos(turn)
    val s = sin(turn)
    // Seen from above, world x to the right puts world z down the page.
    var xs = outline.map { it.x * c + it.z * s }
    var ys = outline.map { -it.x * s + it.z * c }
    var yaw = turn
    if (ys.max() - ys.min() > xs.max() - xs.min()) {
        // A quarter turn more: the longer side runs along x.
        val turnedX = ys.map { -it }
        ys = xs
        xs = turnedX
        yaw += HALF_PI
    }
    val minX = xs.min()
    val minY = ys.min()
    var corners = xs.indices.map { (xs[it] - minX) to (ys[it] - minY) }
    var order = outline.indices.toList()
    val signed = signedArea(corners)
    if (abs(signed) < MIN_AREA_M2) return null
    // Positive on a page whose y goes down is clockwise: the room on the right of the walk.
    val reversed = signed < 0f
    if (reversed) {
        corners = corners.reversed()
        order = order.reversed()
    }
    val first = corners.indices.minBy { corners[it].first + corners[it].second }
    corners = corners.drop(first) + corners.take(first)
    order = order.drop(first) + order.take(first)
    return PlanCorners(
        corners = corners,
        order = order,
        reversed = reversed,
        signedArea = signed,
        yaw = yaw,
        planLength = xs.max() - minX,
        planWidth = ys.max() - minY,
    )
}

private fun planWallTag(index: Int): String = ('A' + index).toString()

private fun measured(value: Float) = SurveyFigure(value, plusMinus = 0f, estimate = false)

/**
 * The turn about +Y that boxes the [outline] tightest, among the turns that lay one of its own
 * walls along x — the longest such wall on a tie. A rectangle squares up exactly, an L as
 * well, and a room with one leaning wall keeps its other walls square to the page.
 */
private fun squaringTurn(outline: List<Position>): Float {
    var best = 0f
    var bestArea = Float.MAX_VALUE
    var bestLength = 0f
    for (i in outline.indices) {
        val a = outline[i]
        val b = outline[(i + 1) % outline.size]
        val dx = b.x - a.x
        val dz = b.z - a.z
        val length = sqrt(dx * dx + dz * dz)
        if (length < MIN_WALL_M) continue
        val turn = atan2(dz, dx)
        val c = cos(turn)
        val s = sin(turn)
        val xs = outline.map { it.x * c + it.z * s }
        val ys = outline.map { -it.x * s + it.z * c }
        val area = (xs.max() - xs.min()) * (ys.max() - ys.min())
        val tighter = area < bestArea - AREA_TIE_M2
        val tie = !tighter && area <= bestArea + AREA_TIE_M2 && length > bestLength
        if (tighter || tie) {
            best = turn
            bestArea = area
            bestLength = length
        }
    }
    return best
}

/** Shoelace area on the page: positive when the corners run clockwise there (y down). */
private fun signedArea(corners: List<Pair<Float, Float>>): Float {
    var sum = 0f
    for (i in corners.indices) {
        val (ax, ay) = corners[i]
        val (bx, by) = corners[(i + 1) % corners.size]
        sum += ax * by - bx * ay
    }
    return sum / 2f
}

private const val HALF_PI = (Math.PI / 2).toFloat()
private const val MIN_WALL_M = 0.05f
private const val CENTIMETRES_PER_METRE = 100f
private const val MIN_AREA_M2 = 0.01f
private const val AREA_TIE_M2 = 1e-3f
