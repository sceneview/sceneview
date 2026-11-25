package io.github.sceneview.demo.demos.internal

import dev.romainguy.kotlin.math.Float3
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

// ── What the room told us ────────────────────────────────────────────────────────────────────

/**
 * The room as ARCore read it the moment a question was asked (or as the QA stage sets it).
 * World space, metres, y up.
 *
 * @param camera  Where the phone is.
 * @param heading Where it looks, flattened onto the floor: a unit vector with y = 0.
 * @param wall    The wall the centre of the screen points at, if it points at one.
 * @param sofa    Where a sofa would go in front of the phone, and what is either side of it.
 */
internal data class AskRoom(
    val camera: Float3,
    val heading: Float3,
    val floor: FloorRead,
    val wall: WallSpot? = null,
    val sofa: SofaSpot? = null,
)

/** The floor's height, and whether ARCore found it or it is [AskFloor.pick]'s fallback. */
internal data class FloorRead(val y: Float, val measured: Boolean)

/**
 * A spot on a wall: [point] where the camera's centre ray met it, [normal] out of it toward the
 * room, horizontal and of unit length.
 *
 * [edgeLeft] and [edgeRight]: how far along the wall, level with [point], the vertical plane
 * ARCore found there ends to the left and to the right as the phone sees it. `null` when the hit
 * was not on a plane, or the plane does not end that way.
 */
internal data class WallSpot(
    val point: Float3,
    val normal: Float3,
    val edgeLeft: Float? = null,
    val edgeRight: Float? = null,
)

internal object WallEdges {
    /**
     * Where a plane's outline crosses the level line through the hit, from the outline's
     * corners laid out on the wall ([InkPoint.x] along it to the right, [InkPoint.y] up, the hit
     * at the origin): the nearest crossing to the left and to the right, as distances.
     */
    fun around(outline: List<InkPoint>): Pair<Float?, Float?> {
        if (outline.size < 3) return null to null
        var left: Float? = null
        var right: Float? = null
        for (i in outline.indices) {
            val a = outline[i]
            val b = outline[(i + 1) % outline.size]
            // An edge that crosses y = 0 (one end on or above, the other below).
            if ((a.y >= 0f) == (b.y >= 0f)) continue
            val x = a.x + (b.x - a.x) * (0f - a.y) / (b.y - a.y)
            if (x < 0f) left = min(left ?: Float.MAX_VALUE, -x) else right = min(right ?: Float.MAX_VALUE, x)
        }
        return left to right
    }
}

/**
 * Where a sofa would stand: [centre] on the floor, and how far from it the nearest wall (or
 * depth hit) is along its long axis, to the [left] and to the [right] as the phone sees it.
 * `null` where nothing was hit within reach.
 */
internal data class SofaSpot(val centre: Float3, val left: Float? = null, val right: Float? = null)

internal object AskFloor {

    /** The floor is at least this far below the phone: a table top alone is not the floor. */
    const val MIN_BELOW_CAMERA = 0.9f

    /**
     * How far below the phone the floor is taken to be when ARCore has not found it: a phone
     * held at chest height by a standing adult. A **fallback**, not a measurement.
     */
    const val FALLBACK_BELOW_CAMERA = 1.4f

    /**
     * The floor, from the heights of the horizontal upward-facing planes ARCore tracks: the
     * lowest of them at least [MIN_BELOW_CAMERA] under the phone. With none, the camera's height
     * minus [FALLBACK_BELOW_CAMERA] — a guess, flagged as one.
     */
    fun pick(cameraY: Float, upwardPlaneHeights: List<Float>): FloorRead {
        val lowest = upwardPlaneHeights.filter { it <= cameraY - MIN_BELOW_CAMERA }.minOrNull()
        return if (lowest != null) FloorRead(lowest, measured = true)
        else FloorRead(cameraY - FALLBACK_BELOW_CAMERA, measured = false)
    }
}

// ── A drawing plan ───────────────────────────────────────────────────────────────────────────

/**
 * How a sheet of ink lies in its plan's frame. Strokes are drawn in the sheet's x/y plane, and
 * the sheet's +z is the side that faces whoever looks at it.
 */
internal enum class SheetFacing {
    /** Upright, x to the right and y up as seen from the room: on a wall, or a block in the air. */
    Wall,

    /** Laid flat on the floor: x to the right, y away from the phone, +z up. */
    Floor,

    /** Upright, and turned about the vertical to face the phone as it moves. */
    Billboard,
}

/**
 * One sheet of a [DrawingPlan]: its strokes, timed on the plan's clock (so its [Handwriting.starts]
 * are seconds since the whole plan started), at [offset] in the plan's frame.
 *
 * @param rimWidth How much wider than the ink its darker rim is, in metres.
 */
