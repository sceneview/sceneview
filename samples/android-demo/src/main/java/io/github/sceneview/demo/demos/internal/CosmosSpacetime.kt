package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.CosmosSystem.DEG
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The Star scene's **Spacetime** view: the same star and worlds laid on a rubber sheet that each
 * mass hollows out — the textbook picture of gravity as curved spacetime, lit by a grazing key
 * light so the star's hollow falls into black shadow.
 *
 * Everything here is pure maths, shared value for value with iOS (`CosmosSpacetime.swift`):
 * the bodies and their orbits, the sheet's profile and its gradient, where each body comes to
 * rest on it, the camera, the light and the entry sequence. The render loop holds one
 * [SpacetimeField] and calls it every frame without allocating; the shader (`cosmos_spacetime.mat`)
 * evaluates the same profile per vertex.
 *
 * ### The sheet
 *
 * Each body is a softened (Plummer) well, cut to zero at the sheet's rim by a smooth window:
 *
 * `F(p) = −w·Σ Dᵢεᵢ(1/√(dᵢ²+εᵢ²) − 1/√(R²+εᵢ²)) · (1 − smoothstep(0.85R, R, |p|))`
 *
 * The sheet drawn is `H = F + C(w) − lift`. `C(w)` is minus the star's rest height, so the star's
 * centre never leaves the origin whatever the well depth `w`; `lift` drops the flat sheet below
 * the star at the start of the entry, so it rises from underneath instead of cutting through it.
 */
@Suppress("TooManyFunctions")
internal object CosmosSpacetime {

    /** Radius of the sheet; the wells are windowed to zero between 0.85 R and R. */
    const val SHEET_RADIUS = 12f
    const val WINDOW_START = 0.85f * SHEET_RADIUS

    /** A body rests sunk this share of its radius into its own well... */
    const val SINK = 0.25f

    /** ...and at least this share of its radius above the sheet all round. */
    const val CLEAR = 0.1f

    /** How far the ringed world's rings stay above the sheet, world units. */
    const val RING_CLEARANCE = 0.014f

    /** How far below its rest the flat sheet starts, world units. */
    const val LIFT = 2f

    /** The star's scale in Spacetime: its radius goes from 1 to [STAR_RADIUS]. */
    const val STAR_SCALE = 0.45f

    /** Scene time frozen for QA captures and reduced motion. */
    const val QA_TIME = 9f

    /** The star and its seven worlds: what the shader's `bodies` and `spheres` arrays hold. */
    const val BODY_COUNT = 8

    // Body order: the star, then the worlds outward, the moons last.
    const val STAR = 0
    const val EMBER = 1
    const val AZURE = 2
    const val RINGED = 3
    const val OCHRE = 4
    const val ICE = 5
    const val MOON_O = 6
    const val MOON_I = 7

    val NAMES = arrayOf("Star", "Ember", "Azure", "Ringed", "Ochre", "Ice", "Moon-O", "Moon-I")

    /** Body radius ρ, world units. */
    val RADIUS = floatArrayOf(0.45f, 0.10f, 0.16f, 0.30f, 0.36f, 0.20f, 0.08f, 0.07f)

    /** Well depth D. */
    val DEPTH = floatArrayOf(2.4f, 0.60f, 0.70f, 0.80f, 0.75f, 0.50f, 0.40f, 0.40f)

    /** Well width ε (the Plummer softening). */
    val WIDTH = floatArrayOf(1.2f, 0.20f, 0.26f, 0.50f, 0.50f, 0.28f, 0.20f, 0.16f)

    /** Orbit radius round the star — or, for a moon, round its parent. */
    val ORBIT = floatArrayOf(0f, 1.3f, 2.0f, CosmosSystem.ORBIT_RADIUS, 5.1f, 6.9f, 0.85f, 0.60f)

    /**
     * Angle at t = 0, degrees, from +x toward −z. The ringed world's is [CosmosSystem.orbitAngle]'s;
     * the others are Spacetime's own, chosen so that at [QA_TIME] Ember and Azure sit in front of
     * the star, left of it, on the lit wall and clear of the hollow's shadow on screen.
     */
    val PHASE_DEGREES = floatArrayOf(0f, 342f, 100f, 7f, 120f, 300f, 90f, 0f)

    /** A moon's parent body, −1 for the star and the worlds. */
    val PARENT = intArrayOf(-1, -1, -1, -1, -1, -1, OCHRE, ICE)

