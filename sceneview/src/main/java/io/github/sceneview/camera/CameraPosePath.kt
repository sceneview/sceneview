package io.github.sceneview.camera

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import dev.romainguy.kotlin.math.mix
import dev.romainguy.kotlin.math.normalize
import io.github.sceneview.Aabb
import io.github.sceneview.DEFAULT_FRAMING_PADDING
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.animation.slerp
import io.github.sceneview.fitDistanceForBounds
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * One stop of a [CameraPosePath]: the camera is exactly at [pose] [seconds] into the path.
 *
 * @property pose Where the camera is, and which way it faces.
 * @property seconds When the camera is there. Counted on the same clock as every other keyframe
 * of the path; the path itself starts at its earliest keyframe.
 * @property easing Easing of the segment that **leaves** this keyframe, from `[0, 1]` to `[0, 1]`.
 * `null` uses the path's [CameraPosePath.easing]. Ignored on the last keyframe.
 */
@ExperimentalSceneViewApi
data class CameraPoseKeyframe(
    val pose: CameraPose,
    val seconds: Float,
    val easing: ((Float) -> Float)? = null,
)

/** How a [CameraPosePath] moves the camera position between two keyframes. */
@ExperimentalSceneViewApi
enum class CameraPathInterpolation {
    /** Straight lines between keyframes; the speed jumps at each keyframe. */
    Linear,

    /**
     * A Catmull-Rom spline through the keyframes, with tangents scaled by the keyframe times: the
     * position and its velocity are continuous across every keyframe when segments are not eased.
     *
     * Tangents are capped so the camera does not overshoot a pause: next to a keyframe that repeats
     * its neighbour's position the camera comes to rest instead of running past and coming back.
     * A sharp turn at a keyframe still rounds off slightly past it, as with any Catmull-Rom spline;
     * use [Linear] for exact corners. Two keyframes at the same time are a cut: the spline restarts
     * on the far side.
     */
    CatmullRom,
}

/**
 * A camera move defined by keyframed [CameraPose]s, sampled with [poseAt].
 *
 * Positions follow [interpolation]; rotations are interpolated by spherical linear
 * interpolation (slerp) along the shortest arc. Each keyframe quaternion is flipped into the
 * hemisphere of the one before it, so a path never spins the long way round between two
 * keyframes whose quaternions have opposite signs. When [lookAt] is set, the rotation is derived
 * from the interpolated position instead, so the camera keeps that point at the centre of the
 * frame at every instant, not only on the keyframes.
 *
 * **Deterministic.** [poseAt] is a pure function of its argument: no state, no clock, no frame
 * rate. The same time gives the same pose whatever was sampled before, so a player, a scrubber
 * and an offline renderer stepping at a fixed rate all see the same move.
 *
 * **Time.** The path starts at its earliest keyframe and lasts until its latest one
 * ([durationSeconds]); [poseAt] counts from that start, like [CameraPath.shotAt]. Before the start
 * it holds the first pose, after the end the last pose, unless [loop] wraps time around.
 *
 * **Easing.** Each segment is eased by its starting keyframe's [CameraPoseKeyframe.easing], or by
 * [easing] when that is `null`. An eased segment stops at both of its keyframes. [pathEasing],
 * when set, also remaps the whole path's time before the segment is found, so a multi-keyframe
 * move can ease in at the start and out at the end as one gesture. With [pathEasing] the
 * keyframes are reached at the times the easing maps their own to.
 *
 * **Limits.**
 * - Rotation is slerped segment by segment (no `squad`): its direction of turn is continuous but
 *   its angular speed can jump at a keyframe. Closer keyframes make the jump smaller.
 * - With [lookAt], the rotation is rebuilt from [up] at every instant. A camera that passes
 *   straight over or under [lookAt] (looking along [up]) flips its roll by 180° at that point;
 *   keep the path away from the pole, as [orbit] does by clamping its elevation.
 *
 * **Experimental.** The keyframe types may change with offline rendering; [CameraTimeline] and
 * [CameraPath] are not affected.
 *
 * Ready-made paths: [orbit] frames a bounding box while turning around it, [travelling] slides
 * the camera sideways, and [dolly] moves it toward or away from a target. Play any of them with
 * [CameraPathPlayer], or place a camera with
 * [io.github.sceneview.node.CameraNode.applyCameraTimeline].
 *
 * @param keyframes The stops. Sorted by time here; keyframes with a non-finite time are dropped.
 * @property interpolation How positions move between keyframes.
 * @property easing Default per-segment easing. Defaults to [CameraEasing.Linear].
 * @property pathEasing Optional easing of the whole path's normalized time; `null` for none.
 * @property lookAt When set, the camera faces this point at every instant and keyframe rotations
 * are ignored.
 * @property up World up used with [lookAt]. Defaults to `+Y`.
 */