internal class InkSheet(
    val facing: SheetFacing,
    val offset: Float3,
    val writing: Handwriting,
    val rimWidth: Float,
)

/**
 * A hand-drawn answer laid out in the room rather than written on a block: lines, marks and
 * words on one or more [sheets], each placed in the plan's frame.
 *
 * The frame sits at [origin] (world space) and is turned [yaw] radians about the vertical: its
 * +x is `(cos yaw, 0, -sin yaw)` and its +z `(sin yaw, 0, cos yaw)`.
 */
internal class DrawingPlan(val origin: Float3, val yaw: Float, val sheets: List<InkSheet>) {

    /** Seconds until the last stroke of any sheet is finished. */
    val totalSeconds: Float get() = sheets.maxOfOrNull { it.writing.totalSeconds } ?: 0f

    /** A point of the plan's frame, in world space. */
    fun toWorld(local: Float3): Float3 {
        val c = cos(yaw)
        val s = sin(yaw)
        return Float3(origin.x + local.x * c + local.z * s, origin.y + local.y, origin.z - local.x * s + local.z * c)
    }

    /** A point of [sheet], in world space (a billboard as it stands before it turns). */
    fun toWorld(sheet: InkSheet, p: InkPoint): Float3 = toWorld(
        when (sheet.facing) {
            SheetFacing.Floor -> Float3(sheet.offset.x + p.x, sheet.offset.y, sheet.offset.z - p.y)
            SheetFacing.Wall, SheetFacing.Billboard -> Float3(sheet.offset.x + p.x, sheet.offset.y + p.y, sheet.offset.z)
        },
    )
}

/**
 * Builds a [DrawingPlan] as a hand draws it: every line, mark and word one after the other, on
 * one clock shared by all the sheets.
 */
internal class Sketch(seed: Int) {
    val random = Random(seed)
    private var clock = 0f
    private val sheets = mutableListOf<InkSheet>()

    /** A pen-lift's worth of nothing. */
    fun pause(seconds: Float) {
        clock += seconds
    }

    /** Draws on a new sheet; see [SheetBuilder]. */
    fun sheet(facing: SheetFacing, offset: Float3, rimWidth: Float, draw: SheetBuilder.() -> Unit) {
        val builder = SheetBuilder()
        builder.draw()
        sheets += InkSheet(facing, offset, builder.build(), rimWidth)
    }

    fun build(origin: Float3, yaw: Float) = DrawingPlan(origin, yaw, sheets.toList())

    inner class SheetBuilder {
        private val strokes = mutableListOf<InkStroke>()
        private val starts = mutableListOf<Float>()
        private val lines = mutableListOf<List<String>>()
        private var textBounds: InkBox? = null

        /**
         * One pen stroke through [points], at [speed] metres a second, easing in and out as a
         * hand does, pressed lighter at both ends.
         */
        fun line(points: List<InkPoint>, radius: Float, speed: Float, pause: Float = STROKE_GAP) {
            val path = HandwritingLayout.resample(points, max(radius * POINT_SPACING_RADII, MIN_SPACING))
            val distances = FloatArray(path.size)
            for (i in 1 until path.size) distances[i] = distances[i - 1] + path[i - 1].distanceTo(path[i])
            val length = distances.last()
            val duration = length / speed + MIN_STROKE_SECONDS
            val times = FloatArray(path.size) { i -> duration * penTime(if (length > 0f) distances[i] / length else 1f) }
            val taper = min(TAPER, length / 4f)
            val radii = List(path.size) { i -> radius * HandwritingLayout.pressure(distances[i], length, taper, i) }
            clock += pause
            starts += clock
            strokes += InkStroke(strokes.size, InkKind.Doodle, path, radii, times)
            clock += duration
        }

        /**
         * Handwritten words, placed by [place]: given the words laid out around the origin, it
         * returns how far to move them.
         */
        fun text(
            text: String,
            style: HandwritingStyle,
            doodle: Doodle = Doodle.None,
            pause: Float = WORD_PAUSE,
            place: (Handwriting) -> InkPoint,
        ) {
            val writing = HandwritingLayout.layout(text, doodle, style, seed = random.nextInt())
            val shift = place(writing)
            clock += pause
            writing.strokes.forEachIndexed { i, stroke ->
                starts += clock + writing.starts[i]
                strokes += InkStroke(
                    strokes.size,
                    stroke.kind,
                    stroke.points.map { InkPoint(it.x + shift.x, it.y + shift.y) },
                    stroke.radii,
                    stroke.times,
                )
            }
            clock += writing.totalSeconds
            lines += writing.lines
            val moved = writing.textBounds.let { InkBox(it.minX + shift.x, it.minY + shift.y, it.maxX + shift.x, it.maxY + shift.y) }
            textBounds = textBounds?.union(moved) ?: moved
        }

        fun build(): Handwriting {
            val bounds = strokes.map { s ->
                InkBox(s.points.minOf { it.x }, s.points.minOf { it.y }, s.points.maxOf { it.x }, s.points.maxOf { it.y })
            }.reduceOrNull(InkBox::union) ?: InkBox(0f, 0f, 0f, 0f)
            return Handwriting(lines.toList(), strokes.toList(), starts.toFloatArray(), bounds, textBounds ?: bounds, null)
        }
    }

