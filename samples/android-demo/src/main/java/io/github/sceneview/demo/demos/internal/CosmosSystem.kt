package io.github.sceneview.demo.demos.internal

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the Star scene's camera is looking: the whole system, the star up close, or the
 * ringed world it follows along its orbit.
 */
internal enum class CosmosFocus(val caption: String) {
    System("Tap the ringed world to fly to it"),
    Star("A hot blue star and its magnetic loops"),
    Planet("A ringed world in blue starlight"),
}

/**
 * The ringed world of the Cosmos Star scene: its orbit, its geometry, the camera poses that
 * frame it and the eased flight between them. Pure functions of time and viewport aspect, like
 * [CosmosFraming], so the whole choreography is covered by JVM tests and the QA captures are
 * reproducible.
 *
 * Units are the star's: the plasma star is a unit sphere at the origin. Quaternions are
 * `(x, y, z, w)` float arrays, converted to kotlin-math at the node.
 */
internal object CosmosSystem {

    const val ORBIT_RADIUS = 3.3f
    const val PLANET_RADIUS = 0.3f
    const val RING_INNER = 0.42f
    const val RING_OUTER = 0.74f

    /** Degrees the planet travels along its orbit per second. */
    private const val ORBIT_DEGREES_PER_SECOND = 6f

    /** Where it is at time zero: right of the star and a little beyond it, so its day side shows. */
    private const val ORBIT_PHASE_DEGREES = 7f

    /** How far the planet's spin axis leans from the orbit's normal, toward the camera. */
    private const val OBLIQUITY_DEGREES = 22f

    /** How far the orbit plane is tipped toward the camera, so its ellipse opens up. */
    private const val INCLINATION_DEGREES = 9f
    private const val PLANET_SPIN_DEGREES_PER_SECOND = 9f

    /** Arc of the orbit trail behind the planet, in degrees. */
    const val TRAIL_DEGREES = 110f

    /** The share of the viewport's half extents the system may fill: room for the chrome. */
    private const val SYSTEM_COVER_X = 0.9f
    private const val SYSTEM_COVER_Y = 0.6f

    /** How far above the orbit plane the overview looks down, in degrees: enough to open the ellipse. */
    private const val SYSTEM_ELEVATION_DEGREES = 16f

    /** How far above the orbit plane the follow camera sits, in degrees. */
    private const val FOLLOW_ELEVATION_DEGREES = 25f

    /** How far the follow camera swings from straight out (star behind the planet) to behind it. */
    private const val FOLLOW_SWING_DEGREES = 55f

    /** Where the planet sits under the follow camera, as a share of the half height below centre. */
    private const val FOLLOW_PLANET_BELOW = 0.3f

    /** A flight, start to settle, in seconds. */
    const val FLY_SECONDS = 1.4f

    /** tan(half vertical FOV) of the default 28 mm lens on a 24 mm sensor height. */
    private const val TAN_HALF_VERTICAL_FOV = 12f / 28f

    private const val DEG = (PI / 180.0).toFloat()

    /**
     * The orbit plane's orientation: rolled about the view axis so the orbit's long axis runs
     * along the viewport's diagonal (a portrait phone has more room corner to corner than
     * across), then tipped toward the camera.
     */
    fun orbitFrame(aspect: Float): FloatArray {
        val roll = atan2(SYSTEM_COVER_Y, SYSTEM_COVER_X * aspect.coerceAtLeast(0.1f)) * 0.9f
        return mul(axisAngle(0f, 0f, 1f, roll), axisAngle(1f, 0f, 0f, INCLINATION_DEGREES * DEG))
    }

    /** The planet's angle along its orbit at [time], in radians. */
    fun orbitAngle(time: Float): Float = (ORBIT_PHASE_DEGREES + ORBIT_DEGREES_PER_SECOND * time) * DEG

    /** The orbit trail's node: the orbit frame turned to the planet's angle. */
    fun trailRotation(time: Float, aspect: Float): FloatArray =
        mul(orbitFrame(aspect), axisAngle(0f, 1f, 0f, orbitAngle(time)))

    /** The planet's centre in world space at [time]. */
    fun planetPosition(time: Float, aspect: Float): FloatArray =
        rotate(trailRotation(time, aspect), floatArrayOf(ORBIT_RADIUS, 0f, 0f))

    /** The planet's orientation: its spin axis leans off the orbit normal and holds still. */
    fun planetRotation(time: Float, aspect: Float): FloatArray = mul(
        mul(orbitFrame(aspect), axisAngle(1f, 0f, 0f, OBLIQUITY_DEGREES * DEG)),
        axisAngle(0f, 1f, 0f, PLANET_SPIN_DEGREES_PER_SECOND * time * DEG),
    )

