package io.github.sceneview.demo.demos.internal

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

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

    /** How thick the plinth stands **on the table**, in metres: a model's base, not a sheet. */
    const val PLINTH_ON_TABLE_M = 0.008f

    /** How far the contact shadow reaches past the plinth **on the table**, in metres. */
    const val SHADOW_ON_TABLE_M = 0.012f

    /** Fewer points than this, with no plane, do not draw a room: they are ARCore's noise. */
    const val MIN_ROOM_POINTS = 30

    /**
     * The session to open: [requestedId] when it is still kept, else the newest room recorded on
     * this phone that kept a surface, else the newest session of any kind — preferring, again, one
     * with surfaces — else `null`: nothing recorded yet, and the screen offers to record one.
     * Never a stock asset.
     */
    fun pickSession(sessions: List<RerunStoredSession>, requestedId: String? = null): RerunStoredSession? {
        requestedId?.let { id -> sessions.firstOrNull { it.id == id }?.let { return it } }
        // A room to stand before a bare path: the newest recording that kept a surface.
        val candidates = sessions.filter { hasSurfaces(it) }.ifEmpty { sessions }
        return candidates.filter { it.source == RerunSessionSource.Recorded }.maxByOrNull { it.createdAt }
            ?: candidates.maxByOrNull { it.createdAt }
    }

    /** The recordings the dollhouse offers to stand instead of the one on the table, newest first. */
    fun choices(sessions: List<RerunStoredSession>): List<RerunStoredSession> =
        sessions.sortedByDescending { it.createdAt }

    /**
     * Whether a kept session holds any surface to stand, read from its `session.json` figures
     * without opening it: a recording with only the path walked and its photos has none.
     */
    fun hasSurfaces(session: RerunStoredSession): Boolean =
        session.planes > 0 || session.points >= MIN_ROOM_POINTS

    /**
     * Whether a [crop]ped room holds anything to stand as a room: a plane, or enough points to
     * show its shape. The path walked alone is not a room — a recording that only kept poses and
     * photos (#4075: the Record mode lost every plane and point on a real phone) says so, instead
     * of standing an empty plinth under a hair-thin line.
     */
    fun hasSurfaces(frame: ArDebugFrame): Boolean =
        frame.planes.any { it.vertexCount >= 3 } || frame.mapPointCount >= MIN_ROOM_POINTS

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

    /**
     * The plinth's thickness in the room's own metres, for a room drawn at [scale]: it stands
     * [PLINTH_ON_TABLE_M] thick on the table whatever the room's scale.
     */
    fun plinthThickness(scale: Float): Float = PLINTH_ON_TABLE_M / scale.coerceAtLeast(MIN_SCALE)

    /**
     * [polygon] (flat `[x,y,z, …]`, horizontal) pushed [margin] metres outwards from its centre,
     * at height [y]: the rings of the contact shadow around the plinth.
     */
    fun expand(polygon: FloatArray, margin: Float, y: Float): FloatArray {
        val n = polygon.size / 3
        if (n == 0) return FloatArray(0)
        var cx = 0f
        var cz = 0f
        for (i in 0 until n) {
            cx += polygon[i * 3]
            cz += polygon[i * 3 + 2]
        }
        cx /= n
        cz /= n
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            val x = polygon[i * 3]
            val z = polygon[i * 3 + 2]
            val length = hypot(x - cx, z - cz).coerceAtLeast(MIN_HEIGHT_M)
            out[i * 3] = x + (x - cx) / length * margin
            out[i * 3 + 1] = y
            out[i * 3 + 2] = z + (z - cz) / length * margin
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

    /**
     * How the [room] is turned and where it stands around the anchor (see [DollhouseOrientation]).
     *
     * The side the recording started from — where the path walked begins, the doorway more often
     * than not — is turned towards +Z, the side of the anchor that faces the user (ARCore's hit
     * pose on a plane points +Z roughly at the device). A path that starts within
     * [ENTRANCE_MIN_M] of the room's middle names no side, and the room keeps its own axes.
     */
    fun orientation(room: DollhouseRoom): DollhouseOrientation {
        val fit = room.fit
        val trail = room.frame.trail
        val yaw = if (trail.size >= 3) {
            val dx = trail[0] - fit.centerX
            val dz = trail[2] - fit.centerZ
            // A turn of `yaw` about +Y takes (sin a, cos a) to (sin(a + yaw), cos(a + yaw)).
            if (hypot(dx, dz) >= ENTRANCE_MIN_M) -atan2(dx, dz) else 0f
        } else 0f
        // The fit's box, turned: how far its nearest side now reaches towards the user.
        val hw = fit.width / 2f
        val hd = fit.depth / 2f
        val front = listOf(-hw to -hd, hw to -hd, hw to hd, -hw to hd)
            .maxOf { (x, z) -> -x * sin(yaw) + z * cos(yaw) }
        return DollhouseOrientation(yawDegrees = Math.toDegrees(yaw.toDouble()).toFloat(), front = front)
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

    /** A path starting nearer the room's middle than this names no side to walk in from. */
    const val ENTRANCE_MIN_M = 0.3f
}

/**
 * How the room stands around the anchor, in the anchor's frame (+Y up, +Z towards the user): its
 * floor's middle turned [yawDegrees] about +Y, so the side the recording started from faces the
 * user. [front] is how far that side then reaches towards the user, in the room's metres.
 *
 * The miniature stands centred on the anchor, where the user aimed. At real size the room is
 * pushed back by [front] (see [offsetZ]): its near side sits on the anchor and the room reaches
 * away from the user, floor on the surface the miniature stood on — a room to look into, not one
 * centred on the anchor, half a metre away, whose walls and floor cut through the camera.
 */
data class DollhouseOrientation(val yawDegrees: Float, val front: Float) {
    /** How far along Z the turned room is drawn at [scale]: 0 for the miniature, `-front` at real size. */
    fun offsetZ(realSize: Boolean, scale: Float): Float = if (realSize) -front * scale else 0f
}

/** What the dollhouse screen shows, decided from what is known — see [dollhouseStage]. */
enum class DollhouseStage {
    /** The kept sessions are still being listed, or the chosen one is being read. */
    Loading,

    /** Nothing recorded yet: the screen offers to record a room. */
    Empty,

    /** The chosen session could not be read. */
    Failed,

    /**
     * The chosen session was read, but it holds no surface to stand, only the path walked and
     * its photos. The screen says so and offers to record again; it never stands a stand-in.
     */
    NoSurfaces,

    /** The miniature, on a table in AR. */
    InRoom,

    /** The miniature in a plain 3D view: this device has no AR, or the user asked for it. */
    Preview,
}

/**
 * The dollhouse screen's stage. [sessionsKnown] once the store has been listed, [hasSession] when
 * it holds one to open, [opened] / [openFailed] once it has been read, [hasSurfaces] `false` when
 * what was read holds no surface to stand ([RoomDollhouse.hasSurfaces]), [arAvailable] `false`
 * once ARCore said it cannot run here, and [previewChosen] when the user switched to the 3D view.
 */
@Suppress("LongParameterList") // one flag per fact the screen knows
fun dollhouseStage(
    sessionsKnown: Boolean,
    hasSession: Boolean,
    opened: Boolean,
    openFailed: Boolean,
    arAvailable: Boolean,
    previewChosen: Boolean,
    hasSurfaces: Boolean = true,
): DollhouseStage = when {
    !sessionsKnown -> DollhouseStage.Loading
    !hasSession -> DollhouseStage.Empty
    openFailed -> DollhouseStage.Failed
    !opened -> DollhouseStage.Loading
    !hasSurfaces -> DollhouseStage.NoSurfaces
    !arAvailable || previewChosen -> DollhouseStage.Preview
    else -> DollhouseStage.InRoom
}

/**
 * The dollhouse's AR placement, as the screen drives it (#4075). Pure: the screen keeps one and
 * replaces it on every action, and the tests pin each transition.
 *
 * [generation] names the `AutoPlacementState` in use: the screen remembers one state per
 * generation. A new one is needed whenever the AR view leaves the screen and comes back — its
 * `AutoPlacementScene` dismisses the state it was given on the way out, and a dismissed state
 * never places again, nor resets — and on Reset, so the room goes and nothing of the last
 * placement (anchor, twist, pinch) survives. [sceneKey] rebuilds the AR view itself (Restart,
 * when the anchor could not be found again). [realSize] is the Real size toggle. [holdUntil] is
 * the `uptimeMillis` before which nothing is placed: after Reset the table stays empty a beat,
 * long enough to see the room go and aim elsewhere. Without it the room came straight back where
 * it stood, on the very next frame, and Reset looked like it did nothing.
 */
data class DollhouseArControl(
    val generation: Int = 0,
    val sceneKey: Int = 0,
    val realSize: Boolean = false,
    val holdUntil: Long = 0L,
) {
    /** Whether a placement may be made at [nowMillis]. */
    fun armed(nowMillis: Long): Boolean = nowMillis >= holdUntil

    /** Reset: the room goes, back to its miniature scale, and stands again a beat later. */
    fun reset(nowMillis: Long): DollhouseArControl =
        copy(generation = generation + 1, realSize = false, holdUntil = nowMillis + RESET_HOLD_MS)

    /** The AR view left the screen: the 3D view, another recording, the camera closed. */
    fun leftAr(): DollhouseArControl = copy(generation = generation + 1, realSize = false, holdUntil = 0L)

    /** Restart: the AR view is rebuilt, and the room placed afresh. */
    fun restart(): DollhouseArControl =
        copy(generation = generation + 1, sceneKey = sceneKey + 1, realSize = false, holdUntil = 0L)

    fun toggleRealSize(): DollhouseArControl = copy(realSize = !realSize)

    companion object {
        /** How long the table stays empty after Reset. */
        const val RESET_HOLD_MS = 1_500L
    }
}

/** The dock's scale toggle as it reads: [label] on it, lit when [selected]. */
data class DollhouseScaleToggle(val label: String, val selected: Boolean)

/** The dollhouse screen's words, in one place (English, like the rest of the Rerun demo). */
object DollhouseCopy {
    const val VIEW_IN_AR = "View in AR"
    const val VIEW_IN_AR_CAPTION = "AR"
    const val OPENING = "Opening your room…"
    const val OPEN_FAILED = "This room could not be opened. Record it again, or pick another session."

    const val NO_SURFACES_TITLE = "This recording has no surfaces yet — record again"
    const val NO_SURFACES_BODY = "It kept the path you walked and its photos, but no floor, wall or " +
        "table, so there is no room to stand on your table. Record your room again, moving slowly " +
        "past the floor and the walls."
    const val OTHER_RECORDINGS = "Or stand another recording"
    const val ON_THE_TABLE = "On the table"
    const val CHANGE_RECORDING = "Change recording"
    const val NO_SURFACES = "No surfaces"
    const val RESET_HOLD = "Room removed. Point at a table — it stands where you aim."

    const val EMPTY_TITLE = "No room recorded yet"
    const val EMPTY_BODY = "Record your room with the Rerun demo, and it stands on your table as a " +
        "miniature you can walk around."
    const val RECORD = "Record your room"

    const val REAL_SIZE = "Real size"
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

    /**
     * The dock's scale toggle: always named [REAL_SIZE] and lit while the room stands at real
     * size, like every dock toggle — so the lit item and the pill above the dock ([peek]) always
     * name the same scale. It used to be named after the scale a tap would switch *to* yet lit
     * for the one shown, and read "Miniature", lit, under a pill saying "Real size".
     */
    fun scaleToggle(realSize: Boolean): DollhouseScaleToggle = DollhouseScaleToggle(REAL_SIZE, selected = realSize)

    /**
     * What a kept recording holds, read from its figures: `3 surfaces · 1,240 points`, or
     * [NO_SURFACES] when it kept only the path walked and its photos.
     */
    fun surfaces(planes: Int, points: Int): String {
        if (planes <= 0 && points < RoomDollhouse.MIN_ROOM_POINTS) return NO_SURFACES
        val parts = buildList {
            if (planes > 0) add(if (planes == 1) "1 surface" else "$planes surfaces")
            if (points > 0) add(if (points == 1) "1 point" else String.format(Locale.US, "%,d points", points))
        }
        return parts.joinToString(" · ")
    }

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