    companion object {
        const val STROKE_GAP = 0.06f
        const val WORD_PAUSE = 0.18f
        private const val POINT_SPACING_RADII = 0.6f
        private const val MIN_SPACING = 0.003f
        private const val MIN_STROKE_SECONDS = 0.03f
        private const val TAPER = 0.02f

        /**
         * Share of a stroke's time spent by the moment the pen is [s] of the way along it: half
         * steady, half a hand's ease in and out (the inverse of smoothstep).
         */
        internal fun penTime(s: Float): Float {
            val t = s.coerceIn(0f, 1f)
            val eased = 0.5f - sin(asin(1f - 2f * t) / 3f)
            return 0.5f * t + 0.5f * eased
        }

        /**
         * A straight line from [a] to [b] as a hand draws it: its ends where they should be, a
         * wobble of at most [amplitude] across it in between.
         */
        fun handLine(a: InkPoint, b: InkPoint, amplitude: Float, random: Random, step: Float = 0.01f): List<InkPoint> {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = sqrt(dx * dx + dy * dy)
            if (length <= 0f) return listOf(a, b)
            val nx = -dy / length
            val ny = dx / length
            val n = max(2, (length / step).roundToInt())
            val p1 = random.nextFloat() * 2f * PI.toFloat()
            val p2 = random.nextFloat() * 2f * PI.toFloat()
            val f1 = 0.8f + 0.6f * random.nextFloat()
            val f2 = 2.6f + 1.2f * random.nextFloat()
            return List(n + 1) { i ->
                val t = i / n.toFloat()
                // Held down at both ends, free in the middle.
                val off = amplitude * sin(PI.toFloat() * t) *
                    (0.65f * sin(2f * PI.toFloat() * f1 * t + p1) + 0.35f * sin(2f * PI.toFloat() * f2 * t + p2))
                InkPoint(a.x + dx * t + nx * off, a.y + dy * t + ny * off)
            }
        }
    }
}

// ── "Where should I hang this picture?" ──────────────────────────────────────────────────────

/**
 * A level line on the wall at eye level, the two nails on it, the height and a note.
 *
 * In the wall's frame: origin on the line's centre (where the camera's centre ray meets the wall,
 * raised or lowered to [EYE_LEVEL] above the floor), x along the wall to the right as the phone
 * sees it, y up, +z out of the wall.
 */
internal object HangPicture {
    /** The centre of a picture, above the floor: the gallery rule. */
    const val EYE_LEVEL = 1.45f
    const val LINE_LENGTH = 0.8f
    const val NAIL_SPACING = 0.4f

    /** The ink stands this far off the wall, so it never sinks into it. */
    const val WALL_LIFT = 0.02f

    /** Text on the wall for the note: "145 cm" is [EYE_LEVEL], written as a rule, not a measure. */
    const val HEIGHT_LABEL = "145 cm"
    const val NOTE = "Centre here. *Eye level.*"

    private const val NAIL_SIZE = 0.03f
    private const val TICK_X = -0.3f
    private const val TICK_LENGTH = 0.22f
    private const val INK = 0.0062f
    private const val RIM = 0.0024f
    private val LABEL = HandwritingStyle(
        blockWidth = 2f, capHeight = 0.065f, inkRadius = 0.0056f, pointSpacing = 0.0032f,
        taperLength = 0.02f, minSeconds = 0.8f, maxSeconds = 1.3f,
    )
    private val NOTE_STYLE = LABEL.copy(blockWidth = 0.55f, minSeconds = 1.6f, maxSeconds = 2.4f)

    /** Along the wall, to the right as seen by someone facing it. */
    fun right(normal: Float3) = Float3(normal.z, 0f, -normal.x)