    /** A moon's own angular speed round its parent, degrees per second. */
    val MOON_DEGREES_PER_SECOND = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 24f, 36f)

    /** The ringed world orbits at 6°/s; the others follow Kepler's third law from it. */
    private const val BASE_DEGREES_PER_SECOND = 6f

    // ─── Light ─────────────────────────────────────────────────────────────────────────────

    /** The key light grazes the sheet from the right and behind: azimuth 35° from +x to −z... */
    const val LIGHT_AZIMUTH_DEGREES = 35f

    /** ...14° above the sheet. */
    const val LIGHT_ELEVATION_DEGREES = 14f

    /** Unit vector toward the key light. */
    val LIGHT = floatArrayOf(
        cos(LIGHT_ELEVATION_DEGREES * DEG) * cos(LIGHT_AZIMUTH_DEGREES * DEG),
        sin(LIGHT_ELEVATION_DEGREES * DEG),
        -cos(LIGHT_ELEVATION_DEGREES * DEG) * sin(LIGHT_AZIMUTH_DEGREES * DEG),
    )

    /** How far away the worlds' "sun" is put along [LIGHT]: far enough to read as parallel. */
    const val SUN_DISTANCE = 1000f

    const val AMBIENT = 0.02f
    const val WRAP = 0.10f
    const val AO_STRENGTH = 0.2f
    const val AO_RADIUS = 2.4f
    const val SHADE_GAIN = 0.6f
    const val SHADE_CEILING = 1.25f
    const val SPOT_START = 3f

    /**
     * The horizon test: a march toward the light in even [HORIZON_STEP]s out to [HORIZON_REACH],
     * the steepest rise softened by ±[HORIZON_SOFTNESS] on the slope. Dense, so the shadow's
     * contour has no corners where the steepest sample hands over from one step to the next. It
     * walks every [HORIZON_COARSE]th step, then the steps round the steepest of those: the chord
     * slope along the march has a single peak, so that finds the dense march's answer.
     */
    const val HORIZON_STEP = 0.03f
    const val HORIZON_REACH = 6f
    const val HORIZON_SOFTNESS = 0.03f
    const val HORIZON_COARSE = 4

    /** The star's well alone, tabulated over the squared distance 0…R² for the horizon march. */
    const val STAR_PROFILE_SIZE = 16384

    /**
     * The horizon visibility is baked once into a [HORIZON_MAP_SIZE]² texture over the square
     * ±[HORIZON_MAP_EXTENT] round the star (the hollow's shadow spans x −1.2…3.0, z −2.7…1.3),
     * which the sheet samples per fragment with bilinear filtering. Outside it the light is clear.
     */
    const val HORIZON_MAP_SIZE = 512
    const val HORIZON_MAP_EXTENT = 4f

    /** `S` on flat, unshadowed sheet: what [SHADE_GAIN] scales to the base colour. */
    val SHADE_FLAT = AMBIENT + (1f - AMBIENT) * (LIGHT[1] + WRAP) / (1f + WRAP)

    /** The sheet's colour on the flat, lit plane, sRGB #6E7680 — the reference's grey. */
    const val SHEET_BASE_SRGB = 0xFF6E7680.toInt()

    // ─── Camera ────────────────────────────────────────────────────────────────────────────

    const val ELEVATION_DEGREES = 42f
    const val MIN_ELEVATION_DEGREES = 30f
    const val MAX_ELEVATION_DEGREES = 70f

    /** The star's centre sits this far down the frame... */
    const val STAR_SCREEN_Y = 0.58f

    /** ...and its disc spans this share of the frame's width. */
    const val STAR_SCREEN_WIDTH = 0.10f

    // ─── Grid ──────────────────────────────────────────────────────────────────────────────

    /** Rings of the polar grid: [INNER_RINGS] + 1 evenly spaced out to [INNER_EXTENT]... */
    const val INNER_RINGS = 76
    const val INNER_EXTENT = 7.6f

    /** ...then [OUTER_RINGS] in a geometric run out to the rim. */
    const val OUTER_RINGS = 18
    const val SECTORS = 416
    const val GRID_RINGS = INNER_RINGS + 1 + OUTER_RINGS
    const val GRID_VERTICES = GRID_RINGS * SECTORS
    const val GRID_TRIANGLES = 2 * (GRID_RINGS - 1) * SECTORS

    /** Floats per grid vertex: its position (x, 0, z). */
    const val GRID_STRIDE = 3

    /** The sheet's static bounds, y over every well depth and lift the entry passes through. */
    const val BOX_MIN_Y = -2.4f
    const val BOX_MAX_Y = 2.3f

    // ─── Entry sequence (seconds) ──────────────────────────────────────────────────────────

    /** The whole entry, forward; the way back plays it in reverse in [EXIT_SECONDS]. */
    const val ENTRY_SECONDS = 2.2f
    const val EXIT_SECONDS = 1.4f
    const val FLIGHT_SECONDS = 0.9f
    const val GLOW_OUT_SECONDS = 0.35f
    const val SHEET_IN_START = 0.2f
    const val WELL_START = 0.6f
    const val WELL_PEAK_SECONDS = 1.75f
    const val WELL_OVERSHOOT = 1.05f
    const val STARS_OUT_START = 0.9f
    const val STARS_OUT_END = 1.9f
    const val SPACETIME_BLOOM = 0.1f

    /** Seconds at which each world arrives (the ringed world is already there). */
    val ARRIVAL = floatArrayOf(0f, 0.80f, 0.95f, 0f, 1.10f, 1.25f, 1.10f, 1.25f)
    const val ARRIVAL_FADE_SECONDS = 0.2f
    const val ARRIVAL_FALL_SECONDS = 0.5f
    const val ARRIVAL_HEIGHT = 1f

    // ─── Orbits ────────────────────────────────────────────────────────────────────────────

    /** Angle of world [index] round the star (or a moon round its parent) at [time], radians. */
    fun angle(index: Int, time: Float): Float {
        val speed = if (PARENT[index] >= 0) {
            MOON_DEGREES_PER_SECOND[index]
        } else {
            BASE_DEGREES_PER_SECOND * (CosmosSystem.ORBIT_RADIUS / ORBIT[index]).pow(1.5f)
        }
        return (PHASE_DEGREES[index] + speed * time) * DEG
    }

    /** Centre of body [index] on the sheet's plane at [time], into [out] (x, z). */
    fun position(index: Int, time: Float, out: FloatArray) {
        if (index == STAR) {
            out[0] = 0f
            out[1] = 0f
            return
        }
        val a = angle(index, time)
        val parent = PARENT[index]
        var px = 0f
        var pz = 0f
        if (parent >= 0) {
            val p = angle(parent, time)
            px = ORBIT[parent] * cos(p)
            pz = -ORBIT[parent] * sin(p)
        }
        out[0] = px + ORBIT[index] * cos(a)
        out[1] = pz - ORBIT[index] * sin(a)
    }

    // ─── Profile ───────────────────────────────────────────────────────────────────────────

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** The rim window at distance [r] from the centre: 1 inside 0.85 R, 0 at R. */
    fun window(r: Float): Float = 1f - smoothstep(WINDOW_START, SHEET_RADIUS, r)

    /** One well of depth [depth] and width [width], unwindowed, at squared distance [d2]. */
    fun well(d2: Float, depth: Float, width: Float): Float {
        val e2 = width * width
        return -depth * width * (1f / sqrt(d2 + e2) - 1f / sqrt(SHEET_RADIUS * SHEET_RADIUS + e2))
    }

    // ─── Light, the CPU reference of the shader ────────────────────────────────────────────

    /**
     * Horizon visibility of the key light at ([x], [z]) over the star's well alone, at full depth:
     * 1 lit, 0 in the hollow's shadow. Baked once into [horizonMap].
     */
    fun horizonVisibility(x: Float, z: Float): Float {
        val lh = sqrt(LIGHT[0] * LIGHT[0] + LIGHT[2] * LIGHT[2])
        val lx = LIGHT[0] / lh
        val lz = LIGHT[2] / lh
        val h0 = starOnly(x, z)
        val te = tan(LIGHT_ELEVATION_DEGREES * DEG)
        val low = te - HORIZON_SOFTNESS
        val high = te + HORIZON_SOFTNESS
        // Two exits that leave the answer unchanged: the sheet never rises above 0, so no step
        // past −h0 / low can reach the penumbra; and a slope past it is full shadow already.
        val last = (min(HORIZON_REACH, -h0 / low) / HORIZON_STEP).toInt()
        var steepest = -Float.MAX_VALUE
        var peak = 0
        var k = HORIZON_COARSE
        while (k <= last && steepest < high) {
            val s = k * HORIZON_STEP
            val slope = (starOnly(x + s * lx, z + s * lz) - h0) / s
            if (slope > steepest) {
                steepest = slope
                peak = k
            }
            k += HORIZON_COARSE
        }
        // The steps either side of the steepest coarse one (all of the first few when none rose).
        val from = max(1, peak - HORIZON_COARSE + 1)
        val to = min(last, max(peak, 1) + HORIZON_COARSE - 1)
        for (fine in from..to) {
            if (fine % HORIZON_COARSE == 0) continue
            val s = fine * HORIZON_STEP
            val slope = (starOnly(x + s * lx, z + s * lz) - h0) / s
            if (slope > steepest) steepest = slope
        }
        return 1f - smoothstep(low, high, steepest)
    }

    /** Centre of horizon-map texel [i] along x (or z), world units. */
    fun horizonTexel(i: Int): Float = HORIZON_MAP_EXTENT * (2f * (i + 0.5f) / HORIZON_MAP_SIZE - 1f)

    /**
     * [horizonVisibility] over the map's square, one byte per texel (0 shadow, 255 lit), row by
     * row along z, each row along x: texel (i, j) is at ([horizonTexel] i, [horizonTexel] j).
     */
    fun horizonMap(): ByteArray = ByteArray(HORIZON_MAP_SIZE * HORIZON_MAP_SIZE).also {
        horizonRows(it, 0, HORIZON_MAP_SIZE)
    }

    /** Rows [from] until [until] of [horizonMap] into [map]: rows are independent, so bands bake in parallel. */
    fun horizonRows(map: ByteArray, from: Int, until: Int) {
        val n = HORIZON_MAP_SIZE
        for (j in from until until) {
            val z = horizonTexel(j)
            for (i in 0 until n) {
                map[j * n + i] = (horizonVisibility(horizonTexel(i), z) * 255f + 0.5f).toInt().toByte()
            }
        }
    }

    /** The star's well alone at ([x], [z]), read from [starProfile] (0 past the rim). */
    private fun starOnly(x: Float, z: Float): Float {
        val f = (x * x + z * z) * PROFILE_PER_D2
        if (f >= STAR_PROFILE_SIZE - 1) return 0f
        val k = f.toInt()
        return starProfile[k] + (starProfile[k + 1] - starProfile[k]) * (f - k)
    }

    private const val PROFILE_PER_D2 = (STAR_PROFILE_SIZE - 1) / (SHEET_RADIUS * SHEET_RADIUS)

    private val starProfile = FloatArray(STAR_PROFILE_SIZE) { k ->
        val d2 = k / PROFILE_PER_D2
        well(d2, DEPTH[STAR], WIDTH[STAR]) * window(sqrt(d2))
    }

    /**
     * The light term `S` at a point of normal (−[gx], 1, −[gz]) and distance [starDistance] from
     * the star, before shadows from the worlds: `(a + (1−a)·wrap(n·L)·vis)·ao`.
     */
    fun shade(gx: Float, gz: Float, visibility: Float, starDistance: Float): Float {
        val m = sqrt(gx * gx + 1f + gz * gz)
        val ndl = (-gx * LIGHT[0] + LIGHT[1] - gz * LIGHT[2]) / m
        val lit = max(0f, (ndl + WRAP) / (1f + WRAP))
        val ao = 1f - AO_STRENGTH * (1f - smoothstep(0f, AO_RADIUS, starDistance))
        return (AMBIENT + (1f - AMBIENT) * lit * visibility) * ao
    }

    /** What the base colour is multiplied by on screen (linear): gain, ceiling and the star's spot. */
    fun screenFactor(shade: Float, starDistance: Float): Float =
        min(SHADE_GAIN * shade / SHADE_FLAT, SHADE_CEILING) * (1f - smoothstep(SPOT_START, SHEET_RADIUS, starDistance))

    // ─── Grid ──────────────────────────────────────────────────────────────────────────────

    /** Radius of grid ring [ring]. */
    fun ringRadius(ring: Int): Float = if (ring <= INNER_RINGS) {
        INNER_EXTENT * ring / INNER_RINGS
    } else {
        INNER_EXTENT * (SHEET_RADIUS / INNER_EXTENT).pow((ring - INNER_RINGS).toFloat() / OUTER_RINGS)
    }

    /** The sheet's polar grid, flat: the vertex stage hollows it. */
    class Grid(val vertices: FloatArray, val indices: ShortArray)

    fun grid(): Grid {
        val vertices = FloatArray(GRID_VERTICES * GRID_STRIDE)
        var v = 0
        for (ring in 0 until GRID_RINGS) {
            val r = ringRadius(ring)
            for (s in 0 until SECTORS) {
                val a = 2f * PI.toFloat() * s / SECTORS
                val x = r * cos(a)
                val z = r * sin(a)
                vertices[v++] = x
                vertices[v++] = 0f
                vertices[v++] = z
            }
        }
        val indices = ShortArray(GRID_TRIANGLES * 3)
        var i = 0
        for (ring in 0 until GRID_RINGS - 1) {
            for (s in 0 until SECTORS) {
                val s1 = (s + 1) % SECTORS
                val a = ring * SECTORS + s
                val b = ring * SECTORS + s1
                val c = (ring + 1) * SECTORS + s
                val d = (ring + 1) * SECTORS + s1
                // Counter-clockwise seen from above: the sheet's one face looks up.
                indices[i++] = a.toShort()
                indices[i++] = b.toShort()
                indices[i++] = c.toShort()
                indices[i++] = b.toShort()
                indices[i++] = d.toShort()
                indices[i++] = c.toShort()
            }
        }
        return Grid(vertices, indices)
    }

    // ─── Camera ────────────────────────────────────────────────────────────────────────────

    /** Distance of the eye from the star: its disc spans [STAR_SCREEN_WIDTH] of the width. */
    fun cameraDistance(aspect: Float): Float =
        RADIUS[STAR] / (STAR_SCREEN_WIDTH * CosmosSystem.TAN_HALF_VERTICAL_FOV * aspect.coerceAtLeast(0.1f))

    /**
     * The Spacetime camera into [out] (eye, target, up): [elevationDegrees] above the sheet,
     * turned [yawDegrees] round it, looking a little above the star so it sits at [STAR_SCREEN_Y].
     */
    fun pose(aspect: Float, yawDegrees: Float, elevationDegrees: Float, out: FloatArray): FloatArray {
        val d = cameraDistance(aspect)
        val phi = elevationDegrees * DEG
        val yaw = yawDegrees * DEG
        val ndc = 1f - 2f * STAR_SCREEN_Y
        val pitch = phi - atan(-ndc * CosmosSystem.TAN_HALF_VERTICAL_FOV)
        val sy = sin(yaw)
        val cy = cos(yaw)
        // Unturned: eye on +z, forward down and toward −z, up tipped back.
        val ex = 0f
        val ey = d * sin(phi)
        val ez = d * cos(phi)
        val fy = -sin(pitch)
        val fz = -cos(pitch)
        val uy = cos(pitch)
        val uz = -sin(pitch)
        out[0] = ex * cy + ez * sy
        out[1] = ey
        out[2] = ez * cy - ex * sy
        out[3] = out[0] + d * fz * sy
        out[4] = ey + d * fy
        out[5] = out[2] + d * fz * cy
        out[6] = uz * sy
        out[7] = uy
        out[8] = uz * cy
        return out
    }

    // ─── Entry sequence ────────────────────────────────────────────────────────────────────

    /** The camera flight, the orbit plane laying down, the star shrinking, the sheet rising: 0 → 1. */
    fun flight(clock: Float): Float = CosmosSystem.easeExpressive(clock / FLIGHT_SECONDS)

    /** The halo, prominences and orbit trail: gone before the sheet reaches them. */
    fun glow(clock: Float): Float = 1f - smoothstep(0f, GLOW_OUT_SECONDS, clock)

    /** The sheet's master intensity. */
    fun sheetIntensity(clock: Float): Float = smoothstep(SHEET_IN_START, FLIGHT_SECONDS, clock)

    /**
     * The well depth `w`: 0 until [WELL_START], up to [WELL_OVERSHOOT] at [WELL_PEAK_SECONDS]
     * (ease out), and back to 1 at [ENTRY_SECONDS] — the sheet gives a little, then settles.
     */
    fun wellDepth(clock: Float): Float = when {
        clock <= WELL_START -> 0f
        clock < WELL_PEAK_SECONDS -> {
            val u = (clock - WELL_START) / (WELL_PEAK_SECONDS - WELL_START)
            val v = 1f - u
            WELL_OVERSHOOT * (1f - v * v * v)
        }
        else -> WELL_OVERSHOOT - (WELL_OVERSHOOT - 1f) *
            smoothstep(WELL_PEAK_SECONDS, ENTRY_SECONDS, clock)
    }

    /** The star field behind: out once the sheet is up, the background is pure black. */
    fun starField(clock: Float): Float = 1f - smoothstep(STARS_OUT_START, STARS_OUT_END, clock)

    /** How far world [index] has faded in, 0 → 1 (the star and the ringed world are always there). */
    fun arrival(index: Int, clock: Float): Float {
        if (index == STAR || index == RINGED) return 1f
        return ((clock - ARRIVAL[index]) / ARRIVAL_FADE_SECONDS).coerceIn(0f, 1f)
    }

    /** How high above its rest world [index] still is: it falls from [ARRIVAL_HEIGHT], ease-in. */
    fun fallHeight(index: Int, clock: Float): Float {
        if (index == STAR || index == RINGED) return 0f
        val u = ((clock - ARRIVAL[index]) / ARRIVAL_FALL_SECONDS).coerceIn(0f, 1f)
        return ARRIVAL_HEIGHT * (1f - u * u)
    }

    /** The bloom strength on the way in, from the user's [bloom] down to [SPACETIME_BLOOM]. */
    fun bloom(bloom: Float, clock: Float): Float {
        val f = flight(clock)
        return bloom + (SPACETIME_BLOOM - bloom) * f
    }

    // ─── Orientation ───────────────────────────────────────────────────────────────────────

    /**
     * The ringed world's orientation in Spacetime into [out] (x, y, z, w): its spin axis, and so
     * its rings' plane, along [normal] (unit), spun about it at the Starlight spin rate.
     */
    fun ringedRotation(normal: FloatArray, time: Float, out: FloatArray): FloatArray {
        // The tilt takes +y onto the normal, about y × n = (n.z, 0, −n.x).
        val ax = normal[2]
        val az = -normal[0]
        val s = sqrt(ax * ax + az * az)
        val half = acos(normal[1].coerceIn(-1f, 1f)) / 2f
        val k = if (s < 1e-6f) 0f else sin(half) / s
        val tx = ax * k
        val tz = az * k
        val tw = cos(half)
        val spin = CosmosRig.PLANET_SPIN_DEGREES_PER_SECOND * time * DEG / 2f
        val sy = sin(spin)
        val cy = cos(spin)
        // tilt × spin, the spin (0, sy, 0, cy) applied first.
        out[0] = tx * cy - tz * sy
        out[1] = tw * sy
        out[2] = tx * sy + tz * cy
        out[3] = tw * cy
        return out
    }

    /** Spherical interpolation of unit quaternions [a] → [b] at [t] into [out], the short way round. */
    fun slerp(a: FloatArray, b: FloatArray, t: Float, out: FloatArray): FloatArray {
        var d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        val sign = if (d < 0f) -1f else 1f
        d *= sign
        val wa: Float
        val wb: Float
        if (d > SLERP_LINEAR) {
            wa = 1f - t
            wb = t * sign
        } else {
            val theta = acos(d)
            val st = sin(theta)
            wa = sin((1f - t) * theta) / st
            wb = sin(t * theta) / st * sign
        }
        var m = 0f
        for (i in 0..3) {
            out[i] = wa * a[i] + wb * b[i]
            m += out[i] * out[i]
        }
        val inv = 1f / sqrt(m)
        for (i in 0..3) out[i] *= inv
        return out
    }

    private const val SLERP_LINEAR = 0.9995f

    // ─── Colour ────────────────────────────────────────────────────────────────────────────

    /** Narkowicz's ACES fit, Filament's `Filmic` tone mapper. */
    fun filmic(x: Float): Float = (x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f)

    /** The linear value [filmic] maps to [y]: what the sheet writes so the screen shows [y]. */
    fun inverseFilmic(y: Float): Float {
        val c = y.coerceIn(0f, 0.999f)
        return (0.03f - 0.59f * c - sqrt(0.0009f + 1.3702f * c - 1.0127f * c * c)) / (-5.02f + 4.86f * c)
    }

    /** sRGB channel 0–255 to linear. */
    fun srgbToLinear(channel: Int): Float {
        val c = channel / 255f
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    /** WCAG relative luminance of an sRGB colour (0xAARRGGBB). */
    fun luminance(argb: Int): Float =
        0.2126f * srgbToLinear(argb shr 16 and 0xFF) +
            0.7152f * srgbToLinear(argb shr 8 and 0xFF) +
            0.0722f * srgbToLinear(argb and 0xFF)

    /** WCAG contrast ratio between two luminances. */
    fun contrast(a: Float, b: Float): Float = (max(a, b) + 0.05f) / (min(a, b) + 0.05f)

    /** The sheet's base colour as linear rgb, the screen value [screenFactor] scales. */
    fun sheetBaseLinear(): FloatArray = floatArrayOf(
        srgbToLinear(SHEET_BASE_SRGB shr 16 and 0xFF),
        srgbToLinear(SHEET_BASE_SRGB shr 8 and 0xFF),
        srgbToLinear(SHEET_BASE_SRGB and 0xFF),
    )
}

