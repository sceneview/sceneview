package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import java.util.Locale

/**
 * One measured point as the debug QA log records it. Every field but [position] is what the
 * demo knew when the point was committed; `null` when it did not know it.
 *
 * @property position the point's world position now (the anchor's pose), metres.
 * @property source where the hit came from (`Plane`, `Floor`, `Depth`, `FeaturePoint`).
 * @property snap what the reticle locked onto (`MeasureSnapKind` name).
 * @property plane ARCore plane type of the hit (`HORIZONTAL_UPWARD_FACING`, `VERTICAL`…).
 * @property timestampNs ARCore frame timestamp of the aim the point was committed from.
 * @property cameraDistance camera-to-point distance at commit, metres.
 */
data class MeasureQaPoint(
    val position: Position,
    val source: String? = null,
    val snap: String? = null,
    val plane: String? = null,
    val timestampNs: Long? = null,
    val cameraDistance: Float? = null,
)

/** Version of the line shape below; bump it when a field changes meaning. */
const val MEASURE_QA_VERSION = 1

/**
 * The board as JSON lines, one per figure — the debug-only on-device accuracy log of the
 * `ar-measure` demo. Pure on purpose: the demo writes these to a file and to Logcat, the
 * tests read them back.
 *
 * Figures are named like the share text ("Distance 2", "Wall 1", "Room 1": one counter per
 * mode, a figure without a segment yet is skipped and not counted). Lengths are metres whatever
 * the unit system on screen, 4 decimals, `.` as the decimal separator in every locale.
 * `height_m` is a locked height only; while walls rise, the height they follow (the one the
 * readout and the share text show) is `live_height_m`, `null` otherwise.
 *
 * Line shape (v1):
 * `{"v":1,"figure":"Distance 2","mode":"Distance","segments_m":[1.2137],"length_m":1.2137,
 * "closed":false,"outline_area_m2":null,"height_m":null,"live_height_m":null,"wall_area_m2":null,"points":[{"x":…,
 * "y":…,"z":…,"source":"Plane","snap":"None","plane":"VERTICAL","t_ns":…,"cam_dist_m":1.42}],
 * "tracking":"TRACKING","device":"Pixel 9","app":"4.60.0-qa"}`
 *
 * @param point the QA record of a board point, `null` when its position is unknown (the
 *              figure is then measured without it, as the screen does).
 * @param tracking ARCore camera tracking state when the line is written.
 * @param liveHeight the height rising walls follow right now, metres; logged on the raising figure only.
 */
fun <P> measureQaLines(
    board: MeasureBoard<P>,
    point: (P) -> MeasureQaPoint?,
    tracking: String?,
    device: String?,
    app: String?,
    liveHeight: Float? = null,
): List<String> {
    val counters = IntArray(MeasureMode.entries.size)
    val lines = ArrayList<String>()
    for (figure in board.figures) {
        val records = figure.points.mapNotNull(point)
        val outline = measureFigureOutline(figure.mode, records.map { it.position })
        val numbers = measureFigureNumbers(figure, outline)
        if (numbers.segments.isEmpty()) continue
        val index = ++counters[figure.mode.ordinal]
        lines += buildString {
            append('{')
            field("v", MEASURE_QA_VERSION.toString())
            field("figure", jsonString("${figure.mode.name} $index"))
            field("mode", jsonString(figure.mode.name))
            field("segments_m", numbers.segments.joinToString(",", "[", "]") { jsonMeters(it) })
            field("length_m", jsonMeters(numbers.length))
            field("closed", numbers.closed.toString())
            field("outline_area_m2", jsonMeters(numbers.outlineArea))
            field("height_m", jsonMeters(numbers.height))
            field("live_height_m", jsonMeters(liveHeight?.takeIf { figure.raising }))
            field("wall_area_m2", jsonMeters(numbers.wallArea))
            field("points", records.joinToString(",", "[", "]") { it.toJson() })
            field("tracking", jsonString(tracking))
            field("device", jsonString(device))
            field("app", jsonString(app), last = true)
            append('}')
        }
    }
    return lines
}

private fun MeasureQaPoint.toJson(): String = buildString {
    append('{')
    field("x", jsonMeters(position.x))
    field("y", jsonMeters(position.y))
    field("z", jsonMeters(position.z))
    field("source", jsonString(source))
    field("snap", jsonString(snap))
    field("plane", jsonString(plane))
    field("t_ns", timestampNs?.toString() ?: "null")
    field("cam_dist_m", jsonMeters(cameraDistance), last = true)
    append('}')
}

private fun StringBuilder.field(name: String, json: String, last: Boolean = false) {
    append('"').append(name).append("\":").append(json)
    if (!last) append(',')
}

/** Metres (or square metres), 4 decimals, locale-independent; `null` for none or not finite. */
private fun jsonMeters(value: Float?): String =
    if (value == null || !value.isFinite()) "null" else String.format(Locale.ROOT, "%.4f", value)

private fun jsonString(value: String?): String {
    if (value == null) return "null"
    val out = StringBuilder(value.length + 2).append('"')
    for (c in value) {
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append(String.format(Locale.ROOT, "\\u%04x", c.code))
            else -> out.append(c)
        }
    }
    return out.append('"').toString()
}
