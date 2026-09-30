package io.github.sceneview.demo.demos.internal

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

// ─── The voyage as data ──────────────────────────────────────────────────────────────────────
//
// The Cosmos **Voyage** is a camera tour written as a table, not as code: one [VoyageShot] per
// scene, each a continuous take through a handful of [VoyageKey]s. Everything a key says is a
// plain number in the scene's own units, so the table ports to another platform by copying it,
// and the evaluator ([CosmosVoyageCamera]) is a few hundred lines of vector maths with no
// engine call in it.

/**
 * The frame a key's positions are written in. Each frame moves with what it is attached to, so
 * a key holds its place relative to a planet that orbits under it.
 */
internal enum class VoyageAnchor {
    /** The scene's own coordinates: the galaxy, the burst and the star sit at the origin, y up. */
    World,

    /**
     * The ringed world's orbit plane, star at the origin: the planet runs round it at
     * `(R cos a, 0, -R sin a)`, and y is the orbit's normal. Its tilt depends on the viewport
     * aspect ([CosmosRig.orbitFrame]), so keys written here keep their place relative to the
     * orbit on every screen.
     */
    Orbit,

    /**
     * Centred on the ringed world: y along its spin axis (the rings lie in y = 0), x away from
     * the star (in the ring plane), z = x × y, which trails the planet along its orbit.
     */
    Planet,

    /**
     * The scene's free-camera framing (the pose a user gets back when they take over), in that
     * camera's own axes: x right, y up, z backward. Zero offsets are the framing itself. The
     * framing is already fitted to the viewport, so the focal length of these keys is not
     * scaled for narrow screens (see [CosmosVoyageCamera.REFERENCE_ASPECT]).
     */
    Framing,
}

/** How a shot hands over to the next one. */
internal enum class VoyageExit {
    /** The next scene fades in over the last one (the scene switch's own reveal). */
    Cut,

    /** The scene fades to black over the shot's last [CosmosVoyageCamera.FADE_OUT_SECONDS]. */
    Fade,

    /**
     * A jump: the camera surges forward, the lens widens and light streaks past, over the
     * shot's last [CosmosVoyageCamera.WARP_OUT_SECONDS]; the next shot arrives out of the
     * streaks, decelerating, over its first [CosmosVoyageCamera.WARP_IN_SECONDS].
     */
    Warp,
}

/**
 * One keyframe of a shot.
 *
 * @param at seconds from the start of the shot (keys are in increasing order).
 * @param anchor the frame [eye] and [target] are written in.
 * @param eye camera position.
 * @param target the point the camera looks at.
 * @param focal lens focal length in mm on a 24 mm-high sensor (28 is the scene's default lens).
 * @param roll degrees the camera turns about its view axis, clockwise as seen by the viewer.
 * @param ahead 0 looks at [target], 1 looks along the camera's own path: a travelling shot
 *   that anticipates where it is going. In between blends the two.
 * @param caption shown under the scene from this key on; null keeps the previous one.
 */
@Suppress("LongParameterList")
internal class VoyageKey(
    val at: Float,
    val anchor: VoyageAnchor,
    val eye: FloatArray,
    val target: FloatArray,
    val focal: Float = CosmosVoyageCamera.DEFAULT_FOCAL,
    val roll: Float = 0f,
    val ahead: Float = 0f,
    val caption: String? = null,
)

/**
 * One continuous take in one scene.
 *
 * @param scene the scene on screen for the whole shot.
 * @param keys at least two; the shot lasts until the last one.
 * @param shake handheld sway, in degrees: a few slow, incommensurate sines on the aim and roll,
 *   so a long glide never feels mechanical. 0 is a locked-off camera.
 * @param exit how it hands over to the next shot.
 */
internal class VoyageShot(
    val scene: CosmosScene,
    val keys: List<VoyageKey>,
    val shake: Float = 0f,
    val exit: VoyageExit = VoyageExit.Cut,
) {
    init {
        require(keys.size >= 2) { "a shot needs at least two keys" }
        require(keys.zipWithNext().all { (a, b) -> b.at > a.at }) { "keys must be in increasing time" }
    }

    val seconds: Float get() = keys.last().at
}

