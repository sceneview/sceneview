package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position

/*
 * A floor plan's areas and volumes (#4540): each room's floor area, its volume under the ceiling,
 * and the plan's totals.
 *
 * The floor area is the outline's, read with the same shoelace as the Measure demo
 * (`measureFloorArea`), so a room drawn by hand and a room read from a scan give one figure for
 * one outline. A volume is the floor area times the ceiling height: no volume without both. A
 * total is given only when every room has its figure: a plan with one unmeasured room has no
 * total, rather than a total that leaves that room out.
 *
 * The marks follow the figures they are made of: a volume or a total made from one estimate is
 * an estimate, and carries no "±"; a measured one carries the sum of its parts' scatters, first
 * order — a scatter, not an accuracy.
 */

/** One room's figures: its floor [area] and its [volume], each `null` when it was not measured. */
class RoomAreas(val name: String, val area: SurveyFigure?, val volume: SurveyFigure?)

/** The plan's [rooms], then its totals — `null` when one room lacks that figure. */
class PlanAreas(val rooms: List<RoomAreas>, val area: SurveyFigure?, val volume: SurveyFigure?) {
    companion object {
        /** Under this an outline encloses no floor: three points on a line, or two points. */
        const val MIN_AREA_M2 = 1e-4f

        /**
         * The floor area enclosed by [outline] seen from above (x/z), in square metres; `null`
         * when it encloses none — fewer than three corners, or every corner on one line.
         */
        fun floorArea(outline: List<Position>): Float? =
            measureFloorArea(outline).takeIf { outline.size >= 3 && it >= MIN_AREA_M2 }

        /** The floor area enclosed by a survey's [walls], drawn on the plan's own x/y. */
        fun wallsArea(walls: List<SurveyWall>): Float? =
            floorArea(walls.map { Position(it.ax, 0f, it.ay) })

        /** [area] × [height]; `null` without both. An estimate when either is. */
        fun volume(area: SurveyFigure?, height: SurveyFigure?): SurveyFigure? {
            if (area == null || height == null) return null
            return SurveyFigure.of(
                value = area.value * height.value,
                spread = area.value * height.plusMinus + height.value * area.plusMinus,
                estimate = area.estimate || height.estimate,
            )
        }

        /** One room of a survey: its area, and its volume under the ceiling the scan saw. */
        fun room(name: String, survey: RoomSurvey): RoomAreas =
            RoomAreas(name, survey.area, volume(survey.area, survey.ceilingHeight))

        /** The plan of [rooms], with its totals. */
        fun of(rooms: List<RoomAreas>): PlanAreas =
            PlanAreas(rooms, total(rooms.map { it.area }), total(rooms.map { it.volume }))

        /** The sum of [figures]; `null` when there are none or one is missing. */
        fun total(figures: List<SurveyFigure?>): SurveyFigure? {
            if (figures.isEmpty() || figures.any { it == null }) return null
            val known = figures.filterNotNull()
            return SurveyFigure.of(
                value = known.sumOf { it.value.toDouble() }.toFloat(),
                spread = known.sumOf { it.plusMinus.toDouble() }.toFloat(),
                estimate = known.any { it.estimate },
            )
        }
    }
}