/**
 * The sheet at one moment — body positions, well depth, offset — and every rest height derived
 * from it, without allocating: the render loop holds one and [prepare]s it each frame. The
 * [bodies] array is laid out as the shader's `bodies` uniform, (x, z, D·w, ε) per body.
 *
 * Not thread-safe: one per render loop (tests make their own).
 */
internal class SpacetimeField {
    /** (x, z, D·w, ε) per body: the shader's `bodies` uniform. */
    val bodies = FloatArray(CosmosSpacetime.BODY_COUNT * 4)

    /** `C(w)`: minus the star's rest height, so the star's centre stays at the origin. */
    var offset = 0f
        private set

    /** The ring plane's normal at the ringed world, after [ringedRest]. */
    val ringNormal = FloatArray(3)

    private val xz = FloatArray(2)
    private val gradient = FloatArray(2)
    private val u = FloatArray(3)
    private val v = FloatArray(3)

    /** Lays the sheet out at [time] with well depth [well]. */
    fun prepare(time: Float, well: Float) {
        for (i in 0 until CosmosSpacetime.BODY_COUNT) {
            CosmosSpacetime.position(i, time, xz)
            bodies[i * 4] = xz[0]
            bodies[i * 4 + 1] = xz[1]
            bodies[i * 4 + 2] = CosmosSpacetime.DEPTH[i] * well
            bodies[i * 4 + 3] = CosmosSpacetime.WIDTH[i]
        }
        offset = 0f
        offset = -restAbove(0f, 0f, CosmosSpacetime.RADIUS[CosmosSpacetime.STAR])
    }