private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

private val ORIGIN = v(0f, 0f, 0f)

/**
 * The voyage: Galaxy → (warp) → Burst → (fade) → Flow → (warp) → Star → (fade) → Galaxy, in a
 * loop. Numbers are in each scene's units (the plasma star is a unit sphere, the galaxy's disc
 * is about 1 across the radius) and framed for a portrait phone; see [CosmosVoyageCamera] for
 * how they adapt to other screens.
 */
internal object CosmosVoyage {

    val SHOTS: List<VoyageShot> = listOf(
        // From inside the disc, the camera pulls back and rises until the whole spiral turns
        // below it, then swoops down to skim the arms and dives at the core.
        VoyageShot(
            scene = CosmosScene.Galaxy,
            shake = 0.35f,
            exit = VoyageExit.Warp,
            keys = listOf(
                VoyageKey(0f, VoyageAnchor.World, v(0.18f, 0.2f, 0.62f), ORIGIN, focal = 20f,
                    caption = "Inside a spiral galaxy of 60,000 stars"),
                VoyageKey(4f, VoyageAnchor.World, v(0.5f, 2.4f, 1.9f), ORIGIN, focal = 24f,
                    caption = "Pulling back to see it whole"),
                VoyageKey(8.5f, VoyageAnchor.World, v(-0.9f, 5.3f, 1.4f), ORIGIN, focal = 26f, roll = -8f),
                VoyageKey(12.5f, VoyageAnchor.World, v(1.9f, 0.62f, 1.55f), ORIGIN, focal = 28f,
                    caption = "Skimming the arms toward the core"),
                VoyageKey(15.5f, VoyageAnchor.World, v(0.95f, 0.13f, 0.62f), ORIGIN, focal = 24f, roll = 6f,
                    ahead = 0.35f),
                VoyageKey(18f, VoyageAnchor.World, v(0.42f, 0.06f, 0.2f), ORIGIN, focal = 22f, ahead = 0.2f),
            ),
        ),
        // Out of the jump into the collision point: the tracks fly past the lens, then the
        // camera backs away and round as the second burst goes off.
        VoyageShot(
            scene = CosmosScene.Burst,
            shake = 0.3f,
            exit = VoyageExit.Fade,
            keys = listOf(
                VoyageKey(0f, VoyageAnchor.World, v(0.22f, 0.12f, 1.0f), ORIGIN, focal = 20f,
                    caption = "Inside a particle collision"),
                VoyageKey(3.5f, VoyageAnchor.World, v(1.3f, 0.55f, 2.3f), ORIGIN, focal = 22f),
                VoyageKey(7.5f, VoyageAnchor.World, v(-0.7f, 1.2f, 4.4f), ORIGIN, focal = 24f,
                    caption = "A particle collision, traced"),
                VoyageKey(11f, VoyageAnchor.World, v(-2.4f, 0.8f, 5.6f), ORIGIN, focal = 24f, roll = -4f),
            ),
        ),
        // The flow field has an edge, so the camera stays on its framing and only pushes in:
        // a longer lens and a small drift of the aim, never a wider view.
        VoyageShot(
            scene = CosmosScene.Flow,
            shake = 0.2f,
            exit = VoyageExit.Warp,
            keys = listOf(
                VoyageKey(0f, VoyageAnchor.Framing, ORIGIN, ORIGIN, focal = 28f,
                    caption = "Currents swirling into whirlpools"),
                VoyageKey(5f, VoyageAnchor.Framing, ORIGIN, v(0.06f, 0.08f, 0f), focal = 36f),
                VoyageKey(10f, VoyageAnchor.Framing, ORIGIN, v(-0.04f, 0.14f, 0f), focal = 46f),
            ),
        ),
        // Out of the jump facing the blue star; round it close enough for its loops, then out to
        // the ringed world, low over its rings with the star behind it, and back until the whole
        // system is a jewel in the dark.
        VoyageShot(
            scene = CosmosScene.Star,
            shake = 0.3f,
            exit = VoyageExit.Fade,
            keys = listOf(
                VoyageKey(0f, VoyageAnchor.Orbit, v(0.7f, 0.6f, 5.4f), ORIGIN, focal = 26f,
                    caption = "A hot blue star and its magnetic loops"),
                VoyageKey(4f, VoyageAnchor.Orbit, v(3.1f, 1.0f, 3.2f), ORIGIN, focal = 30f, roll = 6f),
                VoyageKey(8f, VoyageAnchor.Orbit, v(4.6f, 1.6f, 0.2f), ORIGIN, focal = 30f),
                VoyageKey(11.5f, VoyageAnchor.Planet, v(2.3f, 0.7f, 1.7f), ORIGIN, focal = 30f,
                    caption = "A ringed world in blue starlight"),
                VoyageKey(15f, VoyageAnchor.Planet, v(1.05f, 0.16f, 0.62f), v(-0.1f, 0f, -0.15f), focal = 26f,
                    roll = -5f, caption = "Skimming its rings"),
                VoyageKey(18.5f, VoyageAnchor.Planet, v(0.28f, 0.14f, 1.12f), v(-0.3f, 0.02f, -0.1f), focal = 24f),
                VoyageKey(21.5f, VoyageAnchor.Planet, v(-0.2f, 1.5f, 3.4f), v(-1.2f, 0f, -0.4f), focal = 26f,
                    caption = "Pulling back to the whole system"),
                VoyageKey(25f, VoyageAnchor.Framing, ORIGIN, ORIGIN, focal = 28f),
                VoyageKey(28f, VoyageAnchor.Framing, v(0f, 0.8f, 7f), ORIGIN, focal = 24f),
            ),
        ),
    )