    /** The line's centre in world space: straight in front of where the ray met the wall. */
    fun lineCentre(wall: WallSpot, floorY: Float) = Float3(
        wall.point.x + wall.normal.x * WALL_LIFT,
        floorY + EYE_LEVEL,
        wall.point.z + wall.normal.z * WALL_LIFT,
    )

    /** Both ends of the level line, left then right, in world space. */
    fun levelLine(wall: WallSpot, floorY: Float): Pair<Float3, Float3> {
        val c = lineCentre(wall, floorY)
        val r = right(wall.normal) * (LINE_LENGTH / 2f)
        return (c - r) to (c + r)
    }

    /** Both nails, left then right, in world space. */
    fun nails(wall: WallSpot, floorY: Float): Pair<Float3, Float3> {
        val c = lineCentre(wall, floorY)
        val r = right(wall.normal) * (NAIL_SPACING / 2f)
        return (c - r) to (c + r)
    }

    /**
     * The wall's normal at a hit: [candidate] (a plane's or a depth point's) when it stands
     * near enough upright, else the way back to the camera; flattened, and turned to the room.
     */
    fun wallNormal(candidate: Float3?, point: Float3, camera: Float3): Float3 {
        val toCamera = flat(camera - point) ?: Float3(0f, 0f, 1f)
        val fromHit = candidate?.let { n -> flat(n)?.takeIf { sqrt(n.x * n.x + n.z * n.z) >= MIN_UPRIGHT * len(n) } }
        val n = fromHit ?: return toCamera
        return if (n.x * toCamera.x + n.z * toCamera.z < 0f) Float3(-n.x, 0f, -n.z) else n
    }

    fun plan(wall: WallSpot, floorY: Float, seed: Int = 145): DrawingPlan {
        val sketch = Sketch(seed)
        val r = sketch.random
        val half = LINE_LENGTH / 2f
        val nail = NAIL_SPACING / 2f
        sketch.sheet(SheetFacing.Wall, Float3(0f), RIM) {
            // The level line, left to right, in one go.
            line(Sketch.handLine(InkPoint(-half, 0f), InkPoint(half, 0f), 0.0028f, r), INK, speed = 0.95f, pause = 0f)
            // A small tick on its centre.
            line(Sketch.handLine(InkPoint(0f, 0.028f), InkPoint(0.002f, -0.028f), 0.001f, r, 0.005f), INK * 0.9f, 0.6f)
            // Two crosses for the nails: each two strokes, top-left first.
            for (x in listOf(-nail, nail)) {
                fun jitter() = (r.nextFloat() - 0.5f) * 0.006f
                line(
                    listOf(InkPoint(x - NAIL_SIZE + jitter(), NAIL_SIZE + jitter()), InkPoint(x + NAIL_SIZE + jitter(), -NAIL_SIZE + jitter())),
                    INK, 0.5f, pause = 0.12f,
                )
                line(
                    listOf(InkPoint(x + NAIL_SIZE + jitter(), NAIL_SIZE + jitter()), InkPoint(x - NAIL_SIZE + jitter(), -NAIL_SIZE + jitter())),
                    INK, 0.5f,
                )
            }
            // The height: a tick down toward the floor, its arrowhead, and its label.
            val bottom = -TICK_LENGTH
            line(Sketch.handLine(InkPoint(TICK_X, -0.018f), InkPoint(TICK_X, bottom), 0.0015f, r), INK * 0.9f, 0.7f, pause = 0.15f)
            line(
                listOf(InkPoint(TICK_X - 0.018f, bottom + 0.03f), InkPoint(TICK_X, bottom), InkPoint(TICK_X + 0.018f, bottom + 0.03f)),
                INK * 0.9f, 0.45f,
            )
            text(HEIGHT_LABEL, LABEL) { w -> InkPoint(TICK_X + 0.035f - w.textBounds.minX, bottom / 2f - w.textBounds.centerY) }
            // The note, above the line's centre.
            text(NOTE, NOTE_STYLE, Doodle.Underline, pause = 0.25f) { w ->
                InkPoint(-w.bounds.centerX, 0.08f - w.bounds.minY)
            }
        }
        return sketch.build(lineCentre(wall, floorY), atan2(wall.normal.x, wall.normal.z))
    }

    private const val MIN_UPRIGHT = 0.7f
}

// ── "Will a 2 m sofa fit here?" ──────────────────────────────────────────────────────────────

/**
 * A 200 × 90 cm footprint dashed on the floor, square to where the phone looks, its dimensions,
 * and a verdict above it from what ARCore measured either side.
 *
 * In the footprint's frame: origin on its centre, on the floor; x along its long side to the
 * right as the phone sees it; +z back toward the phone; y up.
 */
internal object SofaFit {
    const val LENGTH = 2f
    const val DEPTH = 0.9f

