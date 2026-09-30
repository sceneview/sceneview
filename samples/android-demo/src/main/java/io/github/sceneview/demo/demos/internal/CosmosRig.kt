package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.CosmosSystem.DEG
import io.github.sceneview.demo.demos.internal.CosmosSystem.ORBIT_RADIUS
import io.github.sceneview.demo.demos.internal.CosmosSystem.RING_OUTER
import io.github.sceneview.demo.demos.internal.CosmosSystem.SYSTEM_COVER_X
import io.github.sceneview.demo.demos.internal.CosmosSystem.SYSTEM_COVER_Y
import io.github.sceneview.demo.demos.internal.CosmosSystem.TAN_HALF_VERTICAL_FOV
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Star scene's per-frame orbit and camera maths, without garbage: every result lands in a
 * buffer this rig owns and reuses, and what depends on the viewport aspect alone (the orbit
 * frame, its normal, the orbit's sample points) is computed once per aspect. The render loop
 * holds one rig; [CosmosSystem]'s functions wrap a fresh one for taps and tests.
 *
 * A returned array is valid until the next call of the same method: copy it to keep it.
 * Not thread-safe: one rig per render loop.
 */
@Suppress("TooManyFunctions")
internal class CosmosRig {

    private var preparedAspect = Float.NaN
    private val frame = FloatArray(4)
    private val normal = FloatArray(3)
    private val orbitPoints = FloatArray(ORBIT_SAMPLES * 3)

    private val trail = FloatArray(4)
    private val planet = FloatArray(3)
    private val planetSpin = FloatArray(4)
    private val pose = FloatArray(CosmosSystem.POSE_FLOATS)
    private val blended = FloatArray(CosmosSystem.POSE_FLOATS)

    // Scratch vectors and quaternions for the maths below.
    private val qa = FloatArray(4)
    private val qb = FloatArray(4)
    private val va = FloatArray(3)
    private val vb = FloatArray(3)
    private val vc = FloatArray(3)
    private val vd = FloatArray(3)

    /** The orbit plane's orientation for [aspect] (see [CosmosSystem.orbitFrame]). */
    fun orbitFrame(aspect: Float): FloatArray {
        prepare(aspect)
        return frame
    }

    /** The orbit plane's normal in world space. */
    fun orbitNormal(aspect: Float): FloatArray {
        prepare(aspect)
        return normal
    }

    /** The orbit trail's node: the orbit frame turned to the planet's angle. */
    fun trailRotation(time: Float, aspect: Float): FloatArray {
        prepare(aspect)
        setAxisAngle(qa, 0f, 1f, 0f, CosmosSystem.orbitAngle(time))
        mulInto(frame, qa, trail)
        return trail
    }

    /** The planet's centre in world space at [time]. */
    fun planetPosition(time: Float, aspect: Float): FloatArray {
        prepare(aspect)
        val a = CosmosSystem.orbitAngle(time)
        rotateInto(frame, ORBIT_RADIUS * cos(a), 0f, -ORBIT_RADIUS * sin(a), planet)
        return planet
    }

    /** The planet's orientation: its spin axis leans off the orbit normal and holds still. */
    fun planetRotation(time: Float, aspect: Float): FloatArray {
        prepare(aspect)
        setAxisAngle(qa, 1f, 0f, 0f, OBLIQUITY_DEGREES * DEG)
        mulInto(frame, qa, qb)
        setAxisAngle(qa, 0f, 1f, 0f, PLANET_SPIN_DEGREES_PER_SECOND * time * DEG)
        mulInto(qb, qa, planetSpin)
        return planetSpin
    }

    /** Camera pose (eye, target, up — nine floats, as [CosmosFraming.pose]) for [focus]. */
    fun pose(focus: CosmosFocus, time: Float, aspect: Float): FloatArray = when (focus) {
        CosmosFocus.System -> systemPose(time, aspect)
        CosmosFocus.Star -> CosmosFraming.pose(CosmosScene.Star, time, aspect).copyInto(pose)
        CosmosFocus.Planet -> planetPose(time, aspect)
    }

    /**
     * The whole system, drifting a little, from the nearest distance at which every point of
     * the orbit, rings included, lands inside the viewport's safe area.
     *
     * The camera looks at the origin, so its axes depend on the drift only: for each orbit point
     * the fit is linear in the distance and the nearest fitting distance is a closed form, a max
     * over the orbit's samples.
     */
    fun systemPose(time: Float, aspect: Float): FloatArray {
        prepare(aspect)
        val elevation = (SYSTEM_ELEVATION_DEGREES + 2f * sin(time * 0.09f)) * DEG
        val yaw = (5f * sin(time * 0.1f)) * DEG
        // Eye direction from the origin; forward is its opposite, up is world +y.
        val dx = cos(elevation) * sin(yaw)
        val dy = sin(elevation)
        val dz = cos(elevation) * cos(yaw)
        // right = forward × up = (-d) × (0, 1, 0), normalised; true up = right × forward.
        val rl = sqrt(dz * dz + dx * dx).coerceAtLeast(1e-6f)
        val rx = dz / rl
        val rz = -dx / rl
        val ux = rz * dy
        val uy = rx * dz - rz * dx
        val uz = -rx * dy
        val needX = TAN_HALF_VERTICAL_FOV * aspect * SYSTEM_COVER_X
        val needY = TAN_HALF_VERTICAL_FOV * SYSTEM_COVER_Y
        var distance = MIN_SYSTEM_DISTANCE
        for (i in 0 until ORBIT_SAMPLES) {
            val px = orbitPoints[i * 3]
            val py = orbitPoints[i * 3 + 1]
            val pz = orbitPoints[i * 3 + 2]
            val alongForward = -(px * dx + py * dy + pz * dz)
            val across = abs(px * rx + pz * rz)
            val upward = abs(px * ux + py * uy + pz * uz)
            // Depth along the view axis is distance + alongForward; it must cover both extents.
            val depth = max((across + RING_OUTER) / needX, (upward + RING_OUTER) / needY)
            distance = max(distance, depth - alongForward)
        }
        distance *= FIT_MARGIN
        pose[0] = distance * dx
        pose[1] = distance * dy
        pose[2] = distance * dz
        pose[3] = 0f
        pose[4] = 0f
        pose[5] = 0f
        pose[6] = 0f
        pose[7] = 1f
        pose[8] = 0f
        return pose
    }

    /**
     * Beside the ringed world, following it round its orbit: behind it, outside the orbit and
     * above its plane, rolled so the star's limb rises over the top of the frame and lights a
     * crescent on the planet below it.
     */
    fun planetPose(time: Float, aspect: Float): FloatArray {
        val p = planetPosition(time, aspect)
        val n = normal
        // va = outward from the star, vb = backward along the orbit.
        setNormalized(va, p[0], p[1], p[2])
        crossInto(va, n, vb)
        setNormalized(vb, vb[0], vb[1], vb[2])
        val swing = FOLLOW_SWING_DEGREES * DEG
        val e = FOLLOW_ELEVATION_DEGREES * DEG
        // vc = from the planet to the eye: behind it, swung out from the star, above the plane.
        setNormalized(
            vc,
            (va[0] * cos(swing) + vb[0] * sin(swing)) * cos(e) + n[0] * sin(e),
            (va[1] * cos(swing) + vb[1] * sin(swing)) * cos(e) + n[1] * sin(e),
            (va[2] * cos(swing) + vb[2] * sin(swing)) * cos(e) + n[2] * sin(e),
        )
        val d = planetViewDistance(aspect)
        val ex = p[0] + vc[0] * d
        val ey = p[1] + vc[1] * d
        val ez = p[2] + vc[2] * d
        // Roll the camera so the star stands straight above the planet, and tip the view up
        // until the planet sits a little below centre: the star's limb rises over the top edge.
        va[0] = -vc[0]
        va[1] = -vc[1]
        va[2] = -vc[2]
        setNormalized(vb, -ex, -ey, -ez)
        val apart = acos(dot(va, vb).coerceIn(-1f, 1f))
        val tip = atan(FOLLOW_PLANET_BELOW * TAN_HALF_VERTICAL_FOV)
        slerpInto(va, vb, (tip / apart).coerceIn(0f, 1f), vd)
        val lift = dot(vb, vd)
        setNormalized(vc, vb[0] - vd[0] * lift, vb[1] - vd[1] * lift, vb[2] - vd[2] * lift)
        pose[0] = ex
        pose[1] = ey
        pose[2] = ez
        pose[3] = ex + vd[0] * d
        pose[4] = ey + vd[1] * d
        pose[5] = ez + vd[2] * d
        pose[6] = vc[0]
        pose[7] = vc[1]
        pose[8] = vc[2]
        return pose
    }

    /** How far the follow camera stands from the planet: the rings with room around them. */
    fun planetViewDistance(aspect: Float): Float =
        CosmosFraming.fitDistance(RING_OUTER * PLANET_VIEW_ROOM, RING_OUTER * PLANET_VIEW_ROOM, aspect)

    /**
     * A pose between [from] and [to] at [t] in [0, 1]. The eye travels round the star, not
     * through it: its direction from the origin turns (slerp) while its distance eases, so a
     * flight from one side of the system to the other never crosses the plasma.
     */
    fun blend(from: FloatArray, to: FloatArray, t: Float): FloatArray {
        val r0 = hypot3(from[0], from[1], from[2]).coerceAtLeast(1e-9f)
        val r1 = hypot3(to[0], to[1], to[2]).coerceAtLeast(1e-9f)
        va[0] = from[0] / r0
        va[1] = from[1] / r0
        va[2] = from[2] / r0
        vb[0] = to[0] / r1
        vb[1] = to[1] / r1
        vb[2] = to[2] / r1
        slerpInto(va, vb, t, vc)
        val r = r0 + (r1 - r0) * t
        setNormalized(
            vd,
            from[6] + (to[6] - from[6]) * t,
            from[7] + (to[7] - from[7]) * t,
            from[8] + (to[8] - from[8]) * t,
        )
        for (i in 0..2) {
            blended[i] = vc[i] * r
            blended[3 + i] = from[3 + i] + (to[3 + i] - from[3 + i]) * t
            blended[6 + i] = vd[i]
        }
        return blended
    }

    /**
     * How much of the orbit trail to draw with the camera's eye at ([ex], [ey], [ez]): all of it
     * from afar, none from the follow view, where the arc behind the planet runs past the lens
     * and would cross the foreground as a bright streak.
     */
    fun trailVisibility(ex: Float, ey: Float, ez: Float, time: Float, aspect: Float): Float {
        val p = planetPosition(time, aspect)
        val gap = hypot3(ex - p[0], ey - p[1], ez - p[2])
        val near = planetViewDistance(aspect)
        return smoothstep(near * TRAIL_HIDDEN_WITHIN, near * TRAIL_SHOWN_BEYOND, gap)
    }

    private fun prepare(aspect: Float) {
        if (aspect == preparedAspect) return
        preparedAspect = aspect
        val roll = atan2(SYSTEM_COVER_Y, SYSTEM_COVER_X * aspect.coerceAtLeast(0.1f)) * 0.9f
        setAxisAngle(qa, 0f, 0f, 1f, roll)
        setAxisAngle(qb, 1f, 0f, 0f, INCLINATION_DEGREES * DEG)
        mulInto(qa, qb, frame)
        rotateInto(frame, 0f, 1f, 0f, normal)
        for (i in 0 until ORBIT_SAMPLES) {
            val a = i * 2f * PI.toFloat() / ORBIT_SAMPLES
            rotateInto(frame, ORBIT_RADIUS * cos(a), 0f, -ORBIT_RADIUS * sin(a), va)
            va.copyInto(orbitPoints, i * 3)
        }
    }

    companion object {
        /** How far the planet's spin axis leans from the orbit's normal, toward the camera. */
        private const val OBLIQUITY_DEGREES = 22f

        /** How far the orbit plane is tipped toward the camera, so its ellipse opens up. */
        private const val INCLINATION_DEGREES = 9f
        private const val PLANET_SPIN_DEGREES_PER_SECOND = 9f

        /** How far above the orbit plane the overview looks down: enough to open the ellipse. */
        private const val SYSTEM_ELEVATION_DEGREES = 16f

        /** The overview never comes closer than this, whatever the aspect. */
        private const val MIN_SYSTEM_DISTANCE = 1.5f

        /** A hair of slack over the exact fit, so float rounding never puts a point on the edge. */
        private const val FIT_MARGIN = 1.001f

        /** How far above the orbit plane the follow camera sits, in degrees. */
        private const val FOLLOW_ELEVATION_DEGREES = 25f

        /** How far the follow camera swings from straight out (star behind the planet) to behind it. */
        private const val FOLLOW_SWING_DEGREES = 55f

        /** Where the planet sits under the follow camera, as a share of the half height below centre. */
        private const val FOLLOW_PLANET_BELOW = 0.3f

        /** The rings' share of the follow view: they fill 1 / this of its half extent. */
        private const val PLANET_VIEW_ROOM = 1.35f

        /** The trail is gone within this many follow distances of the planet, whole beyond the next. */
        private const val TRAIL_HIDDEN_WITHIN = 1.3f
        private const val TRAIL_SHOWN_BEYOND = 2.2f

        private const val ORBIT_SAMPLES = 36
    }
}

// ─── Allocation-free vector and quaternion helpers (x, y, z, w) ─────────────────────────────

private fun setAxisAngle(out: FloatArray, x: Float, y: Float, z: Float, angle: Float) {
    val s = sin(angle / 2f)
    out[0] = x * s
    out[1] = y * s
    out[2] = z * s
    out[3] = cos(angle / 2f)
}

/** Hamilton product a·b (rotate by b, then by a) into [out]; [out] may be [a] or [b]. */
internal fun mulInto(a: FloatArray, b: FloatArray, out: FloatArray) {
    val x = a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1]
    val y = a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0]
    val z = a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3]
    val w = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
    out[0] = x
    out[1] = y
    out[2] = z
    out[3] = w
}