    /** The shot of [scene]: the voyage visits every scene once. */
    fun shotOf(scene: CosmosScene): VoyageShot = SHOTS.first { it.scene == scene }

    /** The scene the voyage goes to after [scene]. */
    fun next(scene: CosmosScene): CosmosScene {
        val index = SHOTS.indexOfFirst { it.scene == scene }
        return SHOTS[(index + 1) % SHOTS.size].scene
    }

    /** How the voyage arrives in [scene]: out of a jump, or by the scene's own fade-in. */
    fun arrivesByWarp(scene: CosmosScene): Boolean {
        val index = SHOTS.indexOfFirst { it.scene == scene }
        return SHOTS[(index - 1 + SHOTS.size) % SHOTS.size].exit == VoyageExit.Warp
    }

    /**
     * The light streaks of a jump: straight strokes parallel to the view axis, in the camera's
     * own frame, ahead of and behind it (the same mesh reads right whichever way the node's
     * forward axis points). Drawn with the ribbon material, whose travelling pulses run from each
     * stroke's far end toward the lens.
     */
    fun warpStreaks(count: Int = 150, points: Int = 14, seed: Int = 11): GlowMesh {
        val rnd = Random(seed)
        val builder = RibbonBuilder(count * 2 * points)
        val xyz = FloatArray(points * 3)
        val rgb = FloatArray(points * 3)
        repeat(count) {
            // More strokes near the axis than far out: the tunnel narrows toward its vanishing point.
            val radius = STREAK_MIN_RADIUS + (STREAK_MAX_RADIUS - STREAK_MIN_RADIUS) * rnd.nextFloat().let { it * it }
            val angle = rnd.nextFloat() * 2f * Math.PI.toFloat()
            val near = STREAK_NEAR + rnd.nextFloat() * 2f
            val far = near + STREAK_LENGTH * (0.4f + 0.6f * rnd.nextFloat())
            val warm = rnd.nextFloat()
            val gain = 0.5f + rnd.nextFloat()
            val seedValue = rnd.nextFloat()
            for (side in floatArrayOf(-1f, 1f)) {
                for (i in 0 until points) {
                    val u = i.toFloat() / (points - 1)
                    xyz[i * 3] = radius * cos(angle)
                    xyz[i * 3 + 1] = radius * sin(angle)
                    // From the far end toward the lens, so the pulses fly at the viewer.
                    xyz[i * 3 + 2] = side * (far + (near - far) * u)
                    // Dim at both ends, brightest two-thirds of the way in.
                    val shape = sin(u * Math.PI.toFloat()).let { it * it } * (0.35f + 0.65f * u)
                    rgb[i * 3] = gain * shape * (0.35f + 0.5f * warm)
                    rgb[i * 3 + 1] = gain * shape * (0.6f + 0.2f * warm)
                    rgb[i * 3 + 2] = gain * shape * 1.6f
                }
                builder.addCurve(xyz, rgb, halfWidth = STREAK_HALF_WIDTH, seed = seedValue)
            }
        }
        return builder.build()
    }