    /** How far ahead the footprint goes when the phone does not point at the floor in range. */
    const val AHEAD = 1.8f
    const val MIN_AHEAD = 1.2f
    const val MAX_AHEAD = 2.6f

    /** How far past each end of the sofa a wall is looked for. */
    const val REACH = 3f

    /** The side rays leave from this height: a sofa's seat, above skirting and floor clutter. */
    const val RAY_HEIGHT = 0.3f

    /** A hit nearer the centre than this is the ray grazing something of its own: ignored. */
    const val MIN_HIT = 0.1f

    const val DASH = 0.12f
    const val GAP = 0.07f

    /** The ink is lifted this far off the floor, so it never sinks into it. */
    const val FLOOR_LIFT = 0.012f

    private const val INK = 0.0085f
    private const val RIM = 0.0032f
    private const val DIM_OFFSET = 0.12f
    const val LENGTH_LABEL = "2 m"

    /** Half the break in the length line, where "2 m" is written: the label's half-width and a margin. */
    fun lengthGap(): Float = HandwritingLayout.layout(LENGTH_LABEL, Doodle.None, FLOOR_LABEL).textBounds.width / 2f + LABEL_MARGIN
    private const val LABEL_MARGIN = 0.06f
    private const val DIM_TICK = 0.05f
    private const val DEPTH_DIM_INSET = 0.13f
    private const val VERDICT_HEIGHT = 0.5f
    private val FLOOR_LABEL = HandwritingStyle(
        blockWidth = 2f, capHeight = 0.14f, inkRadius = 0.0095f, pointSpacing = 0.006f,
        taperLength = 0.025f, minSeconds = 0.5f, maxSeconds = 0.8f,
    )
    private val VERDICT = HandwritingStyle(
        blockWidth = 1.3f, capHeight = 0.12f, inkRadius = 0.0092f, pointSpacing = 0.0055f,
        taperLength = 0.025f, minSeconds = 1.6f, maxSeconds = 2.2f,
    )

    /** Along the sofa, to the right of [heading]. */
    fun axis(heading: Float3) = Float3(-heading.z, 0f, heading.x)

    /**
     * Where the sofa's centre goes: on the floor where the phone points, when that is
     * [MIN_AHEAD] to [MAX_AHEAD] in front of it; else [AHEAD] straight ahead.
     */
    fun centre(camera: Float3, heading: Float3, floorY: Float, floorHit: Float3?): Float3 {
        if (floorHit != null) {
            val dx = floorHit.x - camera.x
            val dz = floorHit.z - camera.z
            val ahead = dx * heading.x + dz * heading.z
            if (ahead in MIN_AHEAD..MAX_AHEAD) return Float3(floorHit.x, floorY, floorHit.z)
        }
        return Float3(camera.x + heading.x * AHEAD, floorY, camera.z + heading.z * AHEAD)
    }

    /** The footprint's corners in world space: near-left, near-right, far-right, far-left. */
    fun corners(centre: Float3, heading: Float3): List<Float3> {
        val r = axis(heading) * (LENGTH / 2f)
        val f = heading * (DEPTH / 2f)
        return listOf(centre - r - f, centre + r - f, centre + r + f, centre - r + f)
    }

    /**
     * Dashes along an edge [length] long, as (from, to) distances along it: as many as fit at
     * [dash] with [gap] between them, stretched so the first starts and the last ends on a
     * corner.
     */
    fun dashes(length: Float, dash: Float = DASH, gap: Float = GAP): List<Pair<Float, Float>> {
        val n = max(1, ((length + gap) / (dash + gap)).roundToInt())
        val d = (length - (n - 1) * gap) / n
        return List(n) { i ->
            val from = i * (d + gap)
            from to (from + d)
        }
    }

    /** What a side ray hit. */
    enum class HitKind { VerticalPlane, Depth, Other }

    /** A side ray's hit, [distance] metres from the sofa's centre. */
    data class SideHit(val distance: Float, val kind: HitKind)

    /**
     * The nearest wall or depth hit along one side, from the sofa's centre: within [REACH] of
     * the sofa's end. Floors, tables and anything else a ray may graze do not count.
     */
    fun nearest(hits: List<SideHit>): Float? = hits
        .filter { it.kind != HitKind.Other && it.distance in MIN_HIT..(LENGTH / 2f + REACH) }
        .minOfOrNull { it.distance }

