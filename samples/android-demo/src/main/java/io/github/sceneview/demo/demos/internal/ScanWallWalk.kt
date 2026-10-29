package io.github.sceneview.demo.demos.internal

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A synthetic turn round a room, as ARCore would report it (#4540 live walls): the phone stands
 * near the middle of a 4.2 × 4.4 m room and turns slowly on the spot, its feature points falling
 * on the floor and the walls in view, and each wall found as a vertical plane that grows as more
 * of it is seen. The front wall is found as two planes 2 cm apart — ARCore's habit on a long
 * plain wall — which the live walls must merge into one. The right wall's plane runs
 * [OVERGROWN_M] past what was seen of it at each end, as ARCore grows a plane past its points:
 * the live walls draw it only as far as its points go ([WallExtent]).
 *
 * The emulator cannot track, so this is the capture screen's QA take with walls (`record-walls`);
 * the JVM tests replay it too.
 */
object ScanWallWalk {
    const val FLOOR_Y = -1.4f
    const val CEILING_Y = 1.1f
    const val MIN_X = -2f
    const val MAX_X = 2.2f
    const val MIN_Z = -2.6f
    const val MAX_Z = 1.8f

    /** The whole turn, in seconds. */
    const val DURATION_S = 26f

    /** How far the phone turns over [DURATION_S], in radians: most of the way round, to the right. */
    private const val TURN_RAD = 5.2f
    private const val PITCH_RAD = -0.32f
    private const val POSE_HZ = 30
    private const val POINTS_EVERY = 15
    private const val PLANES_EVERY = 15
    private const val HALF_FOV_X = 0.5f
    private const val HALF_FOV_Y = 0.36f
    private const val RAYS_X = 18
    private const val RAYS_Y = 13
    private const val MAX_RANGE_M = 4.5f

    /** A wall piece shows as a plane once this much of it is seen. */
    private const val MIN_PLANE_M = 0.3f

    private const val FLOOR_ID = 1
    private const val CEILING_ID = 2

    /** The planes in view of the phone, ids from 10: front left and front right, then the others. */
    private const val FRONT_LEFT_ID = 10
    private const val FRONT_RIGHT_ID = 11
    private const val RIGHT_ID = 12
    private const val BACK_ID = 13
    private const val LEFT_ID = 14

    /** Where ARCore splits the front wall in two, and how far apart the halves sit. */
    private const val FRONT_SPLIT_X = 0.3f
    private const val FRONT_OFFSET_M = 0.02f

    private const val NANOS = 1_000_000_000L

    /** How far past its points the right wall's plane runs, in metres, until it meets the room's corners. */
    const val OVERGROWN_M = 0.9f

    /** The phone's pose [t] seconds into the turn: turning right, on a slow small circle. */
    fun pose(t: Float): DebugPose {
        val yaw = -TURN_RAD * (t / DURATION_S)
        val drift = t / DURATION_S * 2f * Math.PI.toFloat()
        val x = 0.15f + 0.25f * sin(drift)
        val z = -0.3f + 0.25f * (1f - cos(drift))
        val sy = sin(yaw / 2f)
        val cy = cos(yaw / 2f)
        val sp = sin(PITCH_RAD / 2f)
        val cp = cos(PITCH_RAD / 2f)
        // Yaw about +Y, then pitch about the phone's +X.
        return DebugPose(x, 0f, z, qx = cy * sp, qy = cp * sy, qz = -sy * sp, qw = cy * cp)
    }

    /** The take's events, in time order. */
    fun events(): List<ArDebugEvent> {
        val events = ArrayList<ArDebugEvent>()
        val seen = HashMap<Int, FloatArray>() // id → minAlong, maxAlong, maxY
        val floor = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val frames = (DURATION_S * POSE_HZ).toInt()
        for (frame in 0..frames) {
            val t = frame.toFloat() / POSE_HZ
            val nanos = (t * NANOS).toLong()
            val pose = pose(t)
            events += ArDebugEvent.CameraPose(nanos, pose)
            if (frame % POINTS_EVERY == 0) events += points(nanos, pose, frame, seen, floor)
            if (frame % PLANES_EVERY == 0) events += planes(nanos, seen, floor)
        }
        return events
    }

    private fun points(nanos: Long, pose: DebugPose, frame: Int, seen: HashMap<Int, FloatArray>, floor: FloatArray): ArDebugEvent {
        val positions = ArrayList<Float>()
        val colors = ArrayList<Int>()
        for (i in 0 until RAYS_X) for (j in 0 until RAYS_Y) {
            // A jitter per frame, so successive frames land between each other's points.
            val jx = ((frame * 7 + i * 13 + j * 3) % 11) / 11f
            val jy = ((frame * 5 + i * 3 + j * 17) % 13) / 13f
            val a = -HALF_FOV_X + 2f * HALF_FOV_X * (i + jx) / RAYS_X
            val b = -HALF_FOV_Y + 2f * HALF_FOV_Y * (j + jy) / RAYS_Y
            val local = normalised(tan(a), tan(b), -1f)
            val d = pose.rotate(local[0], local[1], local[2])
            val hit = hitRoom(pose.x, pose.y, pose.z, d.x, d.y, d.z) ?: continue
            if (hit.t > MAX_RANGE_M) continue
            val x = pose.x + d.x * hit.t
            val y = pose.y + d.y * hit.t
            val z = pose.z + d.z * hit.t
            positions += x
            positions += y
            positions += z
            colors += colorOf(hit.surface, x, y, z)
            record(hit.surface, x, y, z, seen, floor)
        }
        return ArDebugEvent.Points(nanos, positions.toFloatArray(), null, colors.toIntArray())
    }

    private fun record(surface: Int, x: Float, y: Float, z: Float, seen: HashMap<Int, FloatArray>, floor: FloatArray) {
        if (surface == FLOOR_ID) {
            floor[0] = min(floor[0], x)
            floor[1] = min(floor[1], z)
            floor[2] = max(floor[2], x)
            floor[3] = max(floor[3], z)
            return
        }
        if (surface == CEILING_ID) return
        val along = if (surface == FRONT_LEFT_ID || surface == FRONT_RIGHT_ID || surface == BACK_ID) x else z
        val s = seen.getOrPut(surface) { floatArrayOf(along, along, y) }
        s[0] = min(s[0], along)
        s[1] = max(s[1], along)
        s[2] = max(s[2], y)
    }

    private fun planes(nanos: Long, seen: HashMap<Int, FloatArray>, floor: FloatArray): List<ArDebugEvent> {
        val out = ArrayList<ArDebugEvent>()
        if (floor[2] - floor[0] > MIN_PLANE_M && floor[3] - floor[1] > MIN_PLANE_M) {
            out += ArDebugEvent.Plane(
                nanos, FLOOR_ID, DebugPlaneKind.Floor,
                floatArrayOf(
                    floor[0], FLOOR_Y, floor[1], floor[2], FLOOR_Y, floor[1],
                    floor[2], FLOOR_Y, floor[3], floor[0], FLOOR_Y, floor[3],
                ),
            )
        }
        for ((id, s) in seen) {
            if (s[1] - s[0] < MIN_PLANE_M) continue
            val bottom = FLOOR_Y + 0.15f
            val top = min(s[2], CEILING_Y - 0.1f)
            if (top - bottom < MIN_PLANE_M) continue
            val polygon = when (id) {
                FRONT_LEFT_ID -> wallAlongX(MIN_Z, s[0], s[1], bottom, top)
                FRONT_RIGHT_ID -> wallAlongX(MIN_Z + FRONT_OFFSET_M, s[0], s[1], bottom, top)
                BACK_ID -> wallAlongX(MAX_Z, s[0], s[1], bottom, top)
                RIGHT_ID -> wallAlongZ(MAX_X, max(MIN_Z, s[0] - OVERGROWN_M), min(MAX_Z, s[1] + OVERGROWN_M), bottom, top)
                else -> wallAlongZ(MIN_X, s[0], s[1], bottom, top)
            }
            out += ArDebugEvent.Plane(nanos, id, DebugPlaneKind.Wall, polygon)
        }
        return out
    }

    private fun wallAlongX(z: Float, x0: Float, x1: Float, y0: Float, y1: Float) =
        floatArrayOf(x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z)

    private fun wallAlongZ(x: Float, z0: Float, z1: Float, y0: Float, y1: Float) =
        floatArrayOf(x, y0, z0, x, y0, z1, x, y1, z1, x, y1, z0)

    private class Hit(val t: Float, val surface: Int)

    private fun hitRoom(ox: Float, oy: Float, oz: Float, dx: Float, dy: Float, dz: Float): Hit? {
        var t = Float.MAX_VALUE
        var surface = -1
        fun take(candidate: Float, id: Int) {
            if (candidate > 0f && candidate < t) {
                t = candidate
                surface = id
            }
        }
        if (dy < 0f) take((FLOOR_Y - oy) / dy, FLOOR_ID) else if (dy > 0f) take((CEILING_Y - oy) / dy, CEILING_ID)
        if (dx > 0f) take((MAX_X - ox) / dx, RIGHT_ID) else if (dx < 0f) take((MIN_X - ox) / dx, LEFT_ID)
        if (dz < 0f) {
            val tz = (MIN_Z - oz) / dz
            take(tz, if (ox + dx * tz < FRONT_SPLIT_X) FRONT_LEFT_ID else FRONT_RIGHT_ID)
        } else if (dz > 0f) {
            take((MAX_Z - oz) / dz, BACK_ID)
        }
        return if (surface < 0) null else Hit(t, surface)
    }

    /** Parquet on the floor, off-white walls with a little shade: what a camera would colour them. */
    private fun colorOf(surface: Int, x: Float, y: Float, z: Float): Int = when (surface) {
        FLOOR_ID -> if (((x * 6f).toInt() + (z * 1.5f).toInt()) % 2 == 0) 0xFFA27B52.toInt() else 0xFF8C6844.toInt()
        CEILING_ID -> 0xFFEDEBE6.toInt()
        else -> {
            val shade = (((y - FLOOR_Y) / (CEILING_Y - FLOOR_Y)) * 18f).toInt().coerceIn(0, 18)
            val v = 0xC8 + shade
            (0xFF shl 24) or (v shl 16) or ((v - 4) shl 8) or (v - 12)
        }
    }

    private fun normalised(x: Float, y: Float, z: Float): FloatArray {
        val l = sqrt(x * x + y * y + z * z)
        return floatArrayOf(x / l, y / l, z / l)
    }
}