    private const val STREAK_MIN_RADIUS = 0.25f
    private const val STREAK_MAX_RADIUS = 2.6f
    private const val STREAK_NEAR = 0.6f
    private const val STREAK_LENGTH = 26f
    private const val STREAK_HALF_WIDTH = 0.006f
}

/**
 * Evaluates the voyage table into a camera pose, allocation-free: one instance per render loop.
 *
 * ### How a frame is computed
 *
 * 1. The four keys around the current time are **resolved** into world space at the current
 *    scene time — anchors move (the planet orbits), so this happens every frame. Roll turns each
 *    key's up vector about its view axis.
 * 2. Eye, target, up and focal length are interpolated with a **non-uniform Catmull-Rom**
 *    (cubic Hermite, tangents from the neighbouring keys and their times): the camera's speed
 *    is continuous through every key, and keys spaced closer in time make it slow down.
 * 3. **Look-ahead**: the eye is sampled again [LOOK_AHEAD_SECONDS] later; `ahead` turns the aim
 *    toward that point, kept at the key's own aim distance.
 * 4. **Handheld**: [VoyageShot.shake] degrees of sway on the aim and half of it on the roll,
 *    from sums of incommensurate sines — deterministic, so tests and captures are reproducible.
 * 5. **Transitions**: a warp pushes eye and target along the view axis (cubic in, then out),
 *    halves the focal length and brings the streaks up; a fade brings [fade] down.
 * 6. **Narrow screens**: keys are framed for a [REFERENCE_ASPECT] portrait phone; on a narrower
 *    viewport the focal length shortens in proportion so the width still fits. Wider screens
 *    keep the vertical framing and see more on the sides.
 */
@Suppress("TooManyFunctions")
internal class CosmosVoyageCamera(private val rig: CosmosRig = CosmosRig()) {

    /** Eye, target, up — nine floats, as [CosmosFraming.pose]. */
    val pose = FloatArray(CosmosSystem.POSE_FLOATS)

    /** Focal length in mm for the camera's lens. */
    var focal = DEFAULT_FOCAL
        private set

    /** Master brightness of the scene, 0 during a fade's or a warp's black. */
    var fade = 1f
        private set

    /** Brightness of the warp streaks, 0 outside a jump. */
    var streaks = 0f
        private set

    /** The caption of the last key passed, or of the first one. */
    var caption: String = ""
        private set

    // Four resolved keys (eye 3, target 3, up 3, focal, ahead) and the sampled result.
    private val slots = FloatArray(4 * SLOT)
    private val times = FloatArray(4)
    private val sample = FloatArray(SLOT)
    private val later = FloatArray(SLOT)

    // Scratch.
    private val va = FloatArray(3)
    private val vb = FloatArray(3)
    private val vc = FloatArray(3)
    private val vd = FloatArray(3)
    private val basis = FloatArray(9)
    private val slotKeys = IntArray(4)