    fun x(index: Int) = bodies[index * 4]
    fun z(index: Int) = bodies[index * 4 + 1]

    /** The windowed profile `F`, without the offset. */
    fun field(x: Float, z: Float): Float {
        var h = 0f
        for (i in 0 until CosmosSpacetime.BODY_COUNT) {
            val dx = x - bodies[i * 4]
            val dz = z - bodies[i * 4 + 1]
            h += CosmosSpacetime.well(dx * dx + dz * dz, bodies[i * 4 + 2], bodies[i * 4 + 3])
        }
        return h * CosmosSpacetime.window(sqrt(x * x + z * z))
    }

    /** The sheet at rest (`lift` 0): `F + C`. */
    fun height(x: Float, z: Float): Float = field(x, z) + offset

    /** ∂H/∂x and ∂H/∂z into [out], the window's own slope included. */
    fun gradient(x: Float, z: Float, out: FloatArray) {
        var raw = 0f
        var gx = 0f
        var gz = 0f
        for (i in 0 until CosmosSpacetime.BODY_COUNT) {
            val dx = x - bodies[i * 4]
            val dz = z - bodies[i * 4 + 1]
            val depth = bodies[i * 4 + 2]
            val width = bodies[i * 4 + 3]
            val d2 = dx * dx + dz * dz
            raw += CosmosSpacetime.well(d2, depth, width)
            val s = 1f / sqrt(d2 + width * width)
            val k = depth * width * s * s * s
            gx += k * dx
            gz += k * dz
        }
        val r = sqrt(x * x + z * z)
        val w = CosmosSpacetime.window(r)
        gx *= w
        gz *= w
        if (r > 0f) {
            val dw = -windowSlope(r)
            gx += raw * dw * x / r
            gz += raw * dw * z / r
        }
        out[0] = gx
        out[1] = gz
    }

