package io.github.sceneview.demo.demos.internal

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * "Your room, as a dollhouse" (#4075): a room recorded with the Rerun demo, cut open and stood on
 * a table as a miniature.
 *
 * Pure Kotlin, no ARCore and no Filament: which session opens, what of it is kept, how small it
 * is drawn and what the screen says are all decided here, so the tests pin what the screen draws.
 * The drawing itself reuses the replay's own layers (the plane photos, the points, the
 * path), fed the frame [RoomDollhouse.crop] returns.
 */

/**
 * How a room stands as a miniature: the [crop]ped content's footprint and height in the room's
 * own metres, and the scale it is drawn at — `1 : [denominator]`.
 *
 * The content is drawn offset by ([centerX], [floorY], [centerZ]) and scaled, so the middle of
 * the room's floor lands on the table where the anchor is.
 */
data class DollhouseFit(
    val centerX: Float,
    val floorY: Float,
    val centerZ: Float,
    val width: Float,
    val height: Float,
    val depth: Float,
    val denominator: Int,
) {
    /** The miniature's scale: `1 / denominator`. */
    val scale: Float get() = 1f / denominator

    /** `1:20`: the scale as a model maker writes it. */
    val label: String get() = "1:$denominator"

    /** The miniature's footprint, in centimetres on the table: `[width, depth]`. */
    fun footprintCentimetres(): IntArray = intArrayOf(
        Math.round(width * scale * 100f).coerceAtLeast(1),
        Math.round(depth * scale * 100f).coerceAtLeast(1),
    )
}

/** A room ready to stand: what of it is drawn ([RoomDollhouse.crop]) and how ([RoomDollhouse.fit]). */
class DollhouseRoom(val frame: ArDebugFrame, val fit: DollhouseFit)

object RoomDollhouse {
    /** The longest side of the miniature is kept at or under this, in metres: it fits a table. */
    const val MAX_SIDE_M = 0.35f

    /**
     * The scales a miniature is drawn at: the model maker's ones, so the label reads as a scale
     * and not as a computed ratio. A room of 4 m opens at 1:12, one of 6 m at 1:20.
     */
    val SCALES = listOf(5, 10, 12, 20, 24, 50, 100)

    /** Points higher than this over the floor are the ceiling's: cut away, like a dollhouse's roof. */
    const val CUTAWAY_HEIGHT_M = 2.1f

    /** Points a little under the floor are still the floor's (ARCore's noise); deeper, they are dropped. */
    const val UNDER_FLOOR_M = 0.05f

    /** How far outside the room's planes and path a point may be and still belong to the room. */
    const val POINT_MARGIN_M = 0.5f

    /**
     * The radius of a point **on the table**, in metres: the replay sizes points by screen pixels,
     * the miniature by what reads from arm's length — under a millimetre.
     */
    const val MINIATURE_POINT_RADIUS_M = 0.0008f

    /** How far the plinth reaches past the room, in the room's metres, and how far under its floor. */
    const val BASE_MARGIN_M = 0.15f
    const val BASE_DROP_M = 0.02f

    /**
     * The session to open: [requestedId] when it is still kept, else the newest room recorded on
     * this phone, else the newest session of any kind, else `null` — nothing recorded yet, and the
     * screen offers to record one. Never a stock asset.
     */
    fun pickSession(sessions: List<RerunStoredSession>, requestedId: String? = null): RerunStoredSession? {
        requestedId?.let { id -> sessions.firstOrNull { it.id == id }?.let { return it } }
        return sessions.filter { it.source == RerunSessionSource.Recorded }.maxByOrNull { it.createdAt }
            ?: sessions.maxByOrNull { it.createdAt }
    }

    /**
     * The room cut open: the ceilings go, and so do the points above [CUTAWAY_HEIGHT_M], under the
     * floor, or more than [POINT_MARGIN_M] outside the room's planes and path (the far wall seen
     * through a doorway, a reflection). The photo frustums go too — a miniature has no camera.
     */
    fun crop(frame: ArDebugFrame): ArDebugFrame {
        val floorY = ArDebugGeometry.floorHeight(frame)
        val top = floorY + CUTAWAY_HEIGHT_M
        val planes = frame.planes.filter { plane ->
            plane.kind != DebugPlaneKind.Ceiling && plane.vertexCount >= 3 && lowestY(plane) <= top
        }
        val box = ArDebugGeometry.contentBounds(
            ArDebugFrame(frame.time, frame.trail, null, FloatArray(0), FloatArray(0), planes, frame.anchors),
        )
        val kept = (0 until frame.mapPointCount).filter { i ->
            val y = frame.mapPoints[i * 3 + 1]
            y in (floorY - UNDER_FLOOR_M)..top &&
                (box == null || insideFootprint(box, frame.mapPoints[i * 3], frame.mapPoints[i * 3 + 2]))
        }
        val points = FloatArray(kept.size * 3)
        kept.forEachIndexed { k, i -> frame.mapPoints.copyInto(points, k * 3, i * 3, i * 3 + 3) }
        val colors = frame.mapPointColors?.let { all -> IntArray(kept.size) { all[kept[it]] } }
        return ArDebugFrame(
            time = frame.time,
            trail = frame.trail,
            camera = null,
            mapPoints = points,
            livePoints = FloatArray(0),
            planes = planes,
            anchors = frame.anchors,
            mapPointColors = colors,
        )
    }