    /**
     * The camera at [time] seconds into [shot], with the scene's own clock at [sceneTime] (the
     * planet's orbit runs on it) on a viewport of width / height [aspect]. [arrivedByWarp] plays
     * the end of the jump that brought the voyage here over the shot's first seconds.
     */
    fun evaluate(shot: VoyageShot, time: Float, sceneTime: Float, aspect: Float, arrivedByWarp: Boolean) {
        val t = time.coerceIn(0f, shot.seconds)
        sampleAt(shot, t, sceneTime, aspect, sample)
        caption = captionAt(shot, t)

        // Look-ahead: where the eye will be a moment later, with the anchors moved on too.
        val ahead = sample[AHEAD]
        if (ahead > 0f) {
            val dt = LOOK_AHEAD_SECONDS
            sampleAt(shot, (t + dt).coerceAtMost(shot.seconds + dt), sceneTime + dt, aspect, later)
            val dx = later[0] - sample[0]
            val dy = later[1] - sample[1]
            val dz = later[2] - sample[2]
            val travel = sqrt(dx * dx + dy * dy + dz * dz)
            if (travel > MIN_TRAVEL) {
                val reach = distance(sample, 0, sample, 3)
                for (i in 0..2) {
                    val along = sample[i] + (later[i] - sample[i]) / travel * reach
                    sample[3 + i] += (along - sample[3 + i]) * ahead
                }
            }
        }
        sample.copyInto(pose, 0, 0, CosmosSystem.POSE_FLOATS)
        orthonormalizeUp()
        focal = sample[FOCAL]
        fade = 1f
        streaks = 0f
        applyShake(shot.shake, time)
        if (arrivedByWarp && time < WARP_IN_SECONDS) warpIn(time / WARP_IN_SECONDS)
        when (shot.exit) {
            VoyageExit.Warp -> {
                val start = shot.seconds - WARP_OUT_SECONDS
                if (time > start) warpOut(((time - start) / WARP_OUT_SECONDS).coerceIn(0f, 1f))
            }
            VoyageExit.Fade -> fade = 1f - smoothstep(shot.seconds - FADE_OUT_SECONDS, shot.seconds, time)
            VoyageExit.Cut -> Unit
        }
    }

    /**
     * A jump away from any pose (the free camera, when the voyage resumes): [from] pushed along
     * its view axis at [progress] in [0, 1], as the end of a [VoyageExit.Warp] shot.
     */
    fun warpAway(from: FloatArray, fromFocal: Float, progress: Float) {
        from.copyInto(pose, 0, 0, CosmosSystem.POSE_FLOATS)
        focal = fromFocal
        fade = 1f
        streaks = 0f
        warpOut(progress.coerceIn(0f, 1f))
    }

    // ─── Transitions ─────────────────────────────────────────────────────────────────────────

    private fun warpOut(u: Float) {
        val surge = u * u * u
        push(surge * WARP_PUSH)
        focal *= 1f - (1f - WARP_FOCAL_SHARE) * u * u
        fade = 1f - smoothstep(WARP_BLACK_FROM, 1f, u)
        streaks = smoothstep(0.1f, 0.85f, u)
    }

    private fun warpIn(u: Float) {
        val left = 1f - u
        // Arriving from behind, decelerating: the mirror of the surge.
        push(-left * left * left * WARP_PUSH)
        focal *= WARP_FOCAL_SHARE + (1f - WARP_FOCAL_SHARE) * (1f - left * left)
        streaks = 1f - smoothstep(0f, 0.8f, u)
    }

    /** Moves eye and target along the view axis by [share] of the eye-to-target distance. */
    private fun push(share: Float) {
        val reach = distance(pose, 0, pose, 3)
        if (reach < MIN_TRAVEL) return
        for (i in 0..2) {
            val step = (pose[3 + i] - pose[i]) * share
            pose[i] += step
            pose[3 + i] += step
        }
    }

    /** Handheld sway: the aim drifts by up to [degrees], the roll by half of it. */
    private fun applyShake(degrees: Float, time: Float) {
        if (degrees <= 0f) return
        cameraBasis(pose, basis)
        val reach = distance(pose, 0, pose, 3)
        val swing = tan(degrees * CosmosSystem.DEG) * reach
        val nx = wobble(time, 0.37f, 0.83f, 1.91f, 0.3f)
        val ny = wobble(time, 0.29f, 0.71f, 1.53f, 1.7f)
        val nr = wobble(time, 0.23f, 0.61f, 1.37f, 2.9f)
        for (i in 0..2) pose[3 + i] += (basis[i] * nx + basis[3 + i] * ny) * swing
        rollUp(nr * degrees * 0.5f)
    }

    // ─── Sampling ────────────────────────────────────────────────────────────────────────────