    /**
     * The verdict, from what was measured either side of the centre. A number is written only
     * when both sides were measured (the room's width less the sofa), or to say how far to
     * slide it off the one wall that cuts into it.
     */
    fun verdict(left: Float?, right: Float?): String {
        val half = LENGTH / 2f
        return when {
            left != null && right != null -> {
                val spare = left + right - LENGTH
                when {
                    spare < 0f -> "Too tight: *${roundUp5(-spare)} cm* short."
                    round5(spare) == 0 -> "Fits, *only just.*"
                    else -> "Fits. *${round5(spare)} cm* to spare."
                }
            }
            left != null || right != null -> {
                val gap = (left ?: right!!) - half
                if (gap >= 0f) "Fits. *Lots of room.*" else "Fits. Slide it *${roundUp5(-gap)} cm* over."
            }
            else -> "Fits. *Lots of room.*"
        }
    }

    /** Centimetres, to the nearest 5: what a tape measure would be read to. */
    internal fun round5(metres: Float): Int = (metres * 20f).roundToInt() * 5

    /** Centimetres, up to the next 5, at least 5. */
    internal fun roundUp5(metres: Float): Int = max(1, kotlin.math.ceil(metres * 20f - 1e-4f).toInt()) * 5

    fun plan(spot: SofaSpot, heading: Float3, seed: Int = 200): DrawingPlan {
        val sketch = Sketch(seed)
        val r = sketch.random
        val hl = LENGTH / 2f
        val hd = DEPTH / 2f
        sketch.sheet(SheetFacing.Floor, Float3(0f, FLOOR_LIFT, 0f), RIM) {
            // The footprint, dashed, the pen going round it: near side, right, far side, left.
            val corners = listOf(InkPoint(-hl, -hd), InkPoint(hl, -hd), InkPoint(hl, hd), InkPoint(-hl, hd))
            corners.indices.forEach { edge ->
                val a = corners[edge]
                val b = corners[(edge + 1) % corners.size]
                val length = a.distanceTo(b)
                dashes(length).forEachIndexed { i, (from, to) ->
                    val p = InkPoint(a.x + (b.x - a.x) * from / length, a.y + (b.y - a.y) * from / length)
                    val q = InkPoint(a.x + (b.x - a.x) * to / length, a.y + (b.y - a.y) * to / length)
                    line(Sketch.handLine(p, q, 0.002f, r, 0.02f), INK, speed = 3f, pause = if (i == 0 && edge > 0) 0.06f else 0.015f)
                }
            }
            // Its length, along the near side and no longer than it: a tick at each end, the
            // line broken in the middle for "2 m".
            val y = -hd - DIM_OFFSET
            val gap = lengthGap()
            line(listOf(InkPoint(-hl, y + DIM_TICK), InkPoint(-hl, y - DIM_TICK)), INK * 0.85f, 0.8f, pause = 0.12f)
            line(Sketch.handLine(InkPoint(-hl, y), InkPoint(-gap, y), 0.003f, r), INK * 0.85f, 2.4f)
            text(LENGTH_LABEL, FLOOR_LABEL) { w -> InkPoint(-w.textBounds.centerX, y - w.textBounds.centerY) }
            line(Sketch.handLine(InkPoint(gap, y), InkPoint(hl, y), 0.003f, r), INK * 0.85f, 2.4f)
            line(listOf(InkPoint(hl, y + DIM_TICK), InkPoint(hl, y - DIM_TICK)), INK * 0.85f, 0.8f)
            // Its depth, just inside the right end, "90 cm" beside it.
            val x = hl - DEPTH_DIM_INSET
            line(listOf(InkPoint(x - DIM_TICK, -hd + 0.06f), InkPoint(x + DIM_TICK, -hd + 0.06f)), INK * 0.85f, 0.8f, pause = 0.12f)
            line(Sketch.handLine(InkPoint(x, -hd + 0.06f), InkPoint(x, hd - 0.06f), 0.003f, r), INK * 0.85f, 2.4f)
            line(listOf(InkPoint(x - DIM_TICK, hd - 0.06f), InkPoint(x + DIM_TICK, hd - 0.06f)), INK * 0.85f, 0.6f)
            text("90 cm", FLOOR_LABEL) { w -> InkPoint(x - 0.06f - w.textBounds.maxX, -w.textBounds.centerY) }
        }
        sketch.sheet(SheetFacing.Billboard, Float3(0f, VERDICT_HEIGHT, 0f), RIM * 0.9f) {
            text(verdict(spot.left, spot.right), VERDICT, Doodle.Underline, pause = 0.3f) { w ->
                InkPoint(-w.bounds.centerX, -w.bounds.minY)
            }
        }
        // The footprint's frame: +z back toward the phone.
        return sketch.build(spot.centre, atan2(-heading.x, -heading.z))
    }
}

