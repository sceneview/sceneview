package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Whose the figures drawn on a room's result are. They are read off the scan's points, squared
 * to its walls ([RoomMeasure.ofPoints]): the span of what was filmed. They are the room's own
 * only when the walls the scan saw say the same: one seen at each end of its length and of its
 * width ([wallsOf]), within [AGREE_M] of the figures drawn.
 *
 * The first three rooms scanned by hand (2026-11-14) each held one wall. Their results read
 * 2.83 × 4.06 m and the like, with no word that it was the part filmed and not the room.
 */
enum class RoomFigures {
    /** The walls the scan saw close the same room: the figures are its own. */
    Room,

    /** No wall seen on every side, or walls that close another room: the figures span what was scanned. */
    Scanned;

    companion object {
        /**
         * The room [survey]'s walls close, `null` unless one was seen at each end of its length
         * and of its width: an end with no wall seen is where the scan stopped, not where the room does.
         */
        fun wallsOf(survey: RoomSurvey?): RoomMeasure? {
            val length = survey?.length ?: return null
            val width = survey.width ?: return null
            if (length.estimate || width.estimate) return null
            return ResultFloor.measureOf(survey)
        }

        /** Whose [shown] is, the figures a result draws; [walls] is the room its walls close ([wallsOf]). */
        fun of(shown: RoomMeasure, walls: RoomMeasure?): RoomFigures {
            walls ?: return Scanned
            // The same room whichever of its two sides each measure calls its width.
            val long = abs(max(shown.width, shown.depth) - max(walls.width, walls.depth))
            val short = abs(min(shown.width, shown.depth) - min(walls.width, walls.depth))
            return if (long <= AGREE_M && short <= AGREE_M) Room else Scanned
        }

        /**
         * How far the figures drawn may stand from the walls' and still be the room's: the points
         * of a wall lie a few centimetres either side of it, at both ends. Further apart, the
         * points hold more than the room — what was filmed through a doorway — or less.
         */
        const val AGREE_M = 0.3f
    }
}