    private fun sampleAt(shot: VoyageShot, t: Float, sceneTime: Float, aspect: Float, out: FloatArray) {
        val keys = shot.keys
        val n = keys.size
        val at = t.coerceIn(keys[0].at, keys[n - 1].at)
        var i = 0
        while (i < n - 2 && at >= keys[i + 1].at) i++
        val indices = slotKeys
        indices[0] = (i - 1).coerceAtLeast(0)
        indices[1] = i
        indices[2] = i + 1
        indices[3] = (i + 2).coerceAtMost(n - 1)
        for (s in 0 until 4) {
            val key = keys[indices[s]]
            times[s] = key.at
            resolve(shot.scene, key, sceneTime, aspect, slots, s * SLOT)
        }
        val t1 = times[1]
        val t2 = times[2]
        val span = t2 - t1
        val u = ((at - t1) / span).coerceIn(0f, 1f)
        val h00 = 2f * u * u * u - 3f * u * u + 1f
        val h10 = u * u * u - 2f * u * u + u
        val h01 = -2f * u * u * u + 3f * u * u
        val h11 = u * u * u - u * u
        val firstKey = indices[1] == 0
        val lastKey = indices[2] == n - 1
        for (c in 0 until SLOT) {
            val p0 = slots[c]
            val p1 = slots[SLOT + c]
            val p2 = slots[2 * SLOT + c]
            val p3 = slots[3 * SLOT + c]
            // Non-uniform Catmull-Rom tangents; one-sided at the shot's first and last keys.
            val m1 = if (firstKey) (p2 - p1) / span else (p2 - p0) / (t2 - times[0])
            val m2 = if (lastKey) (p2 - p1) / span else (p3 - p1) / (times[3] - t1)
            out[c] = h00 * p1 + h10 * span * m1 + h01 * p2 + h11 * span * m2
        }
        // `ahead` blends linearly: an overshoot would aim past the path.
        out[AHEAD] = slots[SLOT + AHEAD] + (slots[2 * SLOT + AHEAD] - slots[SLOT + AHEAD]) * smoothstep(0f, 1f, u)
    }

    private fun captionAt(shot: VoyageShot, t: Float): String {
        var text = shot.keys[0].caption.orEmpty()
        for (key in shot.keys) {
            if (key.at > t) break
            key.caption?.let { text = it }
        }
        return text
    }

    /** Writes [key] in world space at [sceneTime] into [out] at [o]: eye, target, up, focal, ahead. */
    private fun resolve(scene: CosmosScene, key: VoyageKey, sceneTime: Float, aspect: Float, out: FloatArray, o: Int) {
        val e = key.eye
        val g = key.target
        when (key.anchor) {
            VoyageAnchor.World -> {
                e.copyInto(out, o)
                g.copyInto(out, o + 3)
                out[o + 6] = 0f
                out[o + 7] = 1f
                out[o + 8] = 0f
            }
            VoyageAnchor.Orbit -> {
                val q = rig.orbitFrame(aspect)
                rotateInto(q, e[0], e[1], e[2], va)
                va.copyInto(out, o)
                rotateInto(q, g[0], g[1], g[2], va)
                va.copyInto(out, o + 3)
                rotateInto(q, 0f, 1f, 0f, va)
                va.copyInto(out, o + 6)
            }
            VoyageAnchor.Planet -> {
                val p = rig.planetPosition(sceneTime, aspect)
                vd[0] = p[0]
                vd[1] = p[1]
                vd[2] = p[2]
                // y: the spin axis (the spin itself turns about it, so it drops out).
                rotateInto(rig.planetRotation(sceneTime, aspect), 0f, 1f, 0f, vb)
                // x: away from the star, in the ring plane.
                val lift = vd[0] * vb[0] + vd[1] * vb[1] + vd[2] * vb[2]
                normalizeInto(va, vd[0] - vb[0] * lift, vd[1] - vb[1] * lift, vd[2] - vb[2] * lift)
                // z = x × y.
                crossNormalized(va, vb, vc)
                for (i in 0..2) {
                    out[o + i] = vd[i] + va[i] * e[0] + vb[i] * e[1] + vc[i] * e[2]
                    out[o + 3 + i] = vd[i] + va[i] * g[0] + vb[i] * g[1] + vc[i] * g[2]
                    out[o + 6 + i] = vb[i]
                }
            }
            VoyageAnchor.Framing -> {
                val framing = if (scene == CosmosScene.Star) {
                    rig.systemPose(sceneTime, aspect)
                } else {
                    CosmosFraming.pose(scene, sceneTime, aspect)
                }
                cameraBasis(framing, basis)
                for (i in 0..2) {
                    // Camera axes: right, up, and backward (the opposite of forward).
                    val r = basis[i]
                    val u = basis[3 + i]
                    val b = -basis[6 + i]
                    out[o + i] = framing[i] + r * e[0] + u * e[1] + b * e[2]
                    out[o + 3 + i] = framing[3 + i] + r * g[0] + u * g[1] + b * g[2]
                    out[o + 6 + i] = u
                }
            }
        }
        if (key.roll != 0f) rollInto(out, o, key.roll)
        val fit = if (key.anchor == VoyageAnchor.Framing) 1f else min(1f, aspect / REFERENCE_ASPECT)
        out[o + FOCAL] = key.focal * fit
        out[o + AHEAD] = key.ahead
    }

