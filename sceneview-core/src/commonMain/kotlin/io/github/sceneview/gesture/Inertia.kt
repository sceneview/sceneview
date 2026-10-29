package io.github.sceneview.gesture

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * How a gesture-driven movement coasts once the finger lifts — the "fling" every touch UI has.
 *
 * SceneView applies it **by default** to every gesture that moves something on screen: camera
 * orbit, pan and pinch-zoom, and the drag / twist / pinch editing of a node (AR objects
 * included). Release a drag mid-flight and the movement carries on at the speed the finger had,
 * then slows down smoothly to a stop; release it after holding still and nothing coasts. A new
 * touch stops a coast on the spot.
 *
 * ```kotlin
 * // Default: coasts.
 * SceneView(cameraManipulator = rememberCameraManipulator(orbitRadius = 3f))
 * // Stops dead on release.
 * SceneView(cameraManipulator = rememberCameraManipulator(orbitRadius = 3f, inertia = Inertia.None))
 * // A node that stops dead when the user lets go of it.
 * ModelNode(modelInstance, isEditable = true, apply = { editingInertia = Inertia.None })
 * ```
 *
 * The model is the one every platform scroller uses: the release velocity is measured over the
 * last [velocityWindowSeconds] of the gesture, then decays exponentially,
 * `v(t) = v₀ · e^(−t / timeConstantSeconds)`. The extra distance a flick travels is therefore
 * exactly `v₀ · timeConstantSeconds`, whatever the frame rate — see [InertiaTracker].
 *
 * @param timeConstantSeconds How long the coast lasts: the time for the speed to fall to ~37 % of
 *                            the release speed. `0` disables inertia ([None]). The default
 *                            `0.35 s` is the feel of a native list fling.
 * @param velocityWindowSeconds How much of the end of the gesture the release velocity is
 *                              measured over. Short enough to follow a last-moment flick, long
 *                              enough to average out touch-sampling jitter.
 */
class Inertia(
    val timeConstantSeconds: Float = DEFAULT_TIME_CONSTANT_SECONDS,
    val velocityWindowSeconds: Float = DEFAULT_VELOCITY_WINDOW_SECONDS,
) {
    /** `false` for [None]: a release stops the movement where the finger left it. */
    val isEnabled: Boolean
        get() = timeConstantSeconds > 0f && timeConstantSeconds.isFinite()

    override fun equals(other: Any?): Boolean =
        other is Inertia && other.timeConstantSeconds == timeConstantSeconds &&
            other.velocityWindowSeconds == velocityWindowSeconds

    override fun hashCode(): Int =
        31 * timeConstantSeconds.hashCode() + velocityWindowSeconds.hashCode()

    override fun toString(): String =
        "Inertia(timeConstantSeconds=$timeConstantSeconds, " +
            "velocityWindowSeconds=$velocityWindowSeconds)"

    companion object {
        /** Time constant of [Default], in seconds. */
        const val DEFAULT_TIME_CONSTANT_SECONDS: Float = 0.35f

        /** Velocity window of [Default], in seconds. */
        const val DEFAULT_VELOCITY_WINDOW_SECONDS: Float = 0.1f

        /** The default coast: a native-fling feel. */
        val Default: Inertia = Inertia()

        /** No coast: the movement stops the instant the finger lifts. */
        val None: Inertia = Inertia(timeConstantSeconds = 0f)
    }
}

/**
 * Measures the velocity of a gesture and coasts it after release — the pure maths behind
 * [Inertia], shared by the camera manipulator and node editing.
 *
 * Feed it the gesture's **cumulative** position on every touch sample ([addSample]), call
 * [release] when the finger lifts, then call [advance] once per frame: it hands back how far the
 * movement travels during that frame, until the coast is over.
 *
 * - **Velocity**: a least-squares line through the samples of the last
 *   [Inertia.velocityWindowSeconds] before release. A finger that rested for more than
 *   [STOPPED_GAP_SECONDS] before lifting releases at zero velocity, as Android's own
 *   `VelocityTracker` assumes.
 * - **Decay**: exact exponential integration, `Δx = v·τ·(1 − e^(−Δt/τ))` and `v ← v·e^(−Δt/τ)`.
 *   Being exact, the trajectory is the same at 30, 60 or 120 Hz.
 * - **Cutoff**: the coast stops once the speed falls under [minSpeed] (in the gesture's own
 *   unit per second), and never starts from a release slower than [minReleaseSpeed].
 * - **Interrupt**: [stop] (a new touch) ends the coast immediately.
 *
 * Several dimensions are tracked together (`x`/`y` of a drag) so the cutoff applies to the speed
 * as a whole and a diagonal flick keeps its direction to the end.
 *
 * @param dimensions How many values a sample carries.
 * @param minSpeed   Speed (units per second) under which there is no coast.
 * @param maxSpeed   Speed the release velocity is clamped to, so a one-sample glitch cannot launch
 *                   the movement.
 * @param inertia    The coast parameters; can be changed between two gestures.
 * @param minReleaseSpeed Slowest release that starts a coast (defaults to [minSpeed]). Set it
 *                   higher than [minSpeed] so a slow, deliberate positioning drag stops where it
 *                   is left, while a flick still coasts all the way down to [minSpeed].
 */
