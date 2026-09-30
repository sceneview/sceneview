package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The replay's "memory palace", after Bilawal Sidhu's: the photo the phone took at a moment of
 * the session, hung in the reconstructed room where that photo's surfaces are, and a flight into
 * the phone's own pose — where the photo and the scan line up, and the moment fills the screen.
 *
 * Nothing here is staged: the pose and the photo are the recorded ones, and the window's depth
 * is what the recording measured in front of the lens — its feature points, or the plane its
 * centre ray meets.
 */
object MemoryPalace {
    /** Nearer than this, a point is the phone's own shadow or noise, not the room. */
    const val MIN_DEPTH_M = 0.25f

    /** Farther than this, a point is past any room a phone scans. */
    const val MAX_DEPTH_M = 8f

    /** Fewer points than this in the frame, and their median says nothing: the plane decides. */
    const val MIN_POINTS = 8

    /**
     * The window stands at this quantile of the seen points' depths: past the near clutter,
     * short of the far wall, where most of the picture's surfaces are.
     */
    const val DEPTH_QUANTILE = 0.5f

    /** Seconds a step inside, or out, takes: long enough to read as a flight, short of a wait. */
    const val FLIGHT_S = 0.9f

    /**
     * How deep, along the lens axis, the camera at [pose] saw its surfaces: the [DEPTH_QUANTILE]
     * of the depths of [points] (flat xyz) inside its [lens], or, with fewer than [MIN_POINTS] of
     * them, where its centre ray meets one of [planes]. `null` when the recording saw neither.
     */
    fun windowDepth(
        pose: DebugPose,
        lens: ReplayLens,
        points: FloatArray,
        planes: List<DebugPlane>,
    ): Float? {
        val depths = ArrayList<Float>()
        for (i in 0 until points.size / 3) {
            val local = toLocal(pose, points[i * 3], points[i * 3 + 1], points[i * 3 + 2])
            val depth = -local.z
            if (depth !in MIN_DEPTH_M..MAX_DEPTH_M) continue
            val inside = abs(local.x) <= depth * lens.halfWidthPerDepth &&
                abs(local.y) <= depth * lens.halfHeightPerDepth
            if (inside) depths += depth
        }
        if (depths.size >= MIN_POINTS) {
            depths.sort()
            return depths[((depths.size - 1) * DEPTH_QUANTILE).toInt()]
        }
        return centreRayHit(pose, planes)
    }

    /** The nearest of [planes] the centre ray of [pose] meets inside its polygon, as a depth. */
    fun centreRayHit(pose: DebugPose, planes: List<DebugPlane>): Float? {
        val origin = pose.position
        val forward = pose.forward
        var nearest: Float? = null
        for (plane in planes) {
            val hit = rayPolygon(origin, forward, plane.polygon)?.takeIf { it in MIN_DEPTH_M..MAX_DEPTH_M }
            if (hit != null && (nearest == null || hit < nearest)) nearest = hit
        }
        return nearest
    }

    /** The world point ([x], [y], [z]) in [pose]'s own frame: the camera looks down its -Z. */
    fun toLocal(pose: DebugPose, x: Float, y: Float, z: Float): Vec3 {
        val inverse = DebugPose(0f, 0f, 0f, -pose.qx, -pose.qy, -pose.qz, pose.qw)
        return inverse.rotate(x - pose.x, y - pose.y, z - pose.z)
    }

    /**
     * The pose of a camera at [eye] looking at [target], upright: the orbit's view as a pose, so
     * a flight can blend it with a recorded one.
     */
    fun lookPose(eye: Vec3, target: Vec3): DebugPose {
        val f = (target - eye).normalized()
        var r = f.cross(Vec3.Up)
        r = if (r.length() < 1e-6f) Vec3(1f, 0f, 0f) else r.normalized()
        val u = r.cross(f)
        // Columns X = r, Y = u, Z = -f.
        return fromBasis(eye, r, u, f * -1f)
    }

