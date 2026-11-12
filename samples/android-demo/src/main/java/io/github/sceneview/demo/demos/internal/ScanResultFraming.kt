package io.github.sceneview.demo.demos.internal

import io.github.sceneview.math.Position
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the scan result looks from (Scan redesign, PR 1): the model's own box, turned square to
 * its walls, so a room stands straight on the screen; seen from outside, at a three-quarter on its
 * front; an object on its own box, never widened to a room's size. The view never moves by itself.
 *
 * The box is read in a frame turned [yawDegrees] about Y — [RoomMeasure]'s convention, from +X
 * towards +Z — where `a` runs along the walls and `b` across them: [local] is
 * `[minA, minY, minB, maxA, maxY, maxB]`. Turning the whole scene about Y changes nothing in the
 * picture, so the box is fitted in that frame ([ArDebugFraming.fit]) and the pose turned back
 * into the world: the azimuth less the yaw, the pivot turned with it.
 *
 * [frontDegrees] is the side the view opens on, an azimuth of the turned frame (0, 90, 180, 270:
 * the eye towards +b, +a, -b, -a), and [turn] the way its three-quarter turns off it, +1 or -1.
 */
class ScanResultFrame(
    val yawDegrees: Float,
    val local: FloatArray,
    val frontDegrees: Float,
    val turn: Float,
) {
    init {
        require(local.size == BOX) { "a box is six numbers" }
    }

    /**
     * The pose that shows the box at [elevationDegrees], [offsetDegrees] off the front, whole in
     * the [insets] of a view of [aspect] (width / height) through a lens of [verticalFovDegrees].
     */
    fun pose(
        elevationDegrees: Float,
        offsetDegrees: Float,
        verticalFovDegrees: Double,
        aspect: Float,
        insets: FramingInsets,
    ): OrbitPose = toWorld(
        ArDebugFraming.fit(
            bounds = local,
            azimuthDegrees = frontDegrees + turn * offsetDegrees,
            elevationDegrees = elevationDegrees,
            verticalFovDegrees = verticalFovDegrees,
            aspect = aspect,
            insets = insets,
        ),
    )

    /** [pose], read in the turned frame, in the world: the same picture. */
    fun toWorld(pose: OrbitPose): OrbitPose {
        val (x, z) = world(pose.target.x, pose.target.z)
        return pose.copy(
            target = Position(x, pose.target.y, z),
            azimuthDegrees = normalized(pose.azimuthDegrees - yawDegrees),
        )
    }

    /** The world box holding the turned one: where the gestures keep the pivot, and the eye out of. */
    val worldBounds: FloatArray by lazy {
        val out = floatArrayOf(Float.MAX_VALUE, local[1], Float.MAX_VALUE, -Float.MAX_VALUE, local[4], -Float.MAX_VALUE)
        for (corner in 0 until CORNERS) {
            val (x, z) = world(local[if (corner and 1 == 0) 0 else 3], local[if (corner and 2 == 0) 2 else 5])
            out[0] = min(out[0], x)
            out[2] = min(out[2], z)
            out[3] = max(out[3], x)
            out[5] = max(out[5], z)
        }
        out
    }

    /**
     * What the gestures may do once [homes] are the views offered: the pivot in the box, the eye
     * outside it and above its floor — never inside the room — and a pinch out to
     * [ArDebugFraming.MAX_ZOOM_OUT] past the furthest of them. Never widened to a room's size.
     */
    fun limits(vararg homes: OrbitPose): OrbitLimits = OrbitLimits(
        maxDistance = ((homes.maxOfOrNull { it.distance } ?: 1f) * ArDebugFraming.MAX_ZOOM_OUT)
            .coerceIn(ArDebugFraming.MIN_DISTANCE, ArDebugFraming.MAX_DISTANCE),
        bounds = worldBounds,
        floorY = local[1],
    )

    /**
     * The same frame with its box no higher than [heightM] over its floor: a room cut for the
     * dollhouse ([ScanResultCut]) is framed, and its gestures kept, on what is drawn of it.
     */
    fun cappedAt(heightM: Float): ScanResultFrame {
        if (local[4] - local[1] <= heightM) return this
        return ScanResultFrame(yawDegrees, local.copyOf().also { it[4] = local[1] + heightM }, frontDegrees, turn)
    }

    /**
     * This frame opened on the side a room shows largest from, square on, at [elevationDegrees]
     * in the [insets] of a view of [aspect]: its own [frontDegrees], unless a side beside it
     * stands the camera under [SIDE_GAIN] of the distance — a long room seen down its length is
     * a sliver across a tall screen, the same room seen along it fills the screen's width.
     */
    fun widestSide(
        elevationDegrees: Float,
        verticalFovDegrees: Double,
        aspect: Float,
        insets: FramingInsets,
    ): ScanResultFrame {
        fun distance(side: Float) =
            ArDebugFraming.fit(local, side, elevationDegrees, verticalFovDegrees, aspect, insets).distance
        val own = distance(frontDegrees)
        val (side, best) = listOf(frontDegrees + QUARTER, frontDegrees + 3 * QUARTER)
            .map { normalized(it) to distance(it) }
            .minBy { it.second }
        return if (best < own * SIDE_GAIN) ScanResultFrame(yawDegrees, local, side, turn) else this
    }

    /** The box's middle on the floor plan, in the world: `x` to `z`. */
    val worldCentre: Pair<Float, Float> get() = world((local[0] + local[3]) / 2f, (local[2] + local[5]) / 2f)

    private fun world(a: Float, b: Float): Pair<Float, Float> {
        val yaw = Math.toRadians(yawDegrees.toDouble())
        val c = cos(yaw).toFloat()
        val s = sin(yaw).toFloat()
        return (a * c - b * s) to (a * s + b * c)
    }

    companion object {
        /** A model whose geometry cannot be read: its stored box, unturned, the front on +Z. */
        fun unturned(bounds: FloatArray): ScanResultFrame = ScanResultFrame(0f, bounds.copyOf(), 0f, 1f)

        private fun normalized(degrees: Float): Float = ((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN

        /** How much closer a side beside the front must bring the camera to open on instead. */
        const val SIDE_GAIN = 0.85f

        private const val BOX = 6
        private const val CORNERS = 4
        private const val FULL_TURN = 360f
        private const val QUARTER = 90f
    }
}

/** How [ScanResultFrame] is read off a model's vertices, pure so a test holds it. */
object ScanResultFraming {
    /** An object's three-quarter: low, as a product is shot, its top still seen. */
    const val OBJECT_ELEVATION = 28f

    /** A room's: from outside and above its near walls, cut low, down onto its floor and its depth. */
    const val ROOM_ELEVATION = 38f

    /** How far off the front the view opens: [ArDebugFraming.FRONT_OFFSET_DEGREES], a three-quarter. */
    const val THREE_QUARTER = ArDebugFraming.FRONT_OFFSET_DEGREES

    /**
     * The pitches a drag turns the result between: never level with the floor, where a scan's
     * open walls show, nor past the plan's look down.
     */
    val PITCHES: ClosedFloatingPointRange<Float> = MIN_PITCH..ArDebugFraming.MAP_ELEVATION

    /** The vertices read at most: a room's mesh has millions; its box and its walls need far fewer. */
    const val MAX_SAMPLES = 120_000

    /**
     * A normal leaning less than this off the horizontal (its |y|) belongs to a wall or a side:
     * it says which way the walls run. Floors, ceilings and table tops say nothing of it.
     */
    const val WALL_NORMAL_Y = 0.5f

    /** The elevation the result opens at for [scope]. */
    fun elevation(scope: ScanScope?): Float = if (scope == ScanScope.Object) OBJECT_ELEVATION else ROOM_ELEVATION

    /**
     * How far off the front the result opens for [scope]: an object at a three-quarter, as a
     * product is shot; a room square on its walls, standing straight on the screen.
     */
    fun offset(scope: ScanScope?): Float = if (scope == ScanScope.Object) THREE_QUARTER else 0f

    /**
     * The weakest agreement of the walls' normals on one way — the folded mean's length over
     * their weight, 0 to 1 — that [of] turns the box by; under it, the walls say nothing (a
     * garden, a hedge, a round object) and the footprint's long axis does ([footprintYawDegrees]).
     */
    const val MIN_WALL_COHERENCE = 0.3f

    /** The frame of the `.glb` the result shows; `null` for one this does not read, or an empty one. */
    fun read(glb: ByteArray, maxSamples: Int = MAX_SAMPLES): ScanResultFrame? =
        sample(glb, maxSamples)?.let { (positions, normals) -> of(positions, normals) }

    /**
     * The frame of [cloud], a scan's surfels, read as a model's is: what the result opens on while
     * its model is still built, so the model then takes its place in the same frame. From at most
     * [maxSamples] of them, evenly spread; `null` without normals or without a surfel.
     */
    fun of(cloud: DenseCloud, maxSamples: Int = MAX_SAMPLES): ScanResultFrame? {
        val from = cloud.normals ?: return null
        val n = cloud.count
        if (n == 0) return null
        val step = (n + maxSamples - 1) / maxSamples.coerceAtLeast(1)
        val count = (n + step - 1) / step
        val positions = FloatArray(count * 3)
        val normals = FloatArray(count * 3)
        for (i in 0 until count) {
            val v = i * step
            for (k in 0 until 3) {
                positions[i * 3 + k] = cloud.positions[v * 3 + k]
                normals[i * 3 + k] = from[v * 3 + k]
            }
        }
        return of(positions, normals)
    }

    /**
     * The frame of the vertices [positions] (flat xyz) with their [normals]: turned square to the
     * walls ([yawDegrees]), its box from the [tail] quantile to 1 - [tail] on each turned axis
     * ([RerunResultView.FRAMING_TAIL]: pieces seen through a window do not shrink the room), its
     * front the side most of the walls face ([front]). `null` without a vertex.
     */
    fun of(positions: FloatArray, normals: FloatArray, tail: Float = RerunResultView.FRAMING_TAIL): ScanResultFrame? {
        val n = min(positions.size, normals.size) / 3
        if (n == 0) return null
        val yaw = if (wallCoherence(normals) >= MIN_WALL_COHERENCE) yawDegrees(normals) else footprintYawDegrees(positions)
        val radians = Math.toRadians(yaw.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        val a = FloatArray(n)
        val y = FloatArray(n)
        val b = FloatArray(n)
        for (v in 0 until n) {
            val x = positions[v * 3]
            val z = positions[v * 3 + 2]
            a[v] = x * c + z * s
            y[v] = positions[v * 3 + 1]
            b[v] = -x * s + z * c
        }
        val local = FloatArray(6)
        for ((axis, values) in listOf(a, y, b).withIndex()) {
            values.sort()
            local[axis] = values[((n - 1) * tail).toInt()]
            local[axis + 3] = values[((n - 1) * (1f - tail) + 0.5f).toInt().coerceAtMost(n - 1)]
        }
        val (front, turn) = front(normals, yaw)
        return ScanResultFrame(yaw, local, front, turn)
    }

    /**
     * Which way the walls run, degrees in (-45, 45]: the horizontal normals' angles folded four
     * times — a wall and the one across, a wall and the one beside it, all say the same — and
     * averaged, each weighted by how horizontal it is. 0 when nothing stands up.
     */
    fun yawDegrees(normals: FloatArray): Float {
        var sin4 = 0.0
        var cos4 = 0.0
        for (v in 0 until normals.size / 3) {
            val nx = normals[v * 3]
            val ny = normals[v * 3 + 1]
            val nz = normals[v * 3 + 2]
            if (abs(ny) > WALL_NORMAL_Y) continue
            val weight = (nx * nx + nz * nz).toDouble()
            if (weight < MIN_WEIGHT) continue
            val angle = FOLD * atan2(nz.toDouble(), nx.toDouble())
            sin4 += weight * sin(angle)
            cos4 += weight * cos(angle)
        }
        if (sin4 * sin4 + cos4 * cos4 < MIN_WEIGHT) return 0f
        return Math.toDegrees(atan2(sin4, cos4) / FOLD).toFloat()
    }

    /** How much the walls' normals agree on one way ([MIN_WALL_COHERENCE]): 0 with nothing standing. */
    fun wallCoherence(normals: FloatArray): Float {
        var sin4 = 0.0
        var cos4 = 0.0
        var total = 0.0
        for (v in 0 until normals.size / 3) {
            val nx = normals[v * 3]
            val nz = normals[v * 3 + 2]
            if (abs(normals[v * 3 + 1]) > WALL_NORMAL_Y) continue
            val weight = (nx * nx + nz * nz).toDouble()
            if (weight < MIN_WEIGHT) continue
            val angle = FOLD * atan2(nz.toDouble(), nx.toDouble())
            sin4 += weight * sin(angle)
            cos4 += weight * cos(angle)
            total += weight
        }
        return if (total < MIN_WEIGHT) 0f else (sqrt(sin4 * sin4 + cos4 * cos4) / total).toFloat()
    }

    /**
     * The way the footprint of [positions] runs, degrees in (-45, 45]: its long axis on the floor
     * plan (the principal axis of x and z), folded a quarter turn as [yawDegrees] is. 0 for a
     * footprint no longer one way than the other ([MIN_ELONGATION]).
     */
    fun footprintYawDegrees(positions: FloatArray): Float {
        val n = positions.size / 3
        if (n == 0) return 0f
        var mx = 0.0
        var mz = 0.0
        for (v in 0 until n) {
            mx += positions[v * 3]
            mz += positions[v * 3 + 2]
        }
        mx /= n
        mz /= n
        var xx = 0.0
        var zz = 0.0
        var xz = 0.0
        for (v in 0 until n) {
            val dx = positions[v * 3] - mx
            val dz = positions[v * 3 + 2] - mz
            xx += dx * dx
            zz += dz * dz
            xz += dx * dz
        }
        val spread = xx + zz
        val skew = sqrt((xx - zz) * (xx - zz) + 4 * xz * xz)
        if (spread < MIN_WEIGHT || skew / spread < MIN_ELONGATION) return 0f
        val axis = Math.toDegrees(0.5 * atan2(2 * xz, xx - zz)).toFloat()
        return ((axis + QUARTER / 2) % QUARTER + QUARTER) % QUARTER - QUARTER / 2
    }

    /**
     * The side the view opens on in the frame turned [yawDegrees] (0, 90, 180 or 270), and the
     * way its three-quarter turns: towards the eye the most surface faces, less what faces the eye
     * across from it (`faced[s] - faced[(s + 2) % 4]`) — a room's walls face in,
     * so its fullest wall stands at the back, the near one turned away; an object's faces out, so
     * the side it was scanned from is in front — then towards the better of the two beside it.
     */
    fun front(normals: FloatArray, yawDegrees: Float): Pair<Float, Float> {
        val radians = Math.toRadians(yawDegrees.toDouble())
        val c = cos(radians).toFloat()
        val s = sin(radians).toFloat()
        // Seen from the eye at azimuth 0, 90, 180, 270 of the turned frame: +b, +a, -b, -a.
        val faced = DoubleArray(SIDES)
        for (v in 0 until normals.size / 3) {
            val nx = normals[v * 3]
            val nz = normals[v * 3 + 2]
            if (abs(normals[v * 3 + 1]) > WALL_NORMAL_Y) continue
            val na = nx * c + nz * s
            val nb = -nx * s + nz * c
            faced[0] += max(0f, nb)
            faced[1] += max(0f, na)
            faced[2] += max(0f, -nb)
            faced[3] += max(0f, -na)
        }
        // The side that faces the eye more than the one across faces away: a long side seen from
        // both of its faces says nothing of where the scan was taken, however large it is.
        val side = faced.indices.maxByOrNull { faced[it] - faced[(it + 2) % SIDES] } ?: 0
        val turn = if (faced[(side + 1) % SIDES] >= faced[(side + SIDES - 1) % SIDES]) 1f else -1f
        return side * QUARTER to turn
    }

    /**
     * The positions and normals of at most [maxSamples] of the vertices of a `.glb` [RerunMeshGlb]
     * wrote, evenly spread through it; `null` for any other file. Read in place: the colours and
     * the triangles, most of a model's bytes, are never copied.
     */
    @Suppress("ReturnCount")
    fun sample(glb: ByteArray, maxSamples: Int = MAX_SAMPLES): Pair<FloatArray, FloatArray>? = runCatching {
        val buffer = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
        if (glb.size < HEADER + CHUNK_HEADER || buffer.getInt(0) != GLB_MAGIC) return null
        val jsonLength = buffer.getInt(HEADER)
        val json = String(glb, HEADER + CHUNK_HEADER, jsonLength, Charsets.UTF_8)
        val binStart = HEADER + CHUNK_HEADER + jsonLength + CHUNK_HEADER
        val root = lenient.parseToJsonElement(json) as JsonObject
        val accessors = root["accessors"] as JsonArray
        val views = root["bufferViews"] as JsonArray
        fun Any?.int(key: String) = ((this as JsonObject)[key] as? JsonPrimitive)?.intOrNull ?: 0
        fun offsetOf(accessor: Int): Int = binStart + views[accessors[accessor].int("bufferView")].int("byteOffset")
        val n = accessors[0].int("count")
        if (n <= 0 || accessors[1].int("count") != n) return null
        val step = (n + maxSamples - 1) / maxSamples.coerceAtLeast(1)
        val count = (n + step - 1) / step
        val positionAt = offsetOf(0)
        val normalAt = offsetOf(1)
        val positions = FloatArray(count * 3)
        val normals = FloatArray(count * 3)
        for (i in 0 until count) {
            val v = i * step
            for (k in 0 until 3) {
                positions[i * 3 + k] = buffer.getFloat(positionAt + (v * 3 + k) * Float.SIZE_BYTES)
                normals[i * 3 + k] = buffer.getFloat(normalAt + (v * 3 + k) * Float.SIZE_BYTES)
            }
        }
        positions to normals
    }.getOrNull()

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val MIN_PITCH = 15f
    private const val FOLD = 4.0
    private const val SIDES = 4
    private const val QUARTER = 90f
    private const val MIN_WEIGHT = 1e-9
    private const val MIN_ELONGATION = 0.1
    private const val HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val GLB_MAGIC = 0x46546C67
}