    /** Whether ([x], [z]) lies within [POINT_MARGIN_M] of the room's footprint [box]. */
    private fun insideFootprint(box: FloatArray, x: Float, z: Float): Boolean =
        x in (box[0] - POINT_MARGIN_M)..(box[3] + POINT_MARGIN_M) &&
            z in (box[2] - POINT_MARGIN_M)..(box[5] + POINT_MARGIN_M)

    /**
     * How the [crop]ped [frame] stands as a miniature, `null` when it holds nothing to stand — no
     * path, plane or point.
     */
    fun fit(frame: ArDebugFrame): DollhouseFit? {
        val floorY = ArDebugGeometry.floorHeight(frame)
        val bounds = ArDebugGeometry.contentBounds(frame) ?: pointBounds(frame) ?: return null
        val pointTop = pointBounds(frame)?.get(4) ?: bounds[4]
        val width = bounds[3] - bounds[0]
        val depth = bounds[5] - bounds[2]
        return DollhouseFit(
            centerX = (bounds[0] + bounds[3]) / 2f,
            floorY = floorY,
            centerZ = (bounds[2] + bounds[5]) / 2f,
            width = width,
            height = (max(bounds[4], pointTop) - floorY).coerceAtLeast(MIN_HEIGHT_M),
            depth = depth,
            denominator = denominatorFor(max(width, depth)),
        )
    }

    /** The room of [whole] (a session's last frame) [crop]ped and [fit], `null` when it is empty. */
    fun room(whole: ArDebugFrame): DollhouseRoom? {
        val cropped = crop(whole)
        return fit(cropped)?.let { DollhouseRoom(cropped, it) }
    }

    /** The smallest of [SCALES] that brings a room [longestSide] metres long under [MAX_SIDE_M]. */
    fun denominatorFor(longestSide: Float): Int {
        if (!longestSide.isFinite() || longestSide <= 0f) return SCALES.first()
        return SCALES.firstOrNull { longestSide / it <= MAX_SIDE_M } ?: SCALES.last()
    }

    /**
     * The replay's style, sized for a room drawn at [scale]: its points come out
     * [MINIATURE_POINT_RADIUS_M] wide on the table, and its path as thin. At real size the style's
     * own floor (4 mm) takes over.
     */
    fun styleFor(scale: Float): ArDebugStyle {
        val roomRadius = MINIATURE_POINT_RADIUS_M / scale.coerceAtLeast(MIN_SCALE)
        return ArDebugStyle(metresPerPixel = roomRadius / POINT_RADIUS_PIXELS)
    }

    /**
     * The plinth under the room: the outline of its planes seen from above (their convex hull),
     * [BASE_MARGIN_M] wider all round, just under its floor, as a flat `[x,y,z, …]` polygon in the
     * room's own coordinates. A room scanned at an angle to the session's axes gets a plinth that
     * follows it, not the far larger box around it. With fewer than three corners to go on, the
     * [fit]'s rectangle.
     */
    fun basePolygon(frame: ArDebugFrame, fit: DollhouseFit): FloatArray {
        val corners = frame.planes.flatMap { plane ->
            (0 until plane.vertexCount).map { plane.polygon[it * 3] to plane.polygon[it * 3 + 2] }
        }
        val hull = convexHull(corners)
        if (hull.size < 3) return basePolygon(fit)
        val y = fit.floorY - BASE_DROP_M
        val cx = hull.map { it.first }.average().toFloat()
        val cz = hull.map { it.second }.average().toFloat()
        val out = FloatArray(hull.size * 3)
        hull.forEachIndexed { i, (x, z) ->
            val length = hypot(x - cx, z - cz).coerceAtLeast(MIN_HEIGHT_M)
            out[i * 3] = x + (x - cx) / length * BASE_MARGIN_M
            out[i * 3 + 1] = y
            out[i * 3 + 2] = z + (z - cz) / length * BASE_MARGIN_M
        }
        return out
    }

    /** The [fit]'s own box, [BASE_MARGIN_M] wider all round: the plinth of a room without planes. */
    fun basePolygon(fit: DollhouseFit): FloatArray {
        val y = fit.floorY - BASE_DROP_M
        val hx = fit.width / 2f + BASE_MARGIN_M
        val hz = fit.depth / 2f + BASE_MARGIN_M
        val x0 = fit.centerX - hx
        val x1 = fit.centerX + hx
        val z0 = fit.centerZ - hz
        val z1 = fit.centerZ + hz
        return floatArrayOf(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1)
    }