    /**
     * [a] to [b] at [t] in 0..1: the position along the straight line, the rotation along the
     * shortest arc. A flight through a doorway, not round it.
     */
    fun blend(a: DebugPose, b: DebugPose, t: Float): DebugPose {
        val s = t.coerceIn(0f, 1f)
        var bx = b.qx
        var by = b.qy
        var bz = b.qz
        var bw = b.qw
        var dot = a.qx * bx + a.qy * by + a.qz * bz + a.qw * bw
        if (dot < 0f) {
            bx = -bx; by = -by; bz = -bz; bw = -bw; dot = -dot
        }
        val (wa, wb) = if (dot > SLERP_LINEAR) {
            1f - s to s
        } else {
            val theta = acos(dot.coerceIn(-1f, 1f))
            val sinTheta = sin(theta)
            sin((1f - s) * theta) / sinTheta to sin(s * theta) / sinTheta
        }
        var qx = wa * a.qx + wb * bx
        var qy = wa * a.qy + wb * by
        var qz = wa * a.qz + wb * bz
        var qw = wa * a.qw + wb * bw
        val n = sqrt(qx * qx + qy * qy + qz * qz + qw * qw).coerceAtLeast(1e-9f)
        qx /= n; qy /= n; qz /= n; qw /= n
        return DebugPose(
            a.x + (b.x - a.x) * s, a.y + (b.y - a.y) * s, a.z + (b.z - a.z) * s,
            qx, qy, qz, qw,
        )
    }

    /** Ease in and out: a flight that leaves and lands softly. */
    fun ease(t: Float): Float {
        val s = t.coerceIn(0f, 1f)
        return s * s * (3f - 2f * s)
    }

    /** The column-major 4×4 matrix of [pose]: rotation, then translation. */
    fun matrix(pose: DebugPose): FloatArray {
        val x = pose.rotate(1f, 0f, 0f)
        val y = pose.rotate(0f, 1f, 0f)
        val z = pose.rotate(0f, 0f, 1f)
        return floatArrayOf(
            x.x, x.y, x.z, 0f,
            y.x, y.y, y.z, 0f,
            z.x, z.y, z.z, 0f,
            pose.x, pose.y, pose.z, 1f,
        )
    }

    private fun fromBasis(position: Vec3, x: Vec3, y: Vec3, z: Vec3): DebugPose {
        // The rotation matrix's columns are x, y, z; the standard trace-based conversion.
        val m00 = x.x
        val m11 = y.y
        val m22 = z.z
        val trace = m00 + m11 + m22
        val qx: Float
        val qy: Float
        val qz: Float
        val qw: Float
        if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            qw = 0.25f * s
            qx = (y.z - z.y) / s
            qy = (z.x - x.z) / s
            qz = (x.y - y.x) / s
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            qw = (y.z - z.y) / s
            qx = 0.25f * s
            qy = (y.x + x.y) / s
            qz = (z.x + x.z) / s
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            qw = (z.x - x.z) / s
            qx = (y.x + x.y) / s
            qy = 0.25f * s
            qz = (z.y + y.z) / s
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            qw = (x.y - y.x) / s
            qx = (z.x + x.z) / s
            qy = (z.y + y.z) / s
            qz = 0.25f * s
        }
        return DebugPose(position.x, position.y, position.z, qx, qy, qz, qw)
    }

    /** Distance along the unit [dir] from [origin] to the convex [polygon] (flat xyz), if it is hit. */
    private fun rayPolygon(origin: Vec3, dir: Vec3, polygon: FloatArray): Float? {
        val normal = polygonNormal(polygon) ?: return null
        val denom = normal.dot(dir)
        if (abs(denom) < 1e-6f) return null
        val t = normal.dot(vertex(polygon, 0) - origin) / denom
        return t.takeIf { it > 0f && containsConvex(polygon, normal, origin + dir * it) }
    }

    /** The unit normal of the flat [polygon] (flat xyz), `null` when it is degenerate. */
    private fun polygonNormal(polygon: FloatArray): Vec3? {
        val n = polygon.size / 3
        if (n < 3) return null
        val p0 = vertex(polygon, 0)
        var normal = Vec3.Zero
        for (i in 1 until n - 1) {
            normal = normal + (vertex(polygon, i) - p0).cross(vertex(polygon, i + 1) - p0)
        }
        return normal.takeIf { it.length() >= 1e-9f }?.normalized()
    }

    /** Whether [point], on the plane of the convex [polygon], lies inside it. */
    private fun containsConvex(polygon: FloatArray, normal: Vec3, point: Vec3): Boolean {
        val n = polygon.size / 3
        var sign = 0f
        for (i in 0 until n) {
            val a = vertex(polygon, i)
            val side = normal.dot((vertex(polygon, (i + 1) % n) - a).cross(point - a))
            if (abs(side) >= 1e-9f) {
                if (sign == 0f) sign = side else if (sign * side < 0f) return false
            }
        }
        return true
    }

    private fun vertex(polygon: FloatArray, i: Int) = Vec3(polygon[i * 3], polygon[i * 3 + 1], polygon[i * 3 + 2])

    /** Past this cosine, two rotations are close enough to blend linearly. */
    private const val SLERP_LINEAR = 0.9995f
}