    // ─── Vector helpers ──────────────────────────────────────────────────────────────────────

    /** Makes the pose's up vector unit length and square to its view axis. */
    private fun orthonormalizeUp() {
        cameraBasis(pose, basis)
        pose[6] = basis[3]
        pose[7] = basis[4]
        pose[8] = basis[5]
    }

    /** Turns the pose's up vector about its view axis by [degrees]. */
    private fun rollUp(degrees: Float) {
        rollInto(pose, 0, degrees)
    }

    /** Turns the up vector of the pose at [o] in [p] about its own view axis by [degrees]. */
    private fun rollInto(p: FloatArray, o: Int, degrees: Float) {
        normalizeInto(vd, p[o + 3] - p[o], p[o + 4] - p[o + 1], p[o + 5] - p[o + 2])
        val a = degrees * CosmosSystem.DEG
        val c = cos(a)
        val s = sin(a)
        val ux = p[o + 6]
        val uy = p[o + 7]
        val uz = p[o + 8]
        // Rodrigues, about the forward axis: clockwise for the viewer is a positive turn about it.
        val kx = vd[1] * uz - vd[2] * uy
        val ky = vd[2] * ux - vd[0] * uz
        val kz = vd[0] * uy - vd[1] * ux
        val kd = vd[0] * ux + vd[1] * uy + vd[2] * uz
        p[o + 6] = ux * c + kx * s + vd[0] * kd * (1f - c)
        p[o + 7] = uy * c + ky * s + vd[1] * kd * (1f - c)
        p[o + 8] = uz * c + kz * s + vd[2] * kd * (1f - c)
    }

    companion object {
        /** The scene's default lens: 28 mm on a 24 mm-high sensor. */
        const val DEFAULT_FOCAL = 28f

        /** Width / height the keys are framed for: a 1080 × 2400 portrait phone. */
        const val REFERENCE_ASPECT = 0.45f

        const val WARP_OUT_SECONDS = 1.4f
        const val WARP_IN_SECONDS = 1.2f
        const val FADE_OUT_SECONDS = 1.1f

        /** How far a warp throws the camera, in eye-to-target distances. */
        private const val WARP_PUSH = 2.5f

        /** The focal length at the height of a jump, as a share of the shot's: a wider lens. */
        private const val WARP_FOCAL_SHARE = 0.5f

        /** The share of the warp-out after which the scene goes to black under the streaks. */
        private const val WARP_BLACK_FROM = 0.55f

        const val LOOK_AHEAD_SECONDS = 0.6f
        private const val MIN_TRAVEL = 1e-4f

        private const val SLOT = 11
        private const val FOCAL = 9
        private const val AHEAD = 10
    }
}

/**
 * The camera's right, up and forward unit axes for [pose] into [out] (nine floats, in that
 * order). Up is made square to forward.
 */