// ── "How do I bleed this radiator?" ──────────────────────────────────────────────────────────

/**
 * A pen circle round the bleed screw, a curved arrow for an anticlockwise quarter turn, its
 * label, and the four steps beside it. Only the anchor is real — the spot the camera's centre
 * ray hit when the question was asked, the screw the user centred; the steps are scripted.
 *
 * In the surface's frame: origin on the screw, x to the right as the phone sees it, y up, +z out
 * of the radiator. The circle and the arrow are drawn on the surface; the words stand upright
 * and turn to face the phone.
 */
internal object RadiatorBleed {
    const val CIRCLE_RADIUS = 0.06f

    /** The arrow runs just outside the circle. */
    const val ARROW_RADIUS = 0.085f

    /** From a touch below the right of the screw, a quarter turn anticlockwise to its top. */
    val ARROW_FROM = (-5.0 * PI / 180.0).toFloat()
    val ARROW_SWEEP = (PI / 2.0).toFloat()

    /**
     * Everything drawn stays within this much of the screw, left and right: on a phone held
     * upright at arm's length the screw is rarely dead centre, and the screen is narrow.
     */
    const val HALF_SPAN = 0.25f

    /** How far from the screw the steps begin, to one side: clear of the circle. */
    const val STEPS_GAP = 0.09f

    /** A screw this close to the end of the wall ARCore found has its steps put on the other side. */
    const val EDGE_NEAR = 0.15f

    val STEPS = listOf("1. Heating off", "2. Cloth under", "3. Open till water", "4. Close")

    /** The ink stands this far off the radiator. */
    const val SURFACE_LIFT = 0.01f

    private const val INK = 0.0034f
    private const val RIM = 0.0014f
    private const val WORDS_LIFT = 0.02f
    private val WORDS = HandwritingStyle(
        blockWidth = 2f, capHeight = 0.021f, inkRadius = 0.002f, pointSpacing = 0.0012f,
        taperLength = 0.006f, minSeconds = 0.6f, maxSeconds = 0.95f,
    )
    private val SMALL = WORDS.copy(capHeight = 0.013f, inkRadius = 0.0016f, minSeconds = 0.15f, maxSeconds = 0.3f)

    /**
     * The circle round the screw: a little under two turns of a quick pen, so its two loops
     * overlap without lying on each other.
     */
    fun loops(random: Random): List<InkPoint> {
        val start = (0.55f + 0.1f * random.nextFloat()) * PI.toFloat()
        val turns = 1.85f
        val n = 120
        return List(n + 1) { i ->
            val t = i / n.toFloat()
            val a = start + t * turns * 2f * PI.toFloat()
            // The second loop a touch wider, and both a touch out of round.
            val r = CIRCLE_RADIUS * (0.97f + 0.06f * t + 0.025f * sin(3f * a + start))
            InkPoint(r * cos(a), r * sin(a))
        }
    }

    /**
     * A curved arrow round the screw: a shaft on a circle of [radius] from angle [from]
     * (radians, anticlockwise from the right) through [sweep] radians — positive turns it
     * anticlockwise — then a two-stroke head at its end, pointing the way it turns.
     */
    fun turnArrow(
        radius: Float = ARROW_RADIUS,
        from: Float = ARROW_FROM,
        sweep: Float = ARROW_SWEEP,
    ): List<List<InkPoint>> {
        val n = 36
        val shaft = List(n + 1) { i ->
            val t = i / n.toFloat()
            val a = from + sweep * t
            // A hand's arc: a little out of round, exact at both ends.
            val r = radius * (1f + 0.02f * sin(PI.toFloat() * t))
            InkPoint(r * cos(a), r * sin(a))
        }
        val tip = shaft.last()
        val end = from + sweep
        val turn = if (sweep >= 0f) 1f else -1f
        // Which way the pen was going at the tip: along the circle, the way it turns.
        val heading = atan2(turn * cos(end), -turn * sin(end))
        val barb = radius * 0.32f
        val spread = 0.5f
        fun barbEnd(sign: Float) = InkPoint(
            tip.x - barb * cos(heading + sign * spread),
            tip.y - barb * sin(heading + sign * spread),
        )
        return listOf(shaft, listOf(barbEnd(1f), tip, barbEnd(-1f)))
    }