    private fun windowSlope(r: Float): Float {
        val a = CosmosSpacetime.WINDOW_START
        val b = CosmosSpacetime.SHEET_RADIUS
        if (r <= a || r >= b) return 0f
        val t = (r - a) / (b - a)
        return 6f * t * (1f - t) / (b - a)
    }

    /**
     * Where a ball of radius [radius] centred over ([cx], [cz]) rests: sunk [CosmosSpacetime.SINK]
     * of its radius into the sheet under its centre, but clear of it all round its rim.
     */
    fun restAbove(cx: Float, cz: Float, radius: Float): Float {
        var rim = -Float.MAX_VALUE
        for (i in 0 until REST_SAMPLES) {
            val a = 2f * PI.toFloat() * i / REST_SAMPLES
            rim = max(rim, height(cx + radius * cos(a), cz + radius * sin(a)))
        }
        return max(height(cx, cz) + radius * (1f - CosmosSpacetime.SINK), rim + CosmosSpacetime.CLEAR * radius)
    }

    /** Rest height of body [index]. */
    fun rest(index: Int): Float = if (index == CosmosSpacetime.RINGED) {
        ringedRest()
    } else {
        restAbove(x(index), z(index), CosmosSpacetime.RADIUS[index])
    }

    /**
     * The ringed world's rest height. Its rings lie along the sheet's mean slope round it
     * ([ringNormal]), and the world is raised until both ring edges clear the sheet by
     * [CosmosSpacetime.RING_CLEARANCE].
     */
    fun ringedRest(): Float {
        val cx = x(CosmosSpacetime.RINGED)
        val cz = z(CosmosSpacetime.RINGED)
        val y = restAbove(cx, cz, CosmosSpacetime.RADIUS[CosmosSpacetime.RINGED])
        ringPlane(cx, cz)
        val gap = ringGap(cx, y, cz)
        return y + max(0f, CosmosSpacetime.RING_CLEARANCE - gap)
    }