    /** The orbit plane's normal in world space. */
    fun orbitNormal(aspect: Float): FloatArray = rotate(orbitFrame(aspect), floatArrayOf(0f, 1f, 0f))

    // ─── Camera ─────────────────────────────────────────────────────────────────────────────

    /** Camera pose (eye, target, up — nine floats, as [CosmosFraming.pose]) for [focus]. */
    fun pose(focus: CosmosFocus, time: Float, aspect: Float): FloatArray = when (focus) {
        CosmosFocus.System -> systemPose(time, aspect)
        CosmosFocus.Star -> CosmosFraming.pose(CosmosScene.Star, time, aspect)
        CosmosFocus.Planet -> planetPose(time, aspect)
    }

    /**
     * The whole system, drifting a little: the camera backs off until every point of the orbit,
     * rings included, lands inside the viewport's safe area.
     */
    fun systemPose(time: Float, aspect: Float): FloatArray {
        val elevation = (SYSTEM_ELEVATION_DEGREES + 2f * sin(time * 0.09f)) * DEG
        val yaw = (5f * sin(time * 0.1f)) * DEG
        val frame = orbitFrame(aspect)
        val ring = FloatArray(3)
        var near = 1.5f
        var far = 60f
        repeat(22) {
            val mid = 0.5f * (near + far)
            val pose = orbitPose(mid, elevation, yaw)
            var fits = true
            var step = 0
            while (fits && step < ORBIT_SAMPLES) {
                val a = step * 2f * PI.toFloat() / ORBIT_SAMPLES
                ring[0] = ORBIT_RADIUS * cos(a)
                ring[1] = 0f
                ring[2] = -ORBIT_RADIUS * sin(a)
                val p = rotate(frame, ring)
                fits = fitsInside(pose, aspect, p, RING_OUTER)
                step++
            }
            if (fits) far = mid else near = mid
        }
        return orbitPose(far, elevation, yaw)
    }

    /**
     * Beside the ringed world, following it round its orbit: behind it, outside the orbit and
     * above its plane, rolled so the star's limb rises over the top of the frame and lights a
     * crescent on the planet below it.
     */
    fun planetPose(time: Float, aspect: Float): FloatArray {
        val p = planetPosition(time, aspect)
        val n = orbitNormal(aspect)
        val out = normalized(p)
        // Behind the planet along its orbit, swung out from the star, and above the orbit plane:
        // the star lights a thick crescent and the trail leads the eye in from below.
        val back = normalized(cross(out, n))
        val swing = FOLLOW_SWING_DEGREES * DEG
        val e = FOLLOW_ELEVATION_DEGREES * DEG
        val dir = normalized(
            FloatArray(3) { i -> (out[i] * cos(swing) + back[i] * sin(swing)) * cos(e) + n[i] * sin(e) },
        )
        val d = CosmosFraming.fitDistance(RING_OUTER * 1.35f, RING_OUTER * 1.35f, aspect)
        val eye = FloatArray(3) { i -> p[i] + dir[i] * d }
        // Roll the camera so the star stands straight above the planet, and tip the view up
        // until the planet sits a little below centre: the star's limb rises over the top edge.
        val toPlanet = FloatArray(3) { i -> -dir[i] }
        val toStar = normalized(FloatArray(3) { i -> -eye[i] })
        val apart = acos(dot(toPlanet, toStar).coerceIn(-1f, 1f))
        val tip = atan(FOLLOW_PLANET_BELOW * TAN_HALF_VERTICAL_FOV)
        val forward = slerp(toPlanet, toStar, (tip / apart).coerceIn(0f, 1f))
        val up = normalized(FloatArray(3) { i -> toStar[i] - forward[i] * dot(toStar, forward) })
        return floatArrayOf(
            eye[0], eye[1], eye[2],
            eye[0] + forward[0] * d, eye[1] + forward[1] * d, eye[2] + forward[2] * d,
            up[0], up[1], up[2],
        )
    }

    /**
     * A pose between [from] and [to] at [t] in [0, 1]. The eye travels round the star, not
     * through it: its direction from the origin turns (slerp) while its distance eases, so a
     * flight from one side of the system to the other never crosses the plasma.
     */
    fun blend(from: FloatArray, to: FloatArray, t: Float): FloatArray {
        val r0 = hypot3(from[0], from[1], from[2])
        val r1 = hypot3(to[0], to[1], to[2])
        val d0 = floatArrayOf(from[0] / r0, from[1] / r0, from[2] / r0)
        val d1 = floatArrayOf(to[0] / r1, to[1] / r1, to[2] / r1)
        val dir = slerp(d0, d1, t)
        val r = r0 + (r1 - r0) * t
        val up = normalized(
            floatArrayOf(
                from[6] + (to[6] - from[6]) * t,
                from[7] + (to[7] - from[7]) * t,
                from[8] + (to[8] - from[8]) * t,
            ),
        )
        return floatArrayOf(
            dir[0] * r, dir[1] * r, dir[2] * r,
            from[3] + (to[3] - from[3]) * t, from[4] + (to[4] - from[4]) * t, from[5] + (to[5] - from[5]) * t,
            up[0], up[1], up[2],
        )
    }