/** (x, y, z) rotated by the unit quaternion [q], into [out]. */
internal fun rotateInto(q: FloatArray, x: Float, y: Float, z: Float, out: FloatArray) {
    // v' = v + 2w(q × v) + 2 q × (q × v)
    val tx = 2f * (q[1] * z - q[2] * y)
    val ty = 2f * (q[2] * x - q[0] * z)
    val tz = 2f * (q[0] * y - q[1] * x)
    out[0] = x + q[3] * tx + (q[1] * tz - q[2] * ty)
    out[1] = y + q[3] * ty + (q[2] * tx - q[0] * tz)
    out[2] = z + q[3] * tz + (q[0] * ty - q[1] * tx)
}

private fun crossInto(a: FloatArray, b: FloatArray, out: FloatArray) {
    val x = a[1] * b[2] - a[2] * b[1]
    val y = a[2] * b[0] - a[0] * b[2]
    val z = a[0] * b[1] - a[1] * b[0]
    out[0] = x
    out[1] = y
    out[2] = z
}

private fun setNormalized(out: FloatArray, x: Float, y: Float, z: Float) {
    val l = hypot3(x, y, z).coerceAtLeast(1e-9f)
    out[0] = x / l
    out[1] = y / l
    out[2] = z / l
}

/** Spherical interpolation of unit vectors [a] → [b] into [out], which must be neither. */
private fun slerpInto(a: FloatArray, b: FloatArray, t: Float, out: FloatArray) {
    val angle = acos(dot(a, b).coerceIn(-1f, 1f))
    if (angle < 1e-4f) {
        setNormalized(out, a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
        return
    }
    val sa = sin(angle)
    val wa = sin((1f - t) * angle) / sa
    val wb = sin(t * angle) / sa
    for (i in 0..2) out[i] = a[i] * wa + b[i] * wb
}

private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

private fun hypot3(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