internal fun cameraBasis(pose: FloatArray, out: FloatArray) {
    var fx = pose[3] - pose[0]
    var fy = pose[4] - pose[1]
    var fz = pose[5] - pose[2]
    val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-9f)
    fx /= fl
    fy /= fl
    fz /= fl
    var rx = fy * pose[8] - fz * pose[7]
    var ry = fz * pose[6] - fx * pose[8]
    var rz = fx * pose[7] - fy * pose[6]
    val rl = sqrt(rx * rx + ry * ry + rz * rz)
    if (rl < 1e-6f) {
        // Up along the view axis: any right will do.
        rx = if (abs(fx) < 0.9f) 0f else 1f
        ry = if (abs(fx) < 0.9f) -fz else 0f
        rz = if (abs(fx) < 0.9f) fy else 0f
        val l = sqrt(rx * rx + ry * ry + rz * rz)
        rx /= l
        ry /= l
        rz /= l
    } else {
        rx /= rl
        ry /= rl
        rz /= rl
    }
    out[0] = rx
    out[1] = ry
    out[2] = rz
    out[3] = ry * fz - rz * fy
    out[4] = rz * fx - rx * fz
    out[5] = rx * fy - ry * fx
    out[6] = fx
    out[7] = fy
    out[8] = fz
}

/**
 * The free camera turned by the user's drag: the eye swung [yawDegrees] about the pose's up axis
 * and [pitchDegrees] about its right axis, round the target, in place. [scratch] holds at least
 * 15 floats.
 */
internal fun orbitPose(pose: FloatArray, yawDegrees: Float, pitchDegrees: Float, scratch: FloatArray) {
    if (yawDegrees == 0f && pitchDegrees == 0f) return
    cameraBasis(pose, scratch)
    val ya = yawDegrees * CosmosSystem.DEG
    val pa = pitchDegrees * CosmosSystem.DEG
    // Offset of the eye from the target, yawed about up, into scratch[9..11].
    rotateAbout(pose[0] - pose[3], pose[1] - pose[4], pose[2] - pose[5], scratch, 3, ya, scratch, 9)
    // The right axis turns with the yaw, into scratch[12..14]; the pitch is about it.
    rotateAbout(scratch[0], scratch[1], scratch[2], scratch, 3, ya, scratch, 12)
    rotateAbout(scratch[9], scratch[10], scratch[11], scratch, 12, pa, pose, 0)
    rotateAbout(scratch[3], scratch[4], scratch[5], scratch, 12, pa, pose, 6)
    for (i in 0..2) pose[i] += pose[3 + i]
}

/**
 * ([x], [y], [z]) turned by [angle] radians about the unit axis at [axis][[ao]], into
 * [out] at [oo].
 */
@Suppress("LongParameterList")
private fun rotateAbout(
    x: Float,
    y: Float,
    z: Float,
    axis: FloatArray,
    ao: Int,
    angle: Float,
    out: FloatArray,
    oo: Int,
) {
    val ax = axis[ao]
    val ay = axis[ao + 1]
    val az = axis[ao + 2]
    val c = cos(angle)
    val s = sin(angle)
    val d = ax * x + ay * y + az * z
    out[oo] = x * c + (ay * z - az * y) * s + ax * d * (1f - c)
    out[oo + 1] = y * c + (az * x - ax * z) * s + ay * d * (1f - c)
    out[oo + 2] = z * c + (ax * y - ay * x) * s + az * d * (1f - c)
}

private fun distance(a: FloatArray, ao: Int, b: FloatArray, bo: Int): Float {
    val dx = b[bo] - a[ao]
    val dy = b[bo + 1] - a[ao + 1]
    val dz = b[bo + 2] - a[ao + 2]
    return sqrt(dx * dx + dy * dy + dz * dz)
}

private fun normalizeInto(out: FloatArray, x: Float, y: Float, z: Float) {
    val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-9f)
    out[0] = x / l
    out[1] = y / l
    out[2] = z / l
}

private fun crossNormalized(a: FloatArray, b: FloatArray, out: FloatArray) {
    normalizeInto(out, a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
}

/** A smooth pseudo-random sway in about [-1, 1]: three sines at unrelated rates. */
private fun wobble(t: Float, f1: Float, f2: Float, f3: Float, phase: Float): Float {
    val tau = 2f * Math.PI.toFloat()
    return 0.6f * sin(tau * f1 * t + phase) +
        0.3f * sin(tau * f2 * t + phase * 2.3f) +
        0.1f * sin(tau * f3 * t + phase * 3.7f)
}

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
