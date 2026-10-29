package io.github.sceneview.demo.demos.internal

import java.util.Locale
import kotlin.math.max

/*
 * A room survey in words (#4540): the rows of its table, on the screen and on the PDF, and the
 * few plain lines that are shared as text. What was not measured is said, never filled in.
 *
 * The three outputs read the same rows and the same notes from here, so a figure carries the
 * same marks wherever it is read: "≈" in front of an estimate, which has no "±"; "±" behind a
 * figure read with a scatter, with the note that says it is the scan's scatter and not an
 * accuracy. A figure measured end to end, as in a room traced corner by corner, has neither.
 */

/**
 * What the export sheet offers for a room's floor plan (#4714): the plan once the survey has one,
 * and otherwise why not — never a PDF that says nothing was measured.
 */
enum class PlanOffer {
    /** The survey is still being read: the row waits, disabled. */
    Measuring,

    /** The survey has taken longer than usual and is still being read: the row says so, and waits. */
    StillMeasuring,

    /** The survey drew a plan: the row opens it. */
    Ready,

    /** The scan has no depth to measure: no plan, whatever is scanned again on this phone. */
    NoDepth,

    /** The scan has depth but too few walls for a plan. */
    NoWalls,

    /** The measure failed ([SurveyGap.Unmeasured]): the row says so. */
    Failed,
    ;

    companion object {
        /** The offer for [survey], `null` while it is being read. */
        fun of(survey: RoomSurvey?): PlanOffer = when {
            survey == null -> Measuring
            survey.hasPlan -> Ready
            SurveyGap.Unmeasured in survey.gaps -> Failed
            SurveyGap.NoDepth in survey.gaps -> NoDepth
            else -> NoWalls
        }

        /** The offer for [measure]: still measuring once it is overdue, else as its survey's. */
        fun of(measure: PlanMeasure): PlanOffer =
            if (measure.survey == null && measure.overdue) StillMeasuring else of(measure.survey)
    }
}

/** A room's survey as the plan waits for it: `null` while measured, [overdue] past its usual time. */
data class PlanMeasure(val survey: RoomSurvey?, val overdue: Boolean = false)

/** The survey's words, read from the app's resources: this file holds none of its own. */
class SurveyWording(
    /** `%1$s` the scan's title, `%2$s` its date. */
    val heading: String,
    val area: String,
    val dimensions: String,
    val perimeter: String,
    val height: String,
    val volume: String,
    /** The rooms' table: one row per room, then the plan's total. */
    val rooms: String,
    /** `%1$d` the room's number on the plan. */
    val room: String,
    val total: String,
    /** The one dimension a scan with a single pair of facing walls gives. */
    val span: String,
    val walls: String,
    /** `%1$s` the wall's letter. */
    val wall: String,
    val notSeen: String,
    val notMeasured: String,
    val estimated: String,
    /** What "≈" and a dashed wall mean. */
    val estimateNote: String,
    /** What "±" is: the scatter of the scan, not an accuracy. */
    val spreadNote: String,
    val notice: String,
    val madeWith: String,
    /** `%1$s` the bar's length, a round number of [units]: metres, or feet. */
    val scaleBar: String,
    /** What to scan for each thing a survey can lack. */
    val gaps: Map<SurveyGap, String>,
    /** The units its figures are written in: [SurveyFormat.units] of the phone's language. */
    val units: MeasureUnitSystem = MeasureUnitSystem.Metric,
    /**
     * The language whose decimal mark its figures take: `4.00` in English, `4,00` in French.
     * English when none is given; the Plan view, 3D Scan's and Measure's alike, passes the phone's.
     */
    val locale: Locale = Locale.US,
)

/** One line of the survey's table. [muted] ones say a figure is missing. */
class SurveyRow(val label: String, val value: String, val muted: Boolean = false)

object SurveyReport {
    private const val SEPARATOR = " · "
    private const val METRES = " m"
    private const val SQUARE_METRES = " m²"
    private const val CUBIC_METRES = " m³"
    private const val INCHES = " in"
    private const val SQUARE_FEET = " ft²"
    /**
     * Between the rooms, and between the walls, of the shared text: a comma there reads as one
     * more decimal where the comma is the decimal mark, `A 4,00 m, B 3,00 m`.
     */
    private const val LIST_SEPARATOR = "; "
    private const val PLUS_MINUS = " ± "

    /** A half-metre scale bar's length: `0.5`, `0,5` in French. */
    private const val HALF_METRE = "%.1f"

