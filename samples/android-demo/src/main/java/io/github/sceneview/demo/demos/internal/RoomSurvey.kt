package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.room.RoomFigure
import io.github.sceneview.demo.demos.internal.room.RoomGap
import io.github.sceneview.demo.demos.internal.room.RoomLevels
import io.github.sceneview.demo.demos.internal.room.RoomPlane
import io.github.sceneview.demo.demos.internal.room.RoomPlaneKind
import io.github.sceneview.demo.demos.internal.room.RoomReader
import io.github.sceneview.demo.demos.internal.room.RoomScan
import io.github.sceneview.demo.demos.internal.room.RoomStructure
import io.github.sceneview.demo.demos.internal.room.WallLines
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * A room's survey, read from a scan's dense cloud (#4540): where its walls stand, how long each
 * is, the floor's area and the ceiling's height — the [RoomStructure] engine's reading, laid on
 * the page for the plan, the report and the exports.
 *
 * The walls are measured on the cloud's vertical surfels, never on the extent of the cloud or of
 * ARCore's planes (#4328); the outline is any polygon the walls draw, oblique walls included, and
 * a wall the scan never saw is deduced from its neighbours, drawn dashed and its length an
 * estimate. A measured figure carries the scatter of the surfels it was read from — a scatter, not
 * an accuracy — and an estimate carries none. Same cloud, same figures.
 */

/** A scan as the survey reads it: the dense cloud's surfels ([RoomScan.normals] per point) and the camera's path. */
typealias RoomSurveyInput = RoomScan

/**
 * One figure: its value, the scatter of the surfels it was read from (`plusMinus` — not an
 * accuracy), and whether part of it was not seen. An estimate has no scatter.
 */
typealias SurveyFigure = RoomFigure

/**
 * One wall of the outline, from ([ax], [ay]) to ([bx], [by]) in the plan's own frame: metres, x
 * to the right along the room's longer side, y down the page, the room kept on the right of the
 * walk (clockwise on the page).
 *
 * @property seenShare the part of its length the scan saw a wall on, 0..1.
 * @property seen `false` under [RoomSurveyor.MIN_SEEN_SHARE]: drawn dashed, its length an estimate.
 * @property unseen the stretches the scan saw no wall on, in metres from the wall's start.
 * @property sized `false` when its length would be a guess on a plan its walls do not close: no
 *   length is given for it.
 */
class SurveyWall(
    val tag: String,
    val ax: Float,
    val ay: Float,
    val bx: Float,
    val by: Float,
    val length: SurveyFigure,
    val seenShare: Float,
    val seen: Boolean,
    val unseen: List<ClosedFloatingPointRange<Float>>,
    val sized: Boolean = true,
) {
    val horizontal: Boolean get() = abs(by - ay) < abs(bx - ax)
}

/** What a scan lacks for a full survey, each a thing its owner can go and scan. */
enum class SurveyGap {
    /** No dense cloud with normals: a scan made without depth. */
    NoDepth,

    /** Not one wall was seen high enough to be told from furniture. */
    NoWalls,

    /** A wall seen was not followed to its end: no area until it is. */
    WallEnd,

    /** A wall was not seen: it is deduced from its neighbours and the figures that hang on it are estimates. */
    MissingWall,

    /** A wall stands square to no other. No longer reported: the plan draws oblique walls as they are. */
    ObliqueWall,

    /** Too little ceiling seen for a height. */
    Ceiling,

    /** The measure failed or did not end in time: nothing to scan again, the plan can be reopened. */
    Unmeasured,
}

/**
 * The survey of one scan. [walls] is the outline, clockwise from its top-left corner — empty
 * when no wall was seen. [area], [perimeter], [length] and [width] are `null` when the walls
 * that bound them were not seen ([gaps] says what is missing); [ceilingHeight] is `null` when
 * the ceiling was not.
 */
class RoomSurvey(
    val walls: List<SurveyWall>,
    val planLength: Float,
    val planWidth: Float,
    val length: SurveyFigure?,
    val width: SurveyFigure?,
    val area: SurveyFigure?,
    val perimeter: SurveyFigure?,
    val ceilingHeight: SurveyFigure?,
    val gaps: List<SurveyGap>,
    /** The floor's height in the scan's frame, and how thick its layer of surfels is. */
    val floorY: Float,
    val floorThickness: Float,
    /** The room's turn about +Y from the scan's axes: the plan is drawn squared to the walls. */
    val yaw: Float,
    /** The engine's full reading — openings, ceiling, mesh — when the survey came from one. */
    val structure: RoomStructure? = null,
) {
    val hasPlan: Boolean get() = walls.isNotEmpty()

    /** `true` when any figure or wall is an estimate: the exports then explain the mark. */
    val hasEstimates: Boolean
        get() = walls.any { it.sized && it.length.estimate } || area?.estimate == true || length?.estimate == true ||
            width?.estimate == true || perimeter?.estimate == true || ceilingHeight?.estimate == true

    /** `true` when any figure is measured, and so carries a ±: the exports then say what the ± is. */
    val hasSpread: Boolean
        get() = (walls.filter { it.sized }.map { it.length } + listOfNotNull(area, length, width, perimeter, ceilingHeight))
            .any { !it.estimate }

    companion object {
        fun empty(gap: SurveyGap) = RoomSurvey(
            emptyList(), 0f, 0f, null, null, null, null, null,
            // A room that was not measured lacks nothing it could be scanned for.
            listOfNotNull(gap, SurveyGap.Ceiling.takeIf { gap != SurveyGap.Unmeasured }), 0f, 0f, 0f,
        )
    }
}

/**
 * Reads a [RoomSurvey] from a scan: the [RoomStructure] engine's reading, laid on the page. Pure
 * Kotlin; a few hundred thousand surfels take well under a second.
 */
object RoomSurveyor {
    /** The grid the cloud is read on. */
    const val CELL_M = RoomReader.CELL_M

    /** A wall seen on less than this share of its length is drawn dashed and its length is an estimate. */
    const val MIN_SEEN_SHARE = RoomStructure.MIN_SEEN_SHARE

    /** A vertical face has to reach this high above the floor to be a wall and not furniture. */
    const val WALL_MIN_TOP_M = WallLines.WALL_MIN_TOP_M

    /** A wall is a line seen on at least this much of its length. */
    const val MIN_WALL_SEEN_M = WallLines.MIN_WALL_SEEN_M

    /** Layers of one wall are one wall within this: a scan that drifted draws a wall twice. */
    const val WALL_MERGE_M = WallLines.WALL_MERGE_M

    /** A ceiling is a height only once this much of it was seen. */
    const val MIN_CEILING_AREA_M2 = RoomLevels.MIN_CEILING_AREA_M2

    /** The lowest a ceiling is looked for above the floor. */
    const val MIN_CEILING_M = RoomLevels.MIN_CEILING_M

    /** The lowest horizontal layer holding this share of the densest one's surfels is the floor. */
    const val FLOOR_RIVAL_SHARE = RoomLevels.FLOOR_RIVAL_SHARE

    /** A vertical face is furniture when the same wall is seen this much higher behind it. */
    const val FURNITURE_UNDER_M = WallLines.FURNITURE_UNDER_M

    /** Ceiling seen this far from the height that is given, over [MIN_CEILING_AREA_M2], is a second level. */
    const val CEILING_LEVEL_M = RoomLevels.CEILING_LEVEL_M

    /** No figure is given tighter than this, however thin the layer read. */
    const val MIN_SPREAD_M = RoomLevels.MIN_SPREAD_M

    private const val SAME_ROW_M = 0.01f
    private const val LETTERS = 26

    /**
     * A wall cell is seen over this much of its height at least, or stands over furniture before
     * it: [WallRules.MIN_SPAN_M] too. The structure engine reads walls as lines, not cells; the live
     * walls keep the grid's threshold.
     */
    internal const val WALL_CELL_SPAN_M = 0.4f

    /**
     * A wall square to no other needs this much seen before the live walls ([WallRules]) show it.
     * The structure engine draws oblique walls as they are, without it.
     */
    const val OBLIQUE_MIN_M = 1f

    /** The survey of a scan: [RoomStructure.read], on the page. */
    fun survey(input: RoomSurveyInput): RoomSurvey = of(RoomStructure.read(input))

    /**
     * [structure] laid on the page: the room's longer side along x, y down the page, the outline
     * walked clockwise from its top-left corner. The figures are the structure's.
     */
    @Suppress("LongMethod")
    fun of(structure: RoomStructure): RoomSurvey {
        val gaps = structure.gaps.map { gapOf(it) }
        val n = structure.walls.size
        if (n == 0) {
            return RoomSurvey(
                emptyList(), 0f, 0f, null, null, null, null, structure.ceilingHeight, gaps,
                structure.floorY, structure.floorThickness, structure.yaw, structure,
            )
        }
        val c = cos(structure.yaw)
        val s = sin(structure.yaw)
        // The room's own frame, squared to its walls.
        val cu = FloatArray(n) { c * structure.walls[it].ax + s * structure.walls[it].az }
        val cv = FloatArray(n) { -s * structure.walls[it].ax + c * structure.walls[it].az }
        val minU = cu.min()
        val minV = cv.min()
        val extentU = cu.max() - minU
        val extentV = cv.max() - minV
        val turned = extentV > extentU
        // Counter-clockwise in the room's frame is clockwise on the page, its y pointing down.
        val px = FloatArray(n) { if (turned) extentV - (cv[it] - minV) else cu[it] - minU }
        val py = FloatArray(n) { if (turned) cu[it] - minU else cv[it] - minV }
        val top = py.min()
        val first = (0 until n).filter { py[it] - top <= SAME_ROW_M }.minBy { px[it] }
        val walls = (0 until n).map { k ->
            val i = (first + k) % n
            val next = (i + 1) % n
            val wall = structure.walls[i]
            SurveyWall(
                tag = tagOf(k),
                ax = px[i],
                ay = py[i],
                bx = px[next],
                by = py[next],
                length = wall.length,
                seenShare = wall.seenShare,
                seen = wall.seen,
                unseen = wall.unseen,
                // An estimated length is a figure too: none on a plan its walls do not close.
                sized = !wall.length.estimate || structure.area != null,
            )
        }
        return RoomSurvey(
            walls = walls,
            planLength = max(extentU, extentV),
            planWidth = min(extentU, extentV),
            length = structure.length,
            width = structure.width,
            area = structure.area,
            perimeter = structure.perimeter,
            ceilingHeight = structure.ceilingHeight,
            gaps = gaps,
            floorY = structure.floorY,
            floorThickness = structure.floorThickness,
            yaw = structure.yaw,
            structure = structure,
        )
    }

    private fun gapOf(gap: RoomGap): SurveyGap = when (gap) {
        RoomGap.NoDepth -> SurveyGap.NoDepth
        RoomGap.NoWalls -> SurveyGap.NoWalls
        RoomGap.WallEnd -> SurveyGap.WallEnd
        RoomGap.MissingWall -> SurveyGap.MissingWall
        RoomGap.Ceiling -> SurveyGap.Ceiling
    }

    /** `A`…`Z`, then `AA`…: the name a wall has on the plan and in the table. */
    internal fun tagOf(index: Int): String {
        val letter = ('A' + index % LETTERS).toString()
        return if (index < LETTERS) letter else ('A' + index / LETTERS - 1).toString() + letter
    }
}

/**
 * ARCore's [planes] as the room engine reads them: what a scan's frame hands
 * [RoomStructure.live] next to its dense cloud, so the engine itself needs nothing of the demo.
 */
internal fun roomPlanesOf(planes: List<DebugPlane>): List<RoomPlane> = planes.map { plane ->
    val kind = when (plane.kind) {
        DebugPlaneKind.Floor -> RoomPlaneKind.Floor
        DebugPlaneKind.Ceiling -> RoomPlaneKind.Ceiling
        DebugPlaneKind.Wall -> RoomPlaneKind.Wall
        DebugPlaneKind.Unknown -> RoomPlaneKind.Other
    }
    RoomPlane(kind, plane.polygon)
}