    /**
     * The design system's `ease-expressive`, cubic-bezier(0.2, 0, 0, 1): a quick start that
     * settles long and soft, like the app's camera fly-in.
     */
    fun easeExpressive(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        // x(s) is monotonic on [0, 1]: bisect for the parameter whose x is t, return its y.
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val s = 0.5f * (lo + hi)
            if (bezier(s, 0.2f, 0f) < t) lo = s else hi = s
        }
        return bezier(0.5f * (lo + hi), 0f, 1f)
    }

    private fun bezier(s: Float, p1: Float, p2: Float): Float {
        val u = 1f - s
        return 3f * u * u * s * p1 + 3f * u * s * s * p2 + s * s * s
    }

    // ─── Touch ──────────────────────────────────────────────────────────────────────────────

    /**
     * What a tap at viewport pixel ([x], [y]) on a [width] × [height] viewport lands on, with
     * the camera at [pose] at [time]: the planet (rings included) first, being in front, then
     * the star; null for empty space. A target smaller than [minRadiusPx] on screen is hit
     * within that radius, so the planet stays easy to tap from the far view.
     */
    @Suppress("LongParameterList")
    fun hit(
        pose: FloatArray,
        time: Float,
        width: Float,
        height: Float,
        x: Float,
        y: Float,
        minRadiusPx: Float,
    ): CosmosFocus? {
        if (width <= 0f || height <= 0f) return null
        val aspect = width / height
        val targets = listOf(
            CosmosFocus.Planet to (planetPosition(time, aspect) to RING_OUTER),
            CosmosFocus.Star to (floatArrayOf(0f, 0f, 0f) to 1f),
        )
        for ((focus, target) in targets) {
            val (center, radius) = target
            val s = project(pose, aspect, center) ?: continue
            val px = (s[0] + 1f) * 0.5f * width
            val py = (1f - s[1]) * 0.5f * height
            val radiusPx = radius / (s[2] * TAN_HALF_VERTICAL_FOV) * 0.5f * height
            if (hypot(x - px, y - py) <= max(radiusPx, minRadiusPx)) return focus
        }
        return null
    }

    /**
     * Where world point [p] lands for a camera at [pose]: normalised device x and y in [-1, 1]
     * across the viewport, and the depth along the view axis; null behind the camera.
     */
    fun project(pose: FloatArray, aspect: Float, p: FloatArray): FloatArray? {
        val f = normalized(floatArrayOf(pose[3] - pose[0], pose[4] - pose[1], pose[5] - pose[2]))
        val r = normalized(cross(f, floatArrayOf(pose[6], pose[7], pose[8])))
        val u = cross(r, f)
        val rel = floatArrayOf(p[0] - pose[0], p[1] - pose[1], p[2] - pose[2])
        val depth = dot(rel, f)
        if (depth <= 1e-3f) return null
        val tanH = TAN_HALF_VERTICAL_FOV * aspect
        return floatArrayOf(dot(rel, r) / (depth * tanH), dot(rel, u) / (depth * TAN_HALF_VERTICAL_FOV), depth)
    }

    private fun fitsInside(pose: FloatArray, aspect: Float, p: FloatArray, pad: Float): Boolean {
        val s = project(pose, aspect, p) ?: return false
        val padY = pad / (s[2] * TAN_HALF_VERTICAL_FOV)
        val padX = padY / aspect
        return abs(s[0]) + padX <= SYSTEM_COVER_X && abs(s[1]) + padY <= SYSTEM_COVER_Y
    }

    private const val ORBIT_SAMPLES = 36

    /** A flat ring still gets a box with some thickness, so culling never sees a degenerate one. */
    private const val RING_BOUNDS_PAD = 0.01f

    private fun orbitPose(distance: Float, elevation: Float, yaw: Float): FloatArray = floatArrayOf(
        distance * cos(elevation) * sin(yaw),
        distance * sin(elevation),
        distance * cos(elevation) * cos(yaw),
        0f, 0f, 0f,
        0f, 1f, 0f,
    )

    // ─── Geometry ───────────────────────────────────────────────────────────────────────────

    /**
     * The rings: a flat annulus in the XZ plane from [inner] to [outer], [segments] around.
     * Sprite layout (the ring material reads only the position), so it uploads like the rest.
     */
    fun ring(inner: Float = RING_INNER, outer: Float = RING_OUTER, segments: Int = 128): GlowMesh {
        val vertices = FloatArray(segments * 2 * SPRITE_STRIDE)
        val indices = IntArray(segments * 6)
        for (i in 0 until segments) {
            val a = i * 2f * PI.toFloat() / segments
            for ((k, r) in floatArrayOf(inner, outer).withIndex()) {
                val o = (i * 2 + k) * SPRITE_STRIDE
                vertices[o] = r * cos(a)
                vertices[o + 2] = r * sin(a)
                vertices[o + 3] = 1f
                vertices[o + 4] = 1f
                vertices[o + 5] = 1f
                vertices[o + 6] = 1f
            }
            val a0 = i * 2
            val b0 = ((i + 1) % segments) * 2
            val o = i * 6
            indices[o] = a0
            indices[o + 1] = a0 + 1
            indices[o + 2] = b0
            indices[o + 3] = a0 + 1
            indices[o + 4] = b0 + 1
            indices[o + 5] = b0
        }
        return GlowMesh(
            vertices,
            indices,
            SPRITE_STRIDE,
            floatArrayOf(-outer, -RING_BOUNDS_PAD, -outer),
            floatArrayOf(outer, RING_BOUNDS_PAD, outer),
        )
    }

    /**
     * The orbit trail: an arc of the orbit, in the orbit frame, from the planet (at angle zero,
     * `x = radius`) back over [degrees], fading from a faint blue to nothing. Drawn with the
     * additive ribbon material under a node that turns with the planet.
     */
    fun orbitTrail(radius: Float = ORBIT_RADIUS, degrees: Float = TRAIL_DEGREES, points: Int = 96): GlowMesh {
        val xyz = FloatArray(points * 3)
        val rgb = FloatArray(points * 3)
        for (i in 0 until points) {
            val u = i.toFloat() / (points - 1)
            // Behind the planet: the orbit turns from +x toward -z, so the trail runs toward +z.
            val a = -u * degrees * DEG
            xyz[i * 3] = radius * cos(a)
            xyz[i * 3 + 2] = -radius * sin(a)
            // Starts just behind the planet's rings, not through them.
            val fade = (1f - u) * (1f - u) * smoothstepUp(0.1f, 0.22f, u)
            rgb[i * 3] = 0.3f * fade
            rgb[i * 3 + 1] = 0.55f * fade
            rgb[i * 3 + 2] = 1.1f * fade
        }
        return RibbonBuilder(points).apply { addCurve(xyz, rgb, halfWidth = 0.009f, seed = 0f) }.build()
    }

    // ─── Small vector and quaternion helpers ──────────────────────────────────────────────

    /** Rotation of [angle] radians about the unit axis ([x], [y], [z]). */
    fun axisAngle(x: Float, y: Float, z: Float, angle: Float): FloatArray {
        val s = sin(angle / 2f)
        return floatArrayOf(x * s, y * s, z * s, cos(angle / 2f))
    }

    /** Hamilton product: rotate by [b], then by [a]. */
    fun mul(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )

    /** [v] rotated by the unit quaternion [q]. */
    fun rotate(q: FloatArray, v: FloatArray): FloatArray {
        // v' = v + 2w(q × v) + 2 q × (q × v)
        val qv = floatArrayOf(q[0], q[1], q[2])
        val t = cross(qv, v).let { floatArrayOf(it[0] * 2f, it[1] * 2f, it[2] * 2f) }
        val c = cross(qv, t)
        return floatArrayOf(v[0] + q[3] * t[0] + c[0], v[1] + q[3] * t[1] + c[1], v[2] + q[3] * t[2] + c[2])
    }
}

// ─── Plain vector helpers (file-private: the camera maths above is all they serve) ──────────

private fun slerp(a: FloatArray, b: FloatArray, t: Float): FloatArray {
    val angle = acos(dot(a, b).coerceIn(-1f, 1f))
    if (angle < 1e-4f) {
        return normalized(floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t))
    }
    val sa = sin(angle)
    val wa = sin((1f - t) * angle) / sa
    val wb = sin(t * angle) / sa
    return floatArrayOf(a[0] * wa + b[0] * wb, a[1] * wa + b[1] * wb, a[2] * wa + b[2] * wb)
}

private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
)

private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

private fun hypot3(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

private fun normalized(v: FloatArray): FloatArray {
    val l = hypot3(v[0], v[1], v[2]).coerceAtLeast(1e-9f)
    return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
}

private fun smoothstepUp(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