    /**
     * `20.0 m² ± 0.3` when scanned, `20.0 m²` when traced, `≈ 20.0 m²` when an estimate; in
     * [units] of feet, `215 ft² ± 4`.
     */
    fun area(figure: SurveyFigure, units: MeasureUnitSystem = MeasureUnitSystem.Metric, locale: Locale = Locale.US): String = when {
        figure.estimate -> SurveyFormat.ESTIMATE + areaFigure(figure.value, units, locale)
        else -> areaFigure(figure.value, units, locale) + areaSpread(figure.plusMinus, units, locale)
    }

    /**
     * `37.4 m³ ± 1.2` when scanned, `37.4 m³` when traced, `≈ 37.4 m³` when an estimate: to a
     * tenth of a cubic metre, the area's own step — a finer one would claim more than the floor
     * it stands on. In [units] of feet, to the cubic foot: `1321 ft³ ± 43`.
     */
    fun volume(figure: SurveyFigure, units: MeasureUnitSystem = MeasureUnitSystem.Metric, locale: Locale = Locale.US): String = when {
        figure.estimate -> SurveyFormat.ESTIMATE + volumeFigure(figure.value, units, locale)
        else -> volumeFigure(figure.value, units, locale) + volumeSpread(figure.plusMinus, units, locale)
    }

    /**
     * `5.00 m ± 0.05` when scanned, `5.00 m` when traced, `≈ 5.00 m` when an estimate; in [units]
     * of feet, to the inch: `16 ft 5 in ± 2 in`.
     */
    fun length(figure: SurveyFigure, units: MeasureUnitSystem = MeasureUnitSystem.Metric, locale: Locale = Locale.US): String = when {
        figure.estimate -> SurveyFormat.ESTIMATE + lengthFigure(figure.value, units, locale)
        else -> lengthFigure(figure.value, units, locale) + lengthSpread(figure.plusMinus, units, locale)
    }

    private fun lengthFigure(metres: Float, units: MeasureUnitSystem, locale: Locale): String = when (units) {
        MeasureUnitSystem.Metric -> SurveyFormat.length(metres, locale) + METRES
        MeasureUnitSystem.Imperial -> SurveyFormat.feetAndInches(metres)
    }

    private fun areaFigure(squareMetres: Float, units: MeasureUnitSystem, locale: Locale): String = when (units) {
        MeasureUnitSystem.Metric -> SurveyFormat.area(squareMetres, locale) + SQUARE_METRES
        MeasureUnitSystem.Imperial -> SurveyFormat.squareFeet(squareMetres, locale)
    }

    private fun volumeFigure(cubicMetres: Float, units: MeasureUnitSystem, locale: Locale): String = when (units) {
        MeasureUnitSystem.Metric -> SurveyFormat.area(cubicMetres, locale) + CUBIC_METRES
        MeasureUnitSystem.Imperial -> SurveyFormat.cubicFeet(cubicMetres)
    }

    /**
     * ` ± 0.05` behind a length read with a scatter, ` ± 2 in` in feet and inches; nothing behind
     * one measured end to end, which has none to give — rounded up to a step, its 0 would read as
     * a scatter of 5 cm.
     */
    private fun lengthSpread(plusMinus: Float, units: MeasureUnitSystem, locale: Locale): String = when {
        plusMinus <= 0f -> ""
        units == MeasureUnitSystem.Imperial -> PLUS_MINUS + SurveyFormat.inchSpread(plusMinus) + INCHES
        else -> PLUS_MINUS + SurveyFormat.lengthSpread(plusMinus, locale)
    }

    /** ` ± 0.3` behind an area read with a scatter, ` ± 4` in square feet; nothing behind one measured end to end. */
    private fun areaSpread(plusMinus: Float, units: MeasureUnitSystem, locale: Locale): String = when {
        plusMinus <= 0f -> ""
        units == MeasureUnitSystem.Imperial -> PLUS_MINUS + SurveyFormat.squareFeetSpread(plusMinus)
        else -> PLUS_MINUS + SurveyFormat.areaSpread(plusMinus, locale)
    }

    /**
     * ` ± 1.2` behind a volume read with a scatter, ` ± 43` in cubic feet;
     * nothing behind one measured end to end.
     */
    private fun volumeSpread(plusMinus: Float, units: MeasureUnitSystem, locale: Locale): String = when {
        plusMinus <= 0f -> ""
        units == MeasureUnitSystem.Imperial -> PLUS_MINUS + SurveyFormat.cubicFeetSpread(plusMinus)
        else -> PLUS_MINUS + SurveyFormat.areaSpread(plusMinus, locale)
    }