    /** Lowest height of the ring edges above the sheet with the world centred at ([cx], [y], [cz]). */
    fun ringGap(cx: Float, y: Float, cz: Float): Float {
        var worst = Float.MAX_VALUE
        for (radius in RING_EDGES) {
            for (i in 0 until GAP_SAMPLES) {
                val a = 2f * PI.toFloat() * i / GAP_SAMPLES
                val ca = cos(a)
                val sa = sin(a)
                val qx = cx + radius * (ca * u[0] + sa * v[0])
                val qy = y + radius * (ca * u[1] + sa * v[1])
                val qz = cz + radius * (ca * u[2] + sa * v[2])
                worst = min(worst, qy - height(qx, qz))
            }
        }
        return worst
    }

    /** The ring plane at ([cx], [cz]): normal from the mean gradient under both ring edges. */
    private fun ringPlane(cx: Float, cz: Float) {
        var gx = 0f
        var gz = 0f
        for (radius in RING_EDGES) {
            for (i in 0 until PLANE_SAMPLES) {
                val a = 2f * PI.toFloat() * i / PLANE_SAMPLES
                gradient(cx + radius * cos(a), cz + radius * sin(a), gradient)
                gx += gradient[0]
                gz += gradient[1]
            }
        }
        val n = (2 * PLANE_SAMPLES).toFloat()
        gx /= n
        gz /= n
        val m = sqrt(gx * gx + 1f + gz * gz)
        ringNormal[0] = -gx / m
        ringNormal[1] = 1f / m
        ringNormal[2] = -gz / m
        // u: +x projected onto the plane; v = n × u.
        val d = ringNormal[0]
        val ux = 1f - d * ringNormal[0]
        val uy = -d * ringNormal[1]
        val uz = -d * ringNormal[2]
        val ul = sqrt(ux * ux + uy * uy + uz * uz)
        u[0] = ux / ul
        u[1] = uy / ul
        u[2] = uz / ul
        v[0] = ringNormal[1] * u[2] - ringNormal[2] * u[1]
        v[1] = ringNormal[2] * u[0] - ringNormal[0] * u[2]
        v[2] = ringNormal[0] * u[1] - ringNormal[1] * u[0]
    }

