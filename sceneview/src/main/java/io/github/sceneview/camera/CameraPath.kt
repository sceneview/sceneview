package io.github.sceneview.camera

import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.math.Position

/** A [shot] the camera is on at [seconds] into a [CameraPath]. */
data class CameraKeyframe(val shot: CameraShot, val seconds: Float)

/**
 * A camera move through keyframes: the camera passes each [CameraKeyframe.shot] at its time and
 * flows between them on a cubic spline (Catmull-Rom over the orbit parameters), so its speed is
 * continuous across a keyframe — no kink, no change of pace the eye can catch.
 *
 * Two keyframes make a plain move. With one keyframe, or none, the path holds that shot.
 *
 * - [easing] shapes the time of each segment between two keyframes (`0..1` of that segment), so
 *   every keyframe is reached at exactly its declared [CameraKeyframe.seconds].
 *   [CameraEasing.Expressive] lands a reveal softly and, through several keyframes, settles on
 *   each one before leaving it (its speed is zero at both ends of a segment, so still continuous);
 *   [CameraEasing.Linear] flows through the keyframes without slowing — what a looping tour wants.
 * - [shortestArc] unwraps each keyframe's azimuth the short way round from the one before.
 *   Off, the azimuths are taken as given: a tour spells its turn as `azimuthDegrees + 360f`.
 * - [loop] wraps time past the end back to the start; the spline then closes, so the last
 *   keyframe should equal the first (as [tour] does) for the loop to be seamless.
 *
 * Ready-made paths: [reveal], the entrance shot of a scan or a model, and [tour], a slow lap for
 * a video or a share. Feed either to [CameraAnimator], or sample [shotAt] yourself.
 */
