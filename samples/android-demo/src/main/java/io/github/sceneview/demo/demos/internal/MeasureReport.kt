@file:Suppress("MatchingDeclarationName")

package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import java.util.Locale

/**
 * The numbers of one figure, in metres — what the readout card and the share text show.
 *
 * Wall and room figures are measured on their floor line: every point sits at the height of
 * the figure's first point ([measureFigureOutline]), so a corner the reticle caught on the
 * skirting board does not tilt the wall.
 */
data class MeasureFigureNumbers(
    val mode: MeasureMode,
    val segments: List<Float>,
    val length: Float,
    val closed: Boolean,
    val outlineArea: Float?,
    val height: Float?,
    val wallArea: Float?,
    /** A room's floor area times its height; `null` until both are known. */
    val volume: Float? = null,
    /** The area of the doors and windows marked on a wall or a room's walls; `null` with none marked. */
    val openingsArea: Float? = null,
    /** The wall area less its openings — a wall's, or all of a room's walls'; `null` with none marked. */
    val netWallArea: Float? = null,
)

/**
 * World outline of a figure from its resolved point positions: as-is for a distance chain,
 * levelled on the first point for walls and rooms.
 */
fun measureFigureOutline(mode: MeasureMode, positions: List<Position>): List<Position> {
    if (mode == MeasureMode.Distance || positions.isEmpty()) return positions
    val floor = positions.first().y
    return positions.map { measureOnLevel(it, floor) }
}

/**
 * @param positions the figure's outline, already levelled ([measureFigureOutline]).
 * @param liveHeight the height the walls follow right now, while [MeasureFigure.raising].
 */
fun <P> measureFigureNumbers(
    figure: MeasureFigure<P>,
    positions: List<Position>,
    liveHeight: Float? = null,
): MeasureFigureNumbers {
    val segments = ArrayList<Float>()
    for (i in 0 until positions.size - 1) segments += measureDistanceMeters(positions[i], positions[i + 1])
    if (figure.closed && positions.size > 2) segments += measureDistanceMeters(positions.last(), positions.first())
    val height = figure.height ?: liveHeight?.takeIf { figure.raising }
    val outlineArea = when {
        !figure.closed || positions.size < MeasureBoard.MIN_OUTLINE_POINTS -> null
        figure.mode == MeasureMode.Room -> measureFloorArea(positions)
        else -> measurePolygonArea(positions)
    }
    val wallArea = height?.let { measureWallArea(positions, figure.closed, it) }
    val volume = if (figure.mode == MeasureMode.Room && outlineArea != null && height != null) {
        outlineArea * height
    } else {
        null
    }
    // Each opening is marked on one of the figure's walls (MeasureOpenings.kt) and counted there.
    val openingsArea = if (figure.mode != MeasureMode.Distance && figure.openings.isNotEmpty() && height != null) {
        measureFigureOpeningsArea(figure.openings, segments, height)
    } else {
        null
    }
    return MeasureFigureNumbers(
        mode = figure.mode,
        segments = segments,
        length = segments.sum(),
        closed = figure.closed,
        outlineArea = outlineArea,
        height = height,
        wallArea = wallArea,
        volume = volume,
        openingsArea = openingsArea,
        netWallArea = if (wallArea != null && openingsArea != null) (wallArea - openingsArea).coerceAtLeast(0f) else null,
    )
}

/**
 * A wall's or a room's numbers as words, each `null` until there is a height: [height] the wall
 * height, [area] the wall area, [net] the wall area left once the doors and windows marked on it
 * are off (`null` with none marked). Formatted once for the readout card and the labels on the
 * walls in AR alike, so both say the same thing in the same units (#4765).
 */
data class MeasureWallText(val height: String?, val area: String?, val net: String?)

/** [numbers]' [MeasureWallText], with [len] and [area] the screen's length and area formats. */
fun measureWallText(
    numbers: MeasureFigureNumbers,
    len: (Float) -> String,
    area: (Float) -> String,
): MeasureWallText = MeasureWallText(
    height = numbers.height?.let(len),
    area = numbers.wallArea?.let(area),
    net = numbers.netWallArea?.let(area),
)

/**
 * Plain-text summary of every figure, for the share sheet: what a person pastes into a
 * message to a carpenter or into a quote. English, like the rest of the demo.
 */
fun measureShareText(
    figures: List<MeasureFigureNumbers>,
    system: MeasureUnitSystem,
    locale: Locale,
): String {
    fun len(m: Float) = formatMeasureLength(m, system, locale)
    fun area(m: Float) = formatMeasureArea(m, system, locale)
    fun volume(m: Float) = formatMeasureVolume(m, system, locale)
    val lines = ArrayList<String>()
    lines += "Measured with SceneView"
    var distance = 0
    var wall = 0
    var room = 0
    for (f in figures) {
        if (f.segments.isEmpty()) continue
        lines += ""
        when (f.mode) {
            MeasureMode.Distance -> {
                distance++
                lines += "Distance $distance"
                f.segments.forEachIndexed { i, s -> lines += "  Segment ${i + 1}: ${len(s)}" }
                if (f.segments.size > 1) {
                    lines += if (f.closed) "  Perimeter: ${len(f.length)}" else "  Total: ${len(f.length)}"
                }
                f.outlineArea?.let { lines += "  Area: ${area(it)}" }
            }
            MeasureMode.Wall -> {
                wall++
                lines += "Wall $wall"
                lines += "  Width: ${len(f.length)}"
                f.height?.let { lines += "  Height: ${len(it)}" }
                f.wallArea?.let { lines += "  Area: ${area(it)}" }
                f.openingsArea?.let { lines += "  Doors and windows: ${area(it)}" }
                f.netWallArea?.let { lines += "  Net area: ${area(it)}" }
            }
            MeasureMode.Room -> {
                room++
                lines += "Room $room"
                f.segments.forEachIndexed { i, s -> lines += "  Wall ${i + 1}: ${len(s)}" }
                lines += "  Perimeter: ${len(f.length)}"
                f.outlineArea?.let { lines += "  Floor area: ${area(it)}" }
                f.height?.let { lines += "  Ceiling height: ${len(it)}" }
                f.wallArea?.let { lines += "  Wall area: ${area(it)}" }
                f.openingsArea?.let { lines += "  Doors and windows: ${area(it)}" }
                f.netWallArea?.let { lines += "  Net wall area: ${area(it)}" }
                f.volume?.let { lines += "  Volume: ${volume(it)}" }
            }
        }
    }
    lines += ""
    lines += "AR measurements on a phone are accurate to a few centimetres."
    return lines.joinToString("\n")
}