    /** The convex hull of [points] (x, z), counter-clockwise from the lowest x (monotone chain). */
    internal fun convexHull(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        val sorted = points.distinct().sortedWith(compareBy({ it.first }, { it.second }))
        if (sorted.size < 3) return sorted
        fun cross(o: Pair<Float, Float>, a: Pair<Float, Float>, b: Pair<Float, Float>) =
            (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)
        fun chain(ordered: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
            val out = ArrayList<Pair<Float, Float>>()
            for (p in ordered) {
                while (out.size >= 2 && cross(out[out.size - 2], out.last(), p) <= 0f) out.removeAt(out.size - 1)
                out += p
            }
            return out.dropLast(1)
        }
        return chain(sorted) + chain(sorted.asReversed())
    }

    private fun lowestY(plane: DebugPlane): Float =
        (0 until plane.vertexCount).minOf { plane.polygon[it * 3 + 1] }

    private fun pointBounds(frame: ArDebugFrame): FloatArray? {
        if (frame.mapPointCount == 0) return null
        val b = FloatArray(6) { if (it < 3) Float.MAX_VALUE else -Float.MAX_VALUE }
        for (i in 0 until frame.mapPointCount) for (a in 0..2) {
            val v = frame.mapPoints[i * 3 + a]
            b[a] = minOf(b[a], v)
            b[a + 3] = maxOf(b[a + 3], v)
        }
        return b
    }

    /** `ArDebugStyle.mapPointRadius` is 2.4 px. */
    private const val POINT_RADIUS_PIXELS = 2.4f
    private const val MIN_SCALE = 0.001f
    private const val MIN_HEIGHT_M = 0.1f
}

/** What the dollhouse screen shows, decided from what is known — see [dollhouseStage]. */
enum class DollhouseStage {
    /** The kept sessions are still being listed, or the chosen one is being read. */
    Loading,

    /** Nothing recorded yet: the screen offers to record a room. */
    Empty,

    /** The chosen session could not be read. */
    Failed,

    /** The miniature, on a table in AR. */
    InRoom,

    /** The miniature in a plain 3D view: this device has no AR, or the user asked for it. */
    Preview,
}

/**
 * The dollhouse screen's stage. [sessionsKnown] once the store has been listed, [hasSession] when
 * it holds one to open, [opened] / [openFailed] once it has been read, [arAvailable] `false` once
 * ARCore said it cannot run here, and [previewChosen] when the user switched to the 3D view.
 */
fun dollhouseStage(
    sessionsKnown: Boolean,
    hasSession: Boolean,
    opened: Boolean,
    openFailed: Boolean,
    arAvailable: Boolean,
    previewChosen: Boolean,
): DollhouseStage = when {
    !sessionsKnown -> DollhouseStage.Loading
    !hasSession -> DollhouseStage.Empty
    openFailed -> DollhouseStage.Failed
    !opened -> DollhouseStage.Loading
    !arAvailable || previewChosen -> DollhouseStage.Preview
    else -> DollhouseStage.InRoom
}

/** The dollhouse screen's words, in one place (English, like the rest of the Rerun demo). */
object DollhouseCopy {
    const val VIEW_IN_AR = "View in AR"
    const val VIEW_IN_AR_CAPTION = "AR"
    const val OPENING = "Opening your room…"
    const val OPEN_FAILED = "This room could not be opened. Record it again, or pick another session."

    const val EMPTY_TITLE = "No room recorded yet"
    const val EMPTY_BODY = "Record your room with the Rerun demo, and it stands on your table as a " +
        "miniature you can walk around."
    const val RECORD = "Record your room"

    const val REAL_SIZE = "Real size"
    const val MINIATURE = "Miniature"
    const val RESET = "Reset"
    const val VIEW_3D = "3D"
    const val VIEW_AR = "AR"
    const val VIEW_3D_LABEL = "Show in 3D"
    const val VIEW_AR_LABEL = "Show on a table in AR"

    const val NO_AR = "AR isn't available on this device, so your room is shown in 3D."
    const val PLACE_HINT = "Point at a table. Your room stands on it as a miniature."
    const val GESTURE_HINT = "Drag to move · twist to turn · pinch to resize"

    const val INTRO = "Your own room, recorded with the Rerun demo, cut open and stood on a table " +
        "as a miniature: the surfaces with their photos, the points and the path you walked. " +
        "Drag it, twist it, pinch it — or switch to Real size and stand inside it."

    /** `Room · Sep 28, 2:32 PM · 1:20`. */
    fun peek(title: String, fit: DollhouseFit, realSize: Boolean): String =
        "$title · ${if (realSize) REAL_SIZE else fit.label}"

    /** `1:20 · 30 × 22 cm on the table`, or `Real size` when the room stands at its own size. */
    fun size(fit: DollhouseFit, realSize: Boolean): String {
        if (realSize) return "$REAL_SIZE · the room at its own size"
        val (w, d) = fit.footprintCentimetres().let { it[0] to it[1] }
        return "${fit.label} · $w × $d cm on the table"
    }

    /**
     * The status pill while pinching: the scale the miniature now stands at (`1:6` once a 1:12
     * room is pinched to twice its size), or, at real size, how much of it is left.
     */
    fun pinched(fit: DollhouseFit, scaleFactor: Float, realSize: Boolean): String =
        if (realSize) "${(scaleFactor * 100f).roundToInt()}% of real size"
        else "1:${max(1, (fit.denominator / scaleFactor).roundToInt())}"
}