@OptIn(ExperimentalSceneViewApi::class)
class CameraPath(
    keyframes: List<CameraKeyframe>,
    val easing: (Float) -> Float = CameraEasing.Expressive,
    val shortestArc: Boolean = true,
    override val loop: Boolean = false,
) : CameraTimeline {
    /** The keyframes in time order, azimuths unwrapped when [shortestArc] is set. */
    val keyframes: List<CameraKeyframe> = keyframes.sortedBy { it.seconds }.let { sorted ->
        if (!shortestArc || sorted.size < 2) return@let sorted
        val unwrapped = ArrayList<CameraKeyframe>(sorted.size)
        var previous = sorted.first().shot.azimuthDegrees
        for (keyframe in sorted) {
            val azimuth = previous + CameraShot.shortestDelta(previous, keyframe.shot.azimuthDegrees)
            unwrapped += keyframe.copy(shot = keyframe.shot.copy(azimuthDegrees = azimuth))
            previous = azimuth
        }
        unwrapped
    }

    /** From the first keyframe's time to the last one's; `0` with fewer than two. */
    override val durationSeconds: Float =
        if (this.keyframes.size < 2) 0f else this.keyframes.last().seconds - this.keyframes.first().seconds

    /** The first shot, where the path starts; `null` for an empty path. */
    val start: CameraShot? get() = keyframes.firstOrNull()?.shot

    /** The last shot, where the path ends; `null` for an empty path. */
    val end: CameraShot? get() = keyframes.lastOrNull()?.shot

    /**
     * The shot [seconds] into the path — counted from the first keyframe, the same clock as
     * [isFinishedAt] and [CameraAnimator]. Past the end a path holds its last shot; a [loop] wraps,
     * and its azimuth keeps counting (lap two of a tour reads `home + 720°`), so the camera's
     * velocity is the same across the seam as anywhere else — nothing for a touch to catch.
     */
    fun shotAt(seconds: Float): CameraShot {
        val frames = keyframes
        if (frames.isEmpty()) return CameraShot()
        if (frames.size == 1 || durationSeconds <= 0f) return frames.first().shot
        val startSeconds = frames.first().seconds
        var local = seconds
        var laps = 0f
        if (loop) {
            laps = kotlin.math.floor(local / durationSeconds)
            local -= laps * durationSeconds
            if (local < 0f) local += durationSeconds
        } else {
            local = local.coerceIn(0f, durationSeconds)
        }
        val shot = sample(startSeconds + local)
        if (laps == 0f) return shot
        val turnPerLap = frames.last().shot.azimuthDegrees - frames.first().shot.azimuthDegrees
        return shot.copy(azimuthDegrees = shot.azimuthDegrees + laps * turnPerLap)
    }

    /**
     * The camera pose [seconds] into the path: [shotAt] as a [CameraPose], so a shot path plays
     * in [CameraPathPlayer] like a [CameraPosePath].
     */
    @ExperimentalSceneViewApi
    override fun poseAt(seconds: Float): CameraPose = shotAt(seconds).toPose()

    /**
     * The pose [seconds] into lap [lap] of a [loop]ing path, for [CameraPathPlayer]: the turn of
     * the laps already played is reduced to under one turn in double precision before it reaches
     * a float, so lap 100 000 of a 1.05-turn tour is as exact as lap 1. [seconds] stays within the
     * lap.
     */
    @ExperimentalSceneViewApi
    internal fun poseAt(lap: Long, seconds: Float): CameraPose {
        val frames = keyframes
        if (!loop || frames.size < 2 || durationSeconds <= 0f) return poseAt(seconds)
        val shot = sample(frames.first().seconds + seconds.coerceIn(0f, durationSeconds))
        val turnPerLap = frames.last().shot.azimuthDegrees.toDouble() - frames.first().shot.azimuthDegrees
        val offset = (lap.toDouble() * turnPerLap).mod(FULL_TURN.toDouble())
        return shot.copy(azimuthDegrees = shot.azimuthDegrees + offset.toFloat()).toPose()
    }

    /** `true` once [seconds] is past the end of a path that does not loop. */
    fun isFinishedAt(seconds: Float): Boolean = !loop && seconds >= durationSeconds

    /** Hermite sample at absolute time [t] between the two keyframes around it, [easing] per segment. */
    private fun sample(t: Float): CameraShot {
        val frames = keyframes
        val last = frames.size - 1
        var i = 0
        while (i < last - 1 && t > frames[i + 1].seconds) i++
        val k0 = frames[i]
        val k1 = frames[i + 1]
        val span = k1.seconds - k0.seconds
        if (span <= 0f) return k1.shot
        val u = easing(((t - k0.seconds) / span).coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val m0 = tangentAt(i)
        val m1 = tangentAt(i + 1)
        val u2 = u * u
        val u3 = u2 * u
        val h00 = 2f * u3 - 3f * u2 + 1f
        val h10 = u3 - 2f * u2 + u
        val h01 = -2f * u3 + 3f * u2
        val h11 = u3 - u2
        fun blend(p0: Float, p1: Float, v0: Float, v1: Float): Float =
            h00 * p0 + h10 * span * v0 + h01 * p1 + h11 * span * v1
        val a = k0.shot
        val b = k1.shot
        return CameraShot(
            target = Position(
                blend(a.target.x, b.target.x, m0.targetPerSecond.x, m1.targetPerSecond.x),
                blend(a.target.y, b.target.y, m0.targetPerSecond.y, m1.targetPerSecond.y),
                blend(a.target.z, b.target.z, m0.targetPerSecond.z, m1.targetPerSecond.z),
            ),
            azimuthDegrees = blend(
                a.azimuthDegrees, b.azimuthDegrees, m0.azimuthDegreesPerSecond, m1.azimuthDegreesPerSecond,
            ),
            elevationDegrees = blend(
                a.elevationDegrees, b.elevationDegrees, m0.elevationDegreesPerSecond, m1.elevationDegreesPerSecond,
            ).coerceIn(-CameraShot.MAX_ELEVATION, CameraShot.MAX_ELEVATION),
            distance = blend(a.distance, b.distance, m0.distancePerSecond, m1.distancePerSecond)
                .coerceAtLeast(MIN_DISTANCE),
        )
    }

    /**
     * The velocity through keyframe [index]: the Catmull-Rom tangent, the mean velocity between
     * its neighbours (one-sided at the ends; the far neighbours across the seam when [loop]ing).
     */
    private fun tangentAt(index: Int): CameraShotVelocity {
        val frames = keyframes
        val last = frames.size - 1
        val before: CameraKeyframe
        val after: CameraKeyframe
        var span: Float
        when {
            loop && frames.size > 2 && (index == 0 || index == last) -> {
                // The seam: keyframe 0 and keyframe `last` are the same shot; look across it.
                before = frames[last - 1]
                after = frames[1]
                span = (frames[last].seconds - before.seconds) + (after.seconds - frames[0].seconds)
                return if (span <= 0f) {
                    CameraShotVelocity.Zero
                } else {
                    CameraShotVelocity.between(before.shot, after.shot.shifted(frames[last].shot, frames[0].shot), span)
                }
            }
            index == 0 -> {
                before = frames[0]
                after = frames[1]
            }
            index == last -> {
                before = frames[last - 1]
                after = frames[last]
            }
            else -> {
                before = frames[index - 1]
                after = frames[index + 1]
            }
        }
        span = after.seconds - before.seconds
        return CameraShotVelocity.between(before.shot, after.shot, span)
    }

    /** This shot, moved by the offset from [seamStart] to [seamEnd]: a looped azimuth that turned. */
    private fun CameraShot.shifted(seamEnd: CameraShot, seamStart: CameraShot): CameraShot =
        copy(azimuthDegrees = azimuthDegrees + (seamEnd.azimuthDegrees - seamStart.azimuthDegrees))

    companion object {
        /** No path brings the camera closer than this to its target. */
        const val MIN_DISTANCE = 0.01f

        /**
         * The reveal: the camera starts high, wide and a turn to the side, and cranes down onto
         * [home] over [durationSeconds] — the entrance of a scan result, a model, a room. The same
         * move as the demo app's replay entrance; [CameraEasing.Expressive] lands it softly.
         *
         * @param turnDegrees          How far round from [home] the shot starts. Negative: from the left.
         * @param startElevationDegrees Where the camera starts above the horizon.
         * @param startDistanceFactor  How many times further out it starts.
         */
        fun reveal(
            home: CameraShot,
            durationSeconds: Float = REVEAL_SECONDS,
            turnDegrees: Float = REVEAL_TURN_DEGREES,
            startElevationDegrees: Float = REVEAL_START_ELEVATION,
            startDistanceFactor: Float = REVEAL_DISTANCE_FACTOR,
            easing: (Float) -> Float = CameraEasing.Expressive,
        ): CameraPath {
            val start = home.copy(
                azimuthDegrees = home.azimuthDegrees + turnDegrees,
                elevationDegrees = startElevationDegrees.coerceIn(-CameraShot.MAX_ELEVATION, CameraShot.MAX_ELEVATION),
                distance = home.distance * startDistanceFactor,
            )
            return CameraPath(
                keyframes = listOf(CameraKeyframe(start, 0f), CameraKeyframe(home, durationSeconds)),
                easing = easing,
                shortestArc = false,
            )
        }

        /**
         * A tour: [turns] slow laps around [around]'s target at its distance and elevation, rising
         * by [riseDegrees] at the half-way point of each lap for life, at constant speed and looping
         * seamlessly — a share, a video, a result left on screen.
         *
         * A [loop] of a fractional number of [turns] (`1.5f`) still closes: its rise runs a whole
         * number of times over the path (the nearest to [turns], at least once) instead of once
         * per lap, so the camera is back at [around]'s elevation at the seam and the loop never
         * jumps. The azimuth keeps counting across the seam, as for whole turns.
         */
        fun tour(
            around: CameraShot,
            durationSeconds: Float = TOUR_SECONDS,
            turns: Float = 1f,
            riseDegrees: Float = TOUR_RISE_DEGREES,
            loop: Boolean = true,
        ): CameraPath {
            val laps = turns.coerceAtLeast(MIN_TURNS)
            val quarters = (laps * QUARTERS_PER_TURN).toInt().coerceAtLeast(2)
            val whole = laps == kotlin.math.floor(laps)
            // Rises over the path: one per lap, or a whole number of them for a fractional loop,
            // so the last keyframe is back at the start's elevation and the seam closes.
            val rises = if (whole || !loop) laps else kotlin.math.round(laps).coerceAtLeast(1f)
            val keyframes = (0..quarters).map { step ->
                val share = step.toFloat() / quarters
                val lapPhase = (share * rises) % 1f
                val rise = riseDegrees * kotlin.math.sin(Math.PI * lapPhase).toFloat()
                CameraKeyframe(
                    shot = around.copy(
                        azimuthDegrees = around.azimuthDegrees + share * laps * FULL_TURN,
                        elevationDegrees = (around.elevationDegrees + rise)
                            .coerceIn(-CameraShot.MAX_ELEVATION, CameraShot.MAX_ELEVATION),
                    ),
                    seconds = share * durationSeconds,
                )
            }
            return CameraPath(keyframes, easing = CameraEasing.Linear, shortestArc = false, loop = loop)
        }

        /**
         * A move through [shots] in order, [secondsPerShot] apart: a fly-through of the rooms of a
         * home, the objects of a scene. Each shot is carried onto on the spline, the short way round.
         */
        fun through(
            shots: List<CameraShot>,
            secondsPerShot: Float = THROUGH_SECONDS_PER_SHOT,
            easing: (Float) -> Float = CameraEasing.Expressive,
        ): CameraPath = CameraPath(
            keyframes = shots.mapIndexed { index, shot -> CameraKeyframe(shot, index * secondsPerShot) },
            easing = easing,
        )

        const val REVEAL_SECONDS = 2.8f
        const val REVEAL_TURN_DEGREES = -75f
        const val REVEAL_START_ELEVATION = 68f
        const val REVEAL_DISTANCE_FACTOR = 2.3f
        const val TOUR_SECONDS = 18f
        const val TOUR_RISE_DEGREES = 6f
        const val THROUGH_SECONDS_PER_SHOT = 1.6f
        private const val FULL_TURN = 360f
        private const val QUARTERS_PER_TURN = 4
        private const val MIN_TURNS = 0.25f
    }
}