    private companion object {
        const val REST_SAMPLES = 48
        const val PLANE_SAMPLES = 24
        const val GAP_SAMPLES = 96
        val RING_EDGES = floatArrayOf(CosmosSystem.RING_INNER, CosmosSystem.RING_OUTER)
    }
}

/**
 * Where the Star scene is between Starlight and Spacetime, in seconds of the entry sequence:
 * 0 is Starlight, [CosmosSpacetime.ENTRY_SECONDS] is Spacetime at rest. Entering runs it forward,
 * leaving runs it back [CosmosSpacetime.ENTRY_SECONDS] / [CosmosSpacetime.EXIT_SECONDS] times
 * faster; a change of mind half-way turns round from where it is. Allocation-free.
 */
internal class SpacetimeTransition {
    var target = false
        private set

    /** Seconds into the entry sequence. */
    var clock = 0f
        private set

    private var lastNanos = 0L

    /** True once the sheet is (even partly) on screen. */
    val active: Boolean get() = clock > 0f

    /** True once Spacetime is at rest. */
    val settled: Boolean get() = clock >= CosmosSpacetime.ENTRY_SECONDS

    fun enter(on: Boolean) {
        target = on
    }

    /** Advances toward [target]; [instant] lands there at once (QA, reduced motion). */
    fun advance(nanos: Long, instant: Boolean) {
        val dt = if (lastNanos == 0L) 0f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, MAX_STEP_SECONDS)
        lastNanos = nanos
        clock = when {
            instant -> if (target) CosmosSpacetime.ENTRY_SECONDS else 0f
            target -> (clock + dt).coerceAtMost(CosmosSpacetime.ENTRY_SECONDS)
            else -> (clock - dt * CosmosSpacetime.ENTRY_SECONDS / CosmosSpacetime.EXIT_SECONDS).coerceAtLeast(0f)
        }
    }

    private companion object {
        const val MAX_STEP_SECONDS = 0.1f
    }
}
