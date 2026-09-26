package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * The data behind the Rerun demo's in-app 3D view (#3950): everything ARCore told the app about
 * the session, on one timeline, so the view can draw any instant of it — the live one, or one
 * scrubbed back to.
 *
 * Pure Kotlin, no ARCore and no Filament: the live recorder (`ArDebugRecorder` in the demo), the
 * QA fixture (a JSON-lines log in the Rerun bridge's own wire format, see [parseArDebugLog]) and
 * the tests all feed the same [ArDebugTrace], and the geometry is built from what it returns
 * ([ArDebugFrame]), so what the tests pin is what the screen draws.
 */

/** A rigid pose: translation plus a unit quaternion, ARCore's `Pose` without the native object. */
data class DebugPose(
    val x: Float,
    val y: Float,
    val z: Float,
    val qx: Float = 0f,
    val qy: Float = 0f,
    val qz: Float = 0f,
    val qw: Float = 1f,
) {
    /** Rotates the local vector ([lx], [ly], [lz]) by this pose's rotation, then translates it. */
    fun transform(lx: Float, ly: Float, lz: Float): Vec3 {
        val r = rotate(lx, ly, lz)
        return Vec3(r.x + x, r.y + y, r.z + z)
    }

    /** Rotates the local vector ([lx], [ly], [lz]) by this pose's rotation only. */
    fun rotate(lx: Float, ly: Float, lz: Float): Vec3 {
        // v' = v + 2w(q × v) + 2 q × (q × v), for a unit quaternion (qx, qy, qz, qw).
        val tx = 2f * (qy * lz - qz * ly)
        val ty = 2f * (qz * lx - qx * lz)
        val tz = 2f * (qx * ly - qy * lx)
        return Vec3(
            lx + qw * tx + (qy * tz - qz * ty),
            ly + qw * ty + (qz * tx - qx * tz),
            lz + qw * tz + (qx * ty - qy * tx),
        )
    }

    val position: Vec3 get() = Vec3(x, y, z)

    /** Where the camera looks: ARCore's cameras look down their local -Z. */
    val forward: Vec3 get() = rotate(0f, 0f, -1f)
}

/** A plain 3-vector. Kept local so this file needs nothing beyond the standard library. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(x * x + y * y + z * z)
    fun normalized(): Vec3 {
        val l = length()
        return if (l < 1e-9f) this else Vec3(x / l, y / l, z / l)
    }

    companion object {
        val Zero = Vec3(0f, 0f, 0f)
        val Up = Vec3(0f, 1f, 0f)
    }
}

/** What kind of surface ARCore found — each is tinted differently in the view. */
enum class DebugPlaneKind(val wireName: String) {
    Floor("horizontal_upward"),
    Ceiling("horizontal_downward"),
    Wall("vertical"),
    Unknown("unknown");

    companion object {
        fun ofWire(name: String?): DebugPlaneKind = entries.firstOrNull { it.wireName == name } ?: Unknown
    }
}

/**
 * One detected plane at one instant: its boundary as a **world-space** polygon, flat
 * `[x0,y0,z0, x1,y1,z1, …]` — the form the Rerun bridge already emits. ARCore's plane polygons
 * are convex, which is what lets the view fill them as a fan.
 */
class DebugPlane(val id: Int, val kind: DebugPlaneKind, val polygon: FloatArray) {
    val vertexCount: Int get() = polygon.size / 3
}

/** A placed anchor, and the model the demo put on it. */
data class DebugAnchor(val id: Int, val pose: DebugPose, val placedAt: Float)

/**
 * Everything the 3D view draws at one instant [time] of the trace.
 *
 * @property trail camera positions up to [time], oldest first, flat xyz.
 * @property camera the camera pose at [time], `null` before the first one.
 * @property mapPoints every feature point discovered up to [time], flat xyz — the "map".
 * @property livePoints the points ARCore was tracking at [time], flat xyz, drawn brighter.
 * @property keyframes past camera poses spaced along the trail, drawn as faint frustums — where
 *   the phone looked from, the way the Rerun viewer shows a camera's history.
 */
class ArDebugFrame(
    val time: Float,
    val trail: FloatArray,
    val camera: DebugPose?,
    val mapPoints: FloatArray,
    val livePoints: FloatArray,
    val planes: List<DebugPlane>,
    val anchors: List<DebugAnchor>,
    val keyframes: List<DebugPose> = emptyList(),
    /** Which observation [livePoints] came from, `-1` for none: a cheap "did they change" key. */
    val liveKey: Int = -1,
) {
    val trailLength: Int get() = trail.size / 3
    val mapPointCount: Int get() = mapPoints.size / 3
}

/**
 * The session timeline. Append-only; times are seconds since the first event, derived from the
 * event timestamps (ARCore's `Frame.timestamp` nanoseconds, or the `t` of a wire-format log).
 *
 * Not thread-safe by design: the recorder writes from the AR frame callback and the view reads
 * from its own frame callback, and both run on the main thread.
 *
 * Bounded so a long session cannot grow it without limit: poses are thinned by motion
 * ([POSE_MIN_STEP_M], [POSE_MIN_TURN]) and capped ([MAX_POSES], halved when full); feature points
 * are merged into a [POINT_VOXEL_M] voxel map capped at [MAX_MAP_POINTS]; planes and anchors keep
 * one snapshot per change.
 */
class ArDebugTrace {
    private var originNanos = Long.MIN_VALUE

    private var poseTimes = FloatArray(256)
    private var poses = ArrayList<DebugPose>(256)

    private var pointXyz = FloatArray(3 * 1024)
    private var pointFirstSeen = FloatArray(1024)
    private var pointCount = 0
    private val voxelIndex = HashMap<Long, Int>()

    /** Each point-cloud observation: when, and which map points it saw. */
    private val observationTimes = ArrayList<Float>()
    private val observations = ArrayList<IntArray>()

    /** Per plane id, its snapshots in time order; an empty polygon marks the plane gone. */
    private val planeHistory = LinkedHashMap<Int, MutableList<Pair<Float, DebugPlane>>>()
    private val anchorHistory = LinkedHashMap<Int, MutableList<Pair<Float, DebugAnchor>>>()

    /** Bumped on every change, so a reader can tell "same trace" without comparing contents. */
    var version: Int = 0
        private set

    /** Seconds from the first event to the last one. */
    var duration: Float = 0f
        private set

    val poseCount: Int get() = poses.size
    val mapPointCount: Int get() = pointCount
    val isEmpty: Boolean get() = poses.isEmpty() && pointCount == 0 && planeHistory.isEmpty()

    /** Seconds since the first event for an event at [nanos]; the first call sets the origin. */
    fun secondsOf(nanos: Long): Float {
        if (originNanos == Long.MIN_VALUE) originNanos = nanos
        return ((nanos - originNanos).coerceAtLeast(0L) / 1e9).toFloat()
    }

    private fun touch(time: Float) {
        if (time > duration) duration = time
        version++
    }

    /**
     * Adds a camera pose. A pose within [POSE_MIN_STEP_M] and [POSE_MIN_TURN] of the last kept
     * one only moves the time forward: the trail does not need it, and at 30 Hz a phone held
     * still would otherwise fill the cap in minutes.
     */
    fun addPose(nanos: Long, pose: DebugPose) {
        val t = secondsOf(nanos)
        val last = poses.lastOrNull()
        if (last != null && t <= poseTimes[poses.size - 1] + 1e-6f) {
            // Out-of-order or duplicate timestamp: keep the newest pose, never go back in time.
            poses[poses.size - 1] = pose
            touch(t)
            return
        }
        if (last != null && !movedEnough(last, pose)) {
            touch(t)
            return
        }
        if (poses.size >= MAX_POSES) thinPoses()
        if (poses.size == poseTimes.size) poseTimes = poseTimes.copyOf(poseTimes.size * 2)
        poseTimes[poses.size] = t
        poses.add(pose)
        touch(t)
    }

    private fun movedEnough(a: DebugPose, b: DebugPose): Boolean {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        if (dx * dx + dy * dy + dz * dz >= POSE_MIN_STEP_M * POSE_MIN_STEP_M) return true
        // |dot| of two unit quaternions is cos(half the angle between them).
        val dot = abs(a.qx * b.qx + a.qy * b.qy + a.qz * b.qz + a.qw * b.qw)
        return dot < POSE_MIN_TURN
    }

    /** Drops every other pose (keeping the last), halving the trace at the cap. */
    private fun thinPoses() {
        val keptPoses = ArrayList<DebugPose>(poses.size / 2 + 1)
        val keptTimes = FloatArray(poseTimes.size)
        var n = 0
        for (i in poses.indices) {
            if (i % 2 == 0 || i == poses.lastIndex) {
                keptTimes[n++] = poseTimes[i]
                keptPoses.add(poses[i])
            }
        }
        poses = keptPoses
        poseTimes = keptTimes
    }

    /**
     * Adds one point-cloud observation: flat xyz [positions], optionally with ARCore's per-point
     * [confidences] (points under [MIN_POINT_CONFIDENCE] are dropped). Each point is merged into
     * the voxel map — a point seen again lands in the voxel it already has — and the observation
     * remembers which voxels it saw, which is what "live points" means at any instant.
     */
    @Suppress("LoopWithTooManyJumpStatements") // low-confidence and non-finite points skip early
    fun addPoints(nanos: Long, positions: FloatArray, confidences: FloatArray? = null) {
        val t = secondsOf(nanos)
        val count = positions.size / 3
        val seen = IntArray(count)
        var seenCount = 0
        for (i in 0 until count) {
            if (confidences != null && i < confidences.size && confidences[i] < MIN_POINT_CONFIDENCE) continue
            val x = positions[i * 3]
            val y = positions[i * 3 + 1]
            val z = positions[i * 3 + 2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            val key = voxelKey(x, y, z)
            val index = voxelIndex[key] ?: run {
                if (pointCount >= MAX_MAP_POINTS) return@run -1
                if (pointCount == pointFirstSeen.size) {
                    pointFirstSeen = pointFirstSeen.copyOf(pointFirstSeen.size * 2)
                    pointXyz = pointXyz.copyOf(pointXyz.size * 2)
                }
                pointXyz[pointCount * 3] = x
                pointXyz[pointCount * 3 + 1] = y
                pointXyz[pointCount * 3 + 2] = z
                pointFirstSeen[pointCount] = t
                voxelIndex[key] = pointCount
                pointCount++
                pointCount - 1
            }
            if (index >= 0) seen[seenCount++] = index
        }
        if (observations.size >= MAX_OBSERVATIONS) {
            // Keep the timeline but forget what the oldest half of the observations saw.
            for (i in 0 until observations.size / 2) observations[i] = EMPTY_INTS
        }
        observationTimes.add(t)
        observations.add(seen.copyOf(seenCount))
        touch(t)
    }

    /** Adds a snapshot of plane [id]. An empty [polygon] records that the plane went away. */
    fun addPlane(nanos: Long, id: Int, kind: DebugPlaneKind, polygon: FloatArray) {
        val t = secondsOf(nanos)
        val history = planeHistory.getOrPut(id) { mutableListOf() }
        val last = history.lastOrNull()?.second
        if (last != null && last.kind == kind && last.polygon.contentEquals(polygon)) return
        history.add(t to DebugPlane(id, kind, polygon))
        touch(t)
    }

    /** Adds (or moves) anchor [id]. */
    fun addAnchor(nanos: Long, id: Int, pose: DebugPose) {
        val t = secondsOf(nanos)
        val history = anchorHistory.getOrPut(id) { mutableListOf() }
        val last = history.lastOrNull()?.second
        if (last != null && last.pose == pose) return
        history.add(t to DebugAnchor(id, pose, placedAt = history.firstOrNull()?.first ?: t))
        touch(t)
    }

    /** The whole scene as it stood at [time] (clamped to the trace). */
    fun frameAt(time: Float): ArDebugFrame {
        val t = time.coerceIn(0f, duration)

        val poseEnd = upperBound(poseTimes, poses.size, t)
        val trail = FloatArray(poseEnd * 3)
        for (i in 0 until poseEnd) {
            val p = poses[i]
            trail[i * 3] = p.x
            trail[i * 3 + 1] = p.y
            trail[i * 3 + 2] = p.z
        }
        val camera = if (poseEnd > 0) poses[poseEnd - 1] else null

        val mapEnd = upperBound(pointFirstSeen, pointCount, t)
        val mapPoints = pointXyz.copyOf(mapEnd * 3)

        val liveIndex = latestObservation(t)
        val live = liveIndex.takeIf { it >= 0 }?.let { observations[it] }?.let { indices ->
            val out = FloatArray(indices.size * 3)
            var n = 0
            for (index in indices) {
                if (index >= mapEnd) continue
                out[n++] = pointXyz[index * 3]
                out[n++] = pointXyz[index * 3 + 1]
                out[n++] = pointXyz[index * 3 + 2]
            }
            out.copyOf(n)
        } ?: FloatArray(0)

        val planes = planeHistory.values.mapNotNull { history ->
            history.lastOrNull { it.first <= t }?.second?.takeIf { it.polygon.size >= 9 }
        }
        val anchors = anchorHistory.values.mapNotNull { history -> history.lastOrNull { it.first <= t }?.second }

        return ArDebugFrame(
            time = t,
            trail = trail,
            camera = camera,
            mapPoints = mapPoints,
            livePoints = live,
            planes = planes,
            anchors = anchors,
            keyframes = keyframes(poseEnd),
            liveKey = liveIndex,
        )
    }

    /**
     * Poses among the first [end] spaced [KEYFRAME_SPACING_M] apart along the path, the newest
     * excluded (it is the live frustum). Past [MAX_KEYFRAMES] the spacing widens, so a long walk
     * keeps an even spread instead of losing its start.
     */
    private fun keyframes(end: Int): List<DebugPose> {
        if (end < 2) return emptyList()
        var length = 0f
        for (i in 1 until end) length += distance(poses[i - 1], poses[i])
        val spacing = maxOf(KEYFRAME_SPACING_M, length / MAX_KEYFRAMES)
        val out = ArrayList<DebugPose>()
        var travelled = spacing // the first pose is a keyframe
        for (i in 0 until end - 1) {
            if (i > 0) travelled += distance(poses[i - 1], poses[i])
            if (travelled >= spacing) {
                out.add(poses[i])
                travelled = 0f
            }
        }
        // Never draw a history frustum on top of the live one.
        val head = poses[end - 1]
        while (out.isNotEmpty() && distance(out.last(), head) < spacing * 0.5f) out.removeAt(out.lastIndex)
        return out
    }

    private fun distance(a: DebugPose, b: DebugPose): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Index of the observation in force at [t] — the latest at or before it, if recent — or -1. */
    private fun latestObservation(t: Float): Int {
        var lo = 0
        var hi = observationTimes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (observationTimes[mid] <= t) lo = mid + 1 else hi = mid
        }
        if (lo == 0) return -1
        val at = observationTimes[lo - 1]
        return if (t - at <= LIVE_POINTS_WINDOW_S) lo - 1 else -1
    }

    companion object {
        /** A pose closer than this to the last kept one does not extend the trail. */
        const val POSE_MIN_STEP_M = 0.005f

        /** cos(½·1°): a turn smaller than a degree does not either. */
        const val POSE_MIN_TURN = 0.99996f

        const val MAX_POSES = 20_000

        /** Feature points closer than this are one point of the map. */
        const val POINT_VOXEL_M = 0.03f

        const val MAX_MAP_POINTS = 12_000

        const val MIN_POINT_CONFIDENCE = 0.2f

        const val MAX_OBSERVATIONS = 6_000

        /** A point cloud older than this no longer counts as "what ARCore sees now". */
        const val LIVE_POINTS_WINDOW_S = 1.5f

        const val KEYFRAME_SPACING_M = 0.6f

        const val MAX_KEYFRAMES = 48

        private val EMPTY_INTS = IntArray(0)

        /** Packs the voxel of (x, y, z) into one key: 21 signed bits per axis. */
        internal fun voxelKey(x: Float, y: Float, z: Float): Long {
            val ix = floor(x / POINT_VOXEL_M).toLong() and 0x1FFFFF
            val iy = floor(y / POINT_VOXEL_M).toLong() and 0x1FFFFF
            val iz = floor(z / POINT_VOXEL_M).toLong() and 0x1FFFFF
            return (ix shl 42) or (iy shl 21) or iz
        }

        /** Number of leading entries of the sorted [values] (first [size]) that are `<= t`. */
        internal fun upperBound(values: FloatArray, size: Int, t: Float): Int {
            var lo = 0
            var hi = size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (values[mid] <= t) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}