class InertiaTracker(
    val dimensions: Int,
    val minSpeed: Float,
    val maxSpeed: Float = Float.POSITIVE_INFINITY,
    var inertia: Inertia = Inertia.Default,
    val minReleaseSpeed: Float = minSpeed,
) {
    init {
        require(dimensions > 0) { "dimensions must be > 0, was $dimensions" }
    }

    private val times = DoubleArray(CAPACITY)
    private val values = Array(CAPACITY) { FloatArray(dimensions) }
    private var count = 0
    private var head = 0

    private val velocity = FloatArray(dimensions)

    /** `true` while a released gesture is still coasting. */
    var isCoasting: Boolean = false
        private set

    /** The current coast velocity along [axis], in units per second (`0` when not coasting). */
    fun velocity(axis: Int): Float = if (isCoasting) velocity[axis] else 0f

    /** The current coast speed (norm of the velocity), in units per second. */
    val speed: Float
        get() = if (isCoasting) norm(velocity) else 0f

    /** Forgets the samples of the previous gesture and stops any coast. Call it on touch-down. */
    fun reset() {
        count = 0
        head = 0
        stop()
    }

    /** Ends a coast where it is — a new touch, a hit-test that failed, a limit reached. */
    fun stop() {
        isCoasting = false
        velocity.fill(0f)
    }

    /**
     * Records the gesture's cumulative position at [timeSeconds]. A sample also stops a coast
     * still in flight: the finger is back in control.
     */
    fun addSample(timeSeconds: Double, vararg position: Float) {
        require(position.size == dimensions) {
            "expected $dimensions values, got ${position.size}"
        }
        if (isCoasting) stop()
        val slot = values[head]
        position.copyInto(slot)
        times[head] = timeSeconds
        head = (head + 1) % CAPACITY
        if (count < CAPACITY) count++
    }

    /**
     * The finger lifted at [timeSeconds]: measures the release velocity and starts the coast.
     *
     * @return `true` when the movement coasts, `false` when it stops here (inertia disabled, a
     *         release too slow, or a finger that rested before lifting).
     */
    fun release(timeSeconds: Double): Boolean {
        stop()
        if (!inertia.isEnabled || count < 2) return false
        val newest = index(count - 1)
        if (timeSeconds - times[newest] > STOPPED_GAP_SECONDS) return false
        val window = inertia.velocityWindowSeconds.toDouble().coerceAtLeast(MIN_WINDOW_SECONDS)
        // Samples inside the window, newest first; at least the two newest so a sparse stream
        // (a slow device delivering one sample per window) still yields a velocity.
        var used = 0
        while (used < count) {
            val sample = index(count - 1 - used)
            if (used >= 2 && times[newest] - times[sample] > window) break
            used++
        }
        if (!fitVelocity(used)) return false
        val clampScale = norm(velocity).let { s ->
            if (s > maxSpeed && s > 0f) maxSpeed / s else 1f
        }
        for (axis in 0 until dimensions) velocity[axis] *= clampScale
        isCoasting = velocity.all { it.isFinite() } &&
            norm(velocity) >= maxOf(minSpeed, minReleaseSpeed)
        if (!isCoasting) velocity.fill(0f)
        return isCoasting
    }

    /**
     * Advances the coast by [deltaSeconds] and writes the distance travelled during that step
     * into [out] (one value per dimension; zeros when not coasting).
     *
     * @return `true` while the coast goes on after this step, `false` once it is over.
     */
    fun advance(deltaSeconds: Float, out: FloatArray): Boolean {
        out.fill(0f, 0, dimensions)
        if (!isCoasting) return false
        val dt = if (deltaSeconds.isFinite()) deltaSeconds.coerceAtLeast(0f) else 0f
        val tau = inertia.timeConstantSeconds
        if (!inertia.isEnabled) {
            stop()
            return false
        }
        val decay = exp(-dt / tau)
        val travel = tau * (1f - decay)
        for (axis in 0 until dimensions) {
            out[axis] = velocity[axis] * travel
            velocity[axis] *= decay
        }
        if (norm(velocity) < minSpeed) stop()
        return isCoasting
    }

    private fun index(i: Int): Int = (head - count + i + CAPACITY * 2) % CAPACITY

    /** Least-squares slope through the [used] newest samples, written to [velocity]. */
    private fun fitVelocity(used: Int): Boolean {
        if (used < 2) return false
        val first = count - used
        val t0 = times[index(count - 1)]
        var meanT = 0.0
        for (i in first until count) meanT += times[index(i)] - t0
        meanT /= used
        var varT = 0.0
        for (i in first until count) {
            val d = times[index(i)] - t0 - meanT
            varT += d * d
        }
        if (varT <= 0.0) return false
        for (axis in 0 until dimensions) {
            var meanV = 0.0
            for (i in first until count) meanV += values[index(i)][axis]
            meanV /= used
            var cov = 0.0
            for (i in first until count) {
                val j = index(i)
                cov += (times[j] - t0 - meanT) * (values[j][axis] - meanV)
            }
            velocity[axis] = (cov / varT).toFloat()
        }
        return true
    }

    private fun norm(v: FloatArray): Float {
        var sum = 0f
        for (axis in 0 until dimensions) sum += v[axis] * v[axis]
        return sqrt(sum)
    }

    companion object {
        /**
         * A finger that has not moved for this long before lifting releases at zero velocity —
         * the platform `VelocityTracker`'s "assume the pointer stopped" delay.
         */
        const val STOPPED_GAP_SECONDS: Double = 0.04

        private const val MIN_WINDOW_SECONDS = 0.016
        private const val CAPACITY = 20
    }
}