    /** `true` when [figure] is written with a "±": measured, and read with a scatter. */
    private fun scattered(figure: SurveyFigure?): Boolean = figure != null && !figure.estimate && figure.plusMinus > 0f

    /** The scan's name and, when the scan has one, its date. */
    fun heading(wording: SurveyWording, title: String, date: String?): String =
        if (date == null) title else wording.heading.format(title, date)

    /** What the scan lacks for an area, as the thing to go and scan; `null` when it lacks nothing. */
    fun areaHint(survey: RoomSurvey, wording: SurveyWording): String? =
        survey.gaps.firstOrNull { it != SurveyGap.Ceiling }?.let { wording.gaps[it] }

    /** What to go and scan, one line per thing the survey lacks. */
    fun hints(survey: RoomSurvey, wording: SurveyWording): List<String> = survey.gaps.mapNotNull { wording.gaps[it] }

    /**
     * What the marks in use mean: "≈" when a figure is an estimate, "±" when one is written with
     * a scatter. A room traced corner by corner has none, and so no word about a scan.
     */
    fun notes(survey: RoomSurvey, wording: SurveyWording): List<String> = listOfNotNull(
        wording.estimateNote.takeIf { survey.hasEstimates },
        wording.spreadNote.takeIf { hasScatter(survey) },
    )

    /** `true` when a figure of [survey] — a wall's length, as the table writes it, or the room's — carries a "±". */
    private fun hasScatter(survey: RoomSurvey): Boolean =
        survey.walls.any { it.seen && scattered(it.length) } ||
            listOf(survey.area, survey.length, survey.width, survey.perimeter, survey.ceilingHeight).any(::scattered)

    /** The survey's figures: area, length × width, perimeter, ceiling height and volume — or what is missing. */
    fun rows(survey: RoomSurvey, wording: SurveyWording): List<SurveyRow> {
        val rows = ArrayList<SurveyRow>()
        val area = survey.area
        val length = survey.length
        val width = survey.width
        val units = wording.units
        val locale = wording.locale
        rows += when (area) {
            null -> SurveyRow(wording.area, wording.notMeasured, muted = true)
            else -> SurveyRow(wording.area, area(area, units, locale))
        }
        if (length != null && width != null) {
            // `4.00 × 3.00 m`, one unit for both; feet and inches name theirs on each side.
            val first = when (units) {
                MeasureUnitSystem.Metric -> SurveyFormat.length(length.value, locale)
                MeasureUnitSystem.Imperial -> lengthFigure(length.value, units, locale)
            }
            val both = first + " × " + lengthFigure(width.value, units, locale)
            val value = when {
                length.estimate || width.estimate -> SurveyFormat.ESTIMATE + both
                else -> both + lengthSpread(max(length.plusMinus, width.plusMinus), units, locale)
            }
            rows += SurveyRow(wording.dimensions, value)
        } else {
            (length ?: width)?.let { rows += SurveyRow(wording.span, length(it, units, locale)) }
        }
        survey.perimeter?.let { rows += SurveyRow(wording.perimeter, length(it, units, locale)) }
        val height = survey.ceilingHeight
        rows += when (height) {
            null -> SurveyRow(wording.height, wording.notSeen, muted = true)
            else -> SurveyRow(wording.height, length(height, units, locale))
        }
        rows += when (val volume = PlanAreas.volume(survey.area, height)) {
            null -> SurveyRow(wording.volume, wording.notMeasured, muted = true)
            else -> SurveyRow(wording.volume, volume(volume, units, locale))
        }
        return rows
    }

    /** The plan's rooms: a scan is one room, numbered as the plan's first. */
    fun areas(survey: RoomSurvey, wording: SurveyWording): PlanAreas =
        PlanAreas.of(listOf(PlanAreas.room(wording.room.format(1), survey)))

    /**
     * One row per room — its floor area, then its volume — and the plan's total last. A room
     * without a figure says so, and the total then gives none: it would leave that room out.
     */
    fun roomRows(areas: PlanAreas, wording: SurveyWording): List<SurveyRow> =
        areas.rooms.map { roomRow(it.name, it.area, it.volume, wording) } +
            roomRow(wording.total, areas.area, areas.volume, wording)

    private fun roomRow(label: String, area: SurveyFigure?, volume: SurveyFigure?, wording: SurveyWording) =
        SurveyRow(
            label = label,
            value = (area?.let { area(it, wording.units, wording.locale) } ?: wording.notMeasured) + SEPARATOR +
                (volume?.let { volume(it, wording.units, wording.locale) } ?: wording.notMeasured),
            muted = area == null,
        )