@ExperimentalSceneViewApi
class CameraPosePath(
    keyframes: List<CameraPoseKeyframe>,
    val interpolation: CameraPathInterpolation = CameraPathInterpolation.CatmullRom,
    val easing: (Float) -> Float = CameraEasing.Linear,
    val pathEasing: ((Float) -> Float)? = null,
    val lookAt: Position? = null,
    val up: Direction = CameraPose.WORLD_UP,
    override val loop: Boolean = false,
) : CameraTimeline {

    /** The keyframes in time order. */
    val keyframes: List<CameraPoseKeyframe> =
        keyframes.filter { it.seconds.isFinite() }.sortedBy { it.seconds }

    /** From the first keyframe's time to the last one's; `0` with fewer than two keyframes. */
    override val durationSeconds: Float =
        if (this.keyframes.size < 2) 0f else this.keyframes.last().seconds - this.keyframes.first().seconds

    /** The first pose, where the path starts; `null` for an empty path. */
    val start: CameraPose? get() = keyframes.firstOrNull()?.let { poseOf(it.pose) }

    /** The last pose, where the path ends; `null` for an empty path. */
    val end: CameraPose? get() = keyframes.lastOrNull()?.let { poseOf(it.pose) }

    /** Keyframe rotations, normalized and each flipped into the hemisphere of the previous one. */
    private val rotations: List<Quaternion> = run {
        val result = ArrayList<Quaternion>(this.keyframes.size)
        for (keyframe in this.keyframes) {
            var q = normalizeOrIdentity(keyframe.pose.quaternion)
            val previous = result.lastOrNull()
            if (previous != null && dot(previous, q) < 0f) q = -q
            result += q
        }
        result
    }

    /**
     * `true` when the path loops and ends where it starts, so the spline's tangents at both ends
     * are taken across the seam and the loop has no kink.
     */
    private val closed: Boolean = run {
        val frames = this.keyframes
        loop && frames.size >= 3 &&
            length(frames.last().pose.position - frames.first().pose.position) <= CLOSE_TOLERANCE
    }

    /** Spline tangents (world units per second), one per keyframe. */
    private val tangents: List<Float3> = List(this.keyframes.size) { tangentAt(it) }

    /**
     * The camera pose [seconds] after the path's start. A pure function of [seconds].
     *
     * An empty path gives the identity pose; a single keyframe gives that keyframe at any time.
     * A non-finite [seconds] is treated as `0`.
     */
    override fun poseAt(seconds: Float): CameraPose {
        val frames = keyframes
        if (frames.isEmpty()) return CameraPose()
        if (frames.size == 1 || durationSeconds <= 0f) return poseOf(frames.first().pose)
        val local = localSeconds(if (seconds.isFinite()) seconds else 0f)
        val eased = pathEasing?.let { durationSeconds * applyEasing(it, local / durationSeconds) } ?: local
        return sample(frames.first().seconds + eased)
    }

    /** `true` once [seconds] is past the end of a path that does not loop. */
    fun isFinishedAt(seconds: Float): Boolean = !loop && seconds >= durationSeconds

    private fun localSeconds(seconds: Float): Float {
        if (!loop) return seconds.coerceIn(0f, durationSeconds)
        if (seconds in 0f..durationSeconds) return seconds
        val laps = floor(seconds / durationSeconds)
        val wrapped = seconds - laps * durationSeconds
        return wrapped.coerceIn(0f, durationSeconds)
    }

    /** Sample at absolute keyframe time [t], inside `[first.seconds, last.seconds]`. */
    private fun sample(t: Float): CameraPose {
        val frames = keyframes
        val last = frames.size - 1
        if (t <= frames.first().seconds) return poseOf(frames.first().pose)
        if (t >= frames[last].seconds) return poseOf(frames[last].pose)
        // The segment [i, i + 1] that holds t, the later one on a keyframe time (u = 0 is exact).
        var low = 0
        var high = last
        while (high - low > 1) {
            val mid = (low + high) ushr 1
            if (frames[mid].seconds <= t) low = mid else high = mid
        }
        val i = low
        val k0 = frames[i]
        val k1 = frames[i + 1]
        val span = k1.seconds - k0.seconds
        if (span <= 0f) return poseOf(k1.pose)
        val u = applyEasing(k0.easing ?: easing, (t - k0.seconds) / span)
        if (u <= 0f) return poseOf(k0.pose, rotations[i])
        if (u >= 1f) return poseOf(k1.pose, rotations[i + 1])
        val position = when (interpolation) {
            CameraPathInterpolation.Linear -> mix(k0.pose.position, k1.pose.position, u)
            CameraPathInterpolation.CatmullRom -> hermite(
                k0.pose.position,
                tangents[i] * span,
                k1.pose.position,
                tangents[i + 1] * span,
                u,
            )
        }
        val rotation = lookAt?.let { CameraPose.lookRotation(position, it, up) }
            ?: slerp(rotations[i], rotations[i + 1], u)
        return CameraPose(position, rotation)
    }

    private fun poseOf(pose: CameraPose, rotation: Quaternion = pose.quaternion): CameraPose {
        val locked = lookAt?.let { CameraPose.lookRotation(pose.position, it, up) }
        return when {
            locked != null -> CameraPose(pose.position, locked)
            rotation === pose.quaternion -> pose.copy(quaternion = normalizeOrIdentity(pose.quaternion))
            else -> CameraPose(pose.position, rotation)
        }
    }

    /**
     * Spline tangent at keyframe [index], in world units per second.
     *
     * Catmull-Rom's time-weighted central difference of the two neighbouring segments, then
     * capped in length at [MAX_TANGENT_RATIO] times the slower neighbour's speed. The cap is
     * Fritsch-Carlson's monotonicity bound (α, β ≤ 3) taken on the vector's length, so it does not
     * depend on the axes: a keyframe next to a pause (a neighbour at the same position) gets a zero
     * tangent and the camera neither overshoots nor backs up around the pause, and along a straight
     * line the camera never leaves the segment between two keyframes. Evenly spaced keyframes on a
     * smooth curve, such as an [orbit], are below the cap and keep plain Catmull-Rom tangents.
     *
     * A cut (two keyframes at the same time) splits the spline: each side takes the one-sided
     * tangent of its own segment, as at the end of a path.
     */
    private fun tangentAt(index: Int): Float3 {
        val frames = keyframes
        val last = frames.size - 1
        if (last < 1) return Float3()
        // Segments are (from, to) keyframe pairs; across the seam of a closed loop the keyframe
        // before the end leads into the start and the one after the start follows the end.
        val previous: Pair<Int, Int>? = when {
            index > 0 -> index - 1 to index
            closed -> last - 1 to last
            else -> null
        }?.takeIf { (from, to) -> spanOf(from, to) > MIN_SPAN }
        val next: Pair<Int, Int>? = when {
            index < last -> index to index + 1
            closed -> 0 to 1
            else -> null
        }?.takeIf { (from, to) -> spanOf(from, to) > MIN_SPAN }
        if (previous == null && next == null) return Float3()
        if (previous == null || next == null) return velocityOf(previous ?: next!!)
        val inSpan = spanOf(previous.first, previous.second)
        val outSpan = spanOf(next.first, next.second)
        val inVelocity = velocityOf(previous)
        val outVelocity = velocityOf(next)
        val central = (inVelocity * inSpan + outVelocity * outSpan) / (inSpan + outSpan)
        val limit = MAX_TANGENT_RATIO * minOf(length(inVelocity), length(outVelocity))
        val size = length(central)
        return if (size > limit) central * (limit / size) else central
    }

    private fun spanOf(from: Int, to: Int): Float = keyframes[to].seconds - keyframes[from].seconds

    /** Average velocity over the segment, in world units per second. */
    private fun velocityOf(segment: Pair<Int, Int>): Float3 {
        val (from, to) = segment
        return (keyframes[to].pose.position - keyframes[from].pose.position) / spanOf(from, to)
    }

    companion object {

        /** Keyframes per full turn [orbit] places; the spline then stays within 0.01 % of the circle. */
        const val ORBIT_KEYFRAMES_PER_TURN: Int = 32

        /** Default [orbit] length, in seconds per turn. */
        const val DEFAULT_ORBIT_SECONDS: Float = 12f

        /** Default [orbit] elevation, in degrees above the subject's centre. */
        const val DEFAULT_ORBIT_ELEVATION_DEGREES: Float = 20f

        /** Two positions closer than this (world units) are the same point when closing a loop. */
        private const val CLOSE_TOLERANCE = 1e-4f

        /** Keyframes closer in time than this are a cut, not a segment. */
        private const val MIN_SPAN = 1e-6f

        /** Fritsch-Carlson bound: a tangent at most 3× a neighbour's speed keeps a segment monotone. */
        private const val MAX_TANGENT_RATIO = 3f

        /**
         * A turn around [bounds] that keeps the whole box in frame and the box's centre at the
         * centre of the frame.
         *
         * The distance is computed from the camera's field of view and aspect ratio with
         * [fitDistanceForBounds], for the box swept about the vertical axis, so the box fits at
         * every angle of the turn. The camera stays at that distance from the box centre, at
         * [elevationDegrees] above it, and looks at the centre at every instant ([lookAt] is set).
         *
         * Azimuths follow [CameraShot]: `0°` looks from `+Z`, positive turns go toward `+X`.
         * Positions are a Catmull-Rom spline through [keyframesPerTurn] keyframes per turn.
         *
         * @param bounds The box to frame, in world space. An empty box is framed at
         * [CameraPath.MIN_DISTANCE] around its centre, which must be finite: the empty box Filament
         * reports, with infinite corners, has none, so check [Aabb.isEmpty] first.
         * @param verticalFovDegrees Vertical field of view of the camera that will play the path.
         * @param aspect Viewport width divided by height.
         * @param durationSeconds Length of the whole path. Must be positive.
         * @param turns Number of turns; negative turns the other way. A whole number of turns
         * ends where it starts, so a [loop] is seamless.
         * @param elevationDegrees Camera elevation above the box centre; positive looks down.
         * @param startAzimuthDegrees Azimuth of the first keyframe.
         * @param padding Extra framing margin, as a fraction of the fitted distance.
         * @param loop Whether the path repeats.
         * @param keyframesPerTurn Keyframes per full turn. At least 4.
         * @throws IllegalArgumentException when [durationSeconds] is not positive, [turns] is
         * zero, [verticalFovDegrees] or [aspect] is not positive, or any input (including the
         * bounds) is not finite.
         */
        @Suppress("LongParameterList")
        fun orbit(
            bounds: Aabb,
            verticalFovDegrees: Double,
            aspect: Double,
            durationSeconds: Float = DEFAULT_ORBIT_SECONDS,
            turns: Float = 1f,
            elevationDegrees: Float = DEFAULT_ORBIT_ELEVATION_DEGREES,
            startAzimuthDegrees: Float = 0f,
            padding: Float = DEFAULT_FRAMING_PADDING,
            loop: Boolean = true,
            keyframesPerTurn: Int = ORBIT_KEYFRAMES_PER_TURN,
        ): CameraPosePath {
            require(durationSeconds.isFinite() && durationSeconds > 0f) { "durationSeconds must be > 0" }
            require(turns.isFinite() && turns != 0f) { "turns must be finite and non-zero" }
            require(verticalFovDegrees.isFinite() && verticalFovDegrees > 0.0) {
                "verticalFovDegrees must be finite and > 0"
            }
            require(aspect.isFinite() && aspect > 0.0) { "aspect must be finite and > 0" }
            require(elevationDegrees.isFinite()) { "elevationDegrees must be finite" }
            require(startAzimuthDegrees.isFinite()) { "startAzimuthDegrees must be finite" }
            require(padding.isFinite()) { "padding must be finite" }
            val center = bounds.center
            require(center.x.isFinite() && center.y.isFinite() && center.z.isFinite()) {
                "bounds must be finite"
            }
            val elevation = elevationDegrees.coerceIn(-CameraShot.MAX_ELEVATION, CameraShot.MAX_ELEVATION)
            val radius = fitDistanceForBounds(
                bounds = bounds,
                verticalFovDegrees = verticalFovDegrees,
                aspect = aspect,
                padding = padding,
                elevationDegrees = elevation.toDouble(),
            ).takeIf { it.isFinite() && it > CameraPath.MIN_DISTANCE } ?: CameraPath.MIN_DISTANCE
            val segments = maxOf(2, ceil(abs(turns) * keyframesPerTurn.coerceAtLeast(MIN_KEYFRAMES_PER_TURN)).toInt())
            val wholeTurns = turns == floor(turns)
            val keyframes = ArrayList<CameraPoseKeyframe>(segments + 1)
            for (k in 0..segments) {
                val fraction = k.toFloat() / segments
                val azimuth = if (k == segments && wholeTurns) {
                    startAzimuthDegrees
                } else {
                    startAzimuthDegrees + FULL_TURN_DEGREES * turns * fraction
                }
                val eye = CameraShot(center, azimuth, elevation, radius).eye
                keyframes += CameraPoseKeyframe(CameraPose.lookingAt(eye, center), durationSeconds * fraction)
            }
            return CameraPosePath(
                keyframes = keyframes,
                interpolation = CameraPathInterpolation.CatmullRom,
                easing = CameraEasing.Linear,
                lookAt = center,
                loop = loop,
            )
        }

        /**
         * A travelling: the camera slides sideways along its own screen-right axis by
         * [distance], keeping its orientation.
         *
         * @param from Where the move starts.
         * @param distance How far to slide, in world units; negative slides left.
         * @param durationSeconds Length of the move. Must be positive.
         * @param easing Easing of the move. Defaults to [CameraEasing.InOut]: starts and ends at rest.
         * @throws IllegalArgumentException when [durationSeconds] is not positive, or [distance]
         * or [from]'s position is not finite.
         */
        fun travelling(
            from: CameraPose,
            distance: Float,
            durationSeconds: Float,
            easing: (Float) -> Float = CameraEasing.InOut,
        ): CameraPosePath {
            require(durationSeconds.isFinite() && durationSeconds > 0f) { "durationSeconds must be > 0" }
            require(distance.isFinite()) { "distance must be finite" }
            require(isFinite(from.position)) { "from must be finite" }
            val start = from.copy(quaternion = normalizeOrIdentity(from.quaternion))
            val end = start.copy(position = start.position + start.right * distance)
            return CameraPosePath(
                keyframes = listOf(CameraPoseKeyframe(start, 0f), CameraPoseKeyframe(end, durationSeconds)),
                interpolation = CameraPathInterpolation.Linear,
                easing = easing,
            )
        }

        /**
         * A dolly: the camera moves along the line from [target] through [from] until it is
         * [toDistance] from [target], facing [target] all the way.
         *
         * @param target The point the camera faces.
         * @param from Where the camera starts. Must differ from [target].
         * @param toDistance Final distance from [target], in world units; clamped to at least
         * [CameraPath.MIN_DISTANCE]. Smaller than the start distance moves in, larger moves out.
         * @param durationSeconds Length of the move. Must be positive.
         * @param easing Easing of the move. Defaults to [CameraEasing.InOut].
         * @throws IllegalArgumentException when [durationSeconds] is not positive, [from]
         * equals [target], or [toDistance], [target] or [from] is not finite.
         */
        fun dolly(
            target: Position,
            from: Position,
            toDistance: Float,
            durationSeconds: Float,
            easing: (Float) -> Float = CameraEasing.InOut,
        ): CameraPosePath {
            require(durationSeconds.isFinite() && durationSeconds > 0f) { "durationSeconds must be > 0" }
            require(toDistance.isFinite()) { "toDistance must be finite" }
            require(isFinite(target) && isFinite(from)) { "target and from must be finite" }
            val offset = from - target
            val startDistance = length(offset)
            require(startDistance > 0f) { "from must differ from target" }
            val end = target + offset / startDistance * toDistance.coerceAtLeast(CameraPath.MIN_DISTANCE)
            return CameraPosePath(
                keyframes = listOf(
                    CameraPoseKeyframe(CameraPose.lookingAt(from, target), 0f),
                    CameraPoseKeyframe(CameraPose.lookingAt(end, target), durationSeconds),
                ),
                interpolation = CameraPathInterpolation.Linear,
                easing = easing,
                lookAt = target,
            )
        }

        private const val FULL_TURN_DEGREES = 360f
        private const val MIN_KEYFRAMES_PER_TURN = 4

        private fun isFinite(p: Float3): Boolean = p.x.isFinite() && p.y.isFinite() && p.z.isFinite()

        private fun normalizeOrIdentity(q: Quaternion): Quaternion {
            val norm = length(q)
            return if (norm.isFinite() && norm > 0f) normalize(q) else Quaternion()
        }

        /** [easing] at [u] clamped to `[0, 1]`, exact at both ends whatever the curve's rounding. */
        private fun applyEasing(easing: (Float) -> Float, u: Float): Float = when {
            !(u > 0f) -> 0f
            u >= 1f -> 1f
            else -> easing(u).let { if (it.isFinite()) it.coerceIn(0f, 1f) else u }
        }

        private fun hermite(p0: Float3, m0: Float3, p1: Float3, m1: Float3, u: Float): Float3 {
            val u2 = u * u
            val u3 = u2 * u
            val h00 = 2f * u3 - 3f * u2 + 1f
            val h10 = u3 - 2f * u2 + u
            val h01 = -2f * u3 + 3f * u2
            val h11 = u3 - u2
            return p0 * h00 + m0 * h10 + p1 * h01 + m1 * h11
        }
    }
}
