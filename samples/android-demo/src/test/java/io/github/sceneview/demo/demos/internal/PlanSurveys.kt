package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RoomPlanLayoutTest.Companion.WORDING
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A room over the outline [corners] (x, y pairs in metres, clockwise on the page, y down), every
 * wall seen. With [spread] 0 it is a room traced corner by corner: each figure measured end to
 * end, none with a scatter. With a [spread] it reads like a scan, each figure carrying it.
 */
internal fun planSurvey(vararg corners: Float, spread: Float = 0f, height: Float? = 2.5f): RoomSurvey {
    val n = corners.size / 2
    val walls = List(n) { i ->
        val j = (i + 1) % n
        val ax = corners[i * 2]
        val ay = corners[i * 2 + 1]
        val bx = corners[j * 2]
        val by = corners[j * 2 + 1]
        SurveyWall(
            tag = ('A' + i).toString(),
            ax = ax,
            ay = ay,
            bx = bx,
            by = by,
            length = SurveyFigure(hypot(bx - ax, by - ay), spread),
            seenShare = 1f,
            seen = true,
            unseen = emptyList(),
        )
    }
    var twice = 0f
    for (i in 0 until n) {
        val j = (i + 1) % n
        twice += corners[i * 2] * corners[j * 2 + 1] - corners[j * 2] * corners[i * 2 + 1]
    }
    val length = (0 until n).maxOf { corners[it * 2] }
    val width = (0 until n).maxOf { corners[it * 2 + 1] }
    return RoomSurvey(
        walls = walls,
        planLength = length,
        planWidth = width,
        length = SurveyFigure(length, spread),
        width = SurveyFigure(width, spread),
        area = SurveyFigure(abs(twice) / 2, spread),
        perimeter = SurveyFigure(walls.sumOf { it.length.value.toDouble() }.toFloat(), spread),
        ceilingHeight = height?.let { SurveyFigure(it, spread) },
        gaps = if (height == null) listOf(SurveyGap.Ceiling) else emptyList(),
        floorY = 0f,
        floorThickness = 0f,
        yaw = 0f,
    )
}

/** [WORDING] with its figures in feet and inches, its scale bar read through [scaleBar]. */
internal fun imperialWording(scaleBar: String = "%1\$s ft") = SurveyWording(
    heading = WORDING.heading,
    area = WORDING.area,
    dimensions = WORDING.dimensions,
    perimeter = WORDING.perimeter,
    height = WORDING.height,
    volume = WORDING.volume,
    rooms = WORDING.rooms,
    room = WORDING.room,
    total = WORDING.total,
    span = WORDING.span,
    walls = WORDING.walls,
    wall = WORDING.wall,
    notSeen = WORDING.notSeen,
    notMeasured = WORDING.notMeasured,
    estimated = WORDING.estimated,
    estimateNote = WORDING.estimateNote,
    spreadNote = WORDING.spreadNote,
    notice = WORDING.notice,
    madeWith = WORDING.madeWith,
    scaleBar = scaleBar,
    gaps = WORDING.gaps,
    units = MeasureUnitSystem.Imperial,
)