    /**
     * Which side the steps go, +1 to the right as the phone sees it, -1 to the left.
     *
     * Away from the edge of the wall ARCore found, when the screw is within [EDGE_NEAR] of one
     * side of it and not the other: the steps go where the wall goes on. Otherwise the side the
     * phone stands toward, where they face it best and nothing of the radiator hides them;
     * square on, to the right.
     */
    fun stepsSide(spot: WallSpot, camera: Float3): Float {
        val nearLeft = spot.edgeLeft?.let { it < EDGE_NEAR } == true
        val nearRight = spot.edgeRight?.let { it < EDGE_NEAR } == true
        if (nearLeft != nearRight) return if (nearLeft) 1f else -1f
        val r = HangPicture.right(spot.normal)
        val along = (camera.x - spot.point.x) * r.x + (camera.z - spot.point.z) * r.z
        return if (along < -SIDE_DEADBAND) -1f else 1f
    }

    /** Where the arrow starts: on the side away from the steps, a quarter turn anticlockwise up to the top there. */
    fun arrowFrom(stepsSide: Float): Float = if (stepsSide > 0f) ARROW_FROM + PI.toFloat() - ARROW_SWEEP else ARROW_FROM

    /** The steps' style: [WORDS], wrapped so that each fits between [STEPS_GAP] and [HALF_SPAN]. */
    internal val STEPS_STYLE = WORDS.copy(blockWidth = HALF_SPAN - STEPS_GAP)

    fun plan(spot: WallSpot, camera: Float3, seed: Int = 4): DrawingPlan {
        val sketch = Sketch(seed)
        val side = stepsSide(spot, camera)
        val from = arrowFrom(side)
        sketch.sheet(SheetFacing.Wall, Float3(0f), RIM) {
            line(loops(sketch.random), INK, speed = 1.3f, pause = 0f)
            val (shaft, head) = turnArrow(from = from)
            line(shaft, INK, speed = 0.55f, pause = 0.12f)
            line(head, INK, speed = 0.35f)
        }
        // "¼ turn" by the middle of the arrow, outside it: the fraction drawn by hand, the font
        // has none. Away from the screw: right-aligned on the left, left-aligned on the right.
        val mid = from + ARROW_SWEEP / 2f
        val labelAt = Float3((ARROW_RADIUS + LABEL_OUT) * cos(mid), (ARROW_RADIUS + LABEL_OUT) * sin(mid), WORDS_LIFT)
        val turnWidth = HandwritingLayout.layout("turn", Doodle.None, WORDS).textBounds.width
        val x0 = if (side > 0f) -(FRACTION_WIDTH + turnWidth) else 0f
        sketch.sheet(SheetFacing.Billboard, labelAt, RIM) {
            text("1", SMALL, pause = 0.15f) { w -> InkPoint(x0 - w.textBounds.minX, 0.009f - w.textBounds.minY) }
            line(listOf(InkPoint(x0 + 0.003f, -0.003f), InkPoint(x0 + 0.018f, 0.024f)), WORDS.inkRadius * 0.9f, 0.4f)
            text("4", SMALL, pause = 0.04f) { w -> InkPoint(x0 + 0.015f - w.textBounds.minX, -0.003f - w.textBounds.minY) }
            text("turn", WORDS, pause = 0.08f) { w -> InkPoint(x0 + FRACTION_WIDTH - w.textBounds.minX, -0.003f - w.textBounds.minY) }
        }
        // The steps beside the screw, top to bottom, centred on its height; a step too long for
        // the room wraps onto a second line.
        val gap = STEPS_STYLE.capHeight * STEP_GAP
        val heights = STEPS.map { HandwritingLayout.layout(it, Doodle.None, STEPS_STYLE).textBounds.height }
        var top = (heights.sum() + gap * (STEPS.size - 1)) / 2f
        sketch.sheet(SheetFacing.Billboard, Float3(side * STEPS_GAP, 0f, WORDS_LIFT), RIM) {
            STEPS.forEachIndexed { i, step ->
                val y = top
                text(step, STEPS_STYLE, pause = if (i == 0) 0.25f else 0.15f) { w ->
                    InkPoint(if (side > 0f) -w.textBounds.minX else -w.textBounds.maxX, y - w.textBounds.maxY)
                }
                top -= heights[i] + gap
            }
        }
        val lift = Float3(spot.normal.x * SURFACE_LIFT, 0f, spot.normal.z * SURFACE_LIFT)
        return sketch.build(spot.point + lift, atan2(spot.normal.x, spot.normal.z))
    }

    private const val SIDE_DEADBAND = 0.1f
    private const val LABEL_OUT = 0.025f
    private const val FRACTION_WIDTH = 0.034f
    private const val STEP_GAP = 0.75f
}

private fun flat(v: Float3): Float3? {
    val l = sqrt(v.x * v.x + v.z * v.z)
    return if (l < 1e-4f) null else Float3(v.x / l, 0f, v.z / l)
}

private fun len(v: Float3) = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