    /** One row per wall: its letter, its length, and whether it was seen. */
    fun wallRows(survey: RoomSurvey, wording: SurveyWording): List<SurveyRow> = survey.walls.map { wall ->
        val label = wording.wall.format(wall.tag)
        when {
            !wall.sized ->
                SurveyRow(label, wording.notMeasured + (if (wall.seen) "" else SEPARATOR + wording.notSeen), muted = true)
            !wall.seen -> SurveyRow(label, wallFigure(wall, wording) + SEPARATOR + wording.notSeen, muted = true)
            wall.length.estimate ->
                SurveyRow(label, wallFigure(wall, wording) + SEPARATOR + wording.estimated, muted = true)
            else -> SurveyRow(label, length(wall.length, wording.units, wording.locale))
        }
    }

    /** A wall's length with its unit and its "≈", without its "±": `≈ 4.00 m`, `≈ 13 ft 1 in`. */
    private fun wallFigure(wall: SurveyWall, wording: SurveyWording): String = when (wording.units) {
        MeasureUnitSystem.Metric -> SurveyFormat.wall(wall, wording.locale) + METRES
        MeasureUnitSystem.Imperial -> (if (wall.length.estimate) SurveyFormat.ESTIMATE else "") +
            SurveyFormat.feetAndInches(wall.length.value)
    }

    /**
     * [rows] cut into pages: [first] of them on the page the table starts on, [following] on
     * each page after it. No row is dropped, however many walls the room has.
     */
    fun pages(rows: List<SurveyRow>, first: Int, following: Int): List<List<SurveyRow>> {
        if (rows.size <= first) return listOf(rows)
        return listOf(rows.take(first)) + rows.drop(first).chunked(following)
    }

    /**
     * The plan's own words: a label per wall, the area in the middle, the scale bar's length — in
     * [SurveyWording.units], the bar at a round number of them.
     */
    fun planTexts(survey: RoomSurvey, wording: SurveyWording): PlanTexts {
        val units = wording.units
        val locale = wording.locale
        return PlanTexts(
            walls = survey.walls.map { SurveyFormat.wall(it, units, locale) },
            area = survey.area?.let { (if (it.estimate) SurveyFormat.ESTIMATE else "") + areaFigure(it.value, units, locale) },
            // An estimate has no scatter to give, nor has an area measured end to end.
            areaNote = survey.area?.takeIf(::scattered)?.let {
                PLUS_MINUS.trim() + " " + when (units) {
                    MeasureUnitSystem.Metric -> SurveyFormat.areaSpread(it.plusMinus, locale) + SQUARE_METRES
                    MeasureUnitSystem.Imperial -> SurveyFormat.squareFeetSpread(it.plusMinus) + SQUARE_FEET
                }
            },
            scale = { metres ->
                wording.scaleBar.format(
                    when (units) {
                        MeasureUnitSystem.Metric -> if (metres < 1f) HALF_METRE.format(locale, 0.5f) else metres.toInt().toString()
                        MeasureUnitSystem.Imperial -> SurveyFormat.feet(metres).toString()
                    },
                )
            },
            scaleBars = SurveyFormat.scaleBars(units),
        )
    }

    /**
     * The survey as a few plain lines, ready to paste: the figures, the walls, what to scan for
     * what is missing, what the marks mean, and the notice that this is no certified measurement.
     * The figures and the walls are the table's own rows: the same marks as on the screen and the PDF.
     */
    fun text(survey: RoomSurvey, wording: SurveyWording, title: String, date: String?): String {
        val lines = ArrayList<String>()
        lines += heading(wording, title, date)
        val rows = rows(survey, wording)
        // The floor's figures on one line, the height and the volume it gives on the next.
        val room = rows.takeLast(2)
        lines += rows.dropLast(2).joinToString(SEPARATOR) { it.label + ": " + it.value }
        lines += room.joinToString(SEPARATOR) { it.label + ": " + it.value }
        if (survey.hasPlan) {
            lines += wording.rooms + ": " +
                roomRows(areas(survey, wording), wording).joinToString(LIST_SEPARATOR) { it.label + " " + it.value }
        }
        if (survey.walls.isNotEmpty()) {
            val walls = wallRows(survey, wording)
            lines += wording.walls + ": " +
                survey.walls.indices.joinToString(LIST_SEPARATOR) { survey.walls[it].tag + " " + walls[it].value }
        }
        hints(survey, wording).takeIf { it.isNotEmpty() }?.let { lines += it.joinToString(" ") }
        lines += notes(survey, wording)
        lines += wording.notice + " " + wording.madeWith
        return lines.joinToString("\n")
    }
}
