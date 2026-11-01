package io.github.sceneview.demo.demos.internal

import io.github.sceneview.camera.CameraAnimator
import io.github.sceneview.camera.CameraEasing
import io.github.sceneview.camera.CameraKeyframe
import io.github.sceneview.camera.CameraPath
import io.github.sceneview.camera.CameraShot
import io.github.sceneview.capture.VideoSettings
import kotlin.math.ceil

/**
 * The scan result's film: one deterministic camera move, played on the result screen while
 * `SceneRecorder` encodes it, so a scan leaves the phone as a short vertical clip.
 *
 * Three shots, each the library's own [CameraPath], each starting where the last one ends:
 * 1. the reveal ([CameraPath.reveal], the result's own entrance, with `ModelReveal` over it);
 * 2. one turn round the scan at the framing's distance, rising a little half way;
 * 3. the crane up onto the floor plan, held for a beat so the clip ends on the room's shape.
 *
 * Pure: [shotAt] is the camera at any time of the film, what the tests pin; [play] chains the
 * three on a [CameraAnimator].
 */
internal class ResultFilm private constructor(
    val reveal: CameraPath,
    val turn: CameraPath,
    val toPlan: CameraPath,
) {
    /** When each shot starts, seconds into the film. */
    val turnStart: Float get() = reveal.durationSeconds
    val planStart: Float get() = turnStart + turn.durationSeconds

    /** The whole film, the hold on the plan included. */
    val durationSeconds: Float get() = planStart + toPlan.durationSeconds + HOLD_SECONDS

    /** The clip's frames at [FPS], the last one on the plan. */
    val frameCount: Int get() = ceil(durationSeconds * FPS).toInt()

    /**
     * What the clip is encoded at: 720p on its short edge (the view's own shape, so a phone held
     * upright films upright), [FPS], and a hard stop a little after the film in case the caller's
     * clock never gets to stop it.
     */
    val video: VideoSettings
        get() = VideoSettings(
            maxLongEdge = VIDEO_LONG_EDGE,
            maxShortEdge = VIDEO_SHORT_EDGE,
            frameRate = FPS,
            bitRate = VIDEO_BIT_RATE,
            maxDurationMillis = ceil(durationSeconds * MILLIS).toLong() + STOP_MARGIN_MILLIS,
        )

    /** The camera [seconds] into the film; past its end, the plan. */
    fun shotAt(seconds: Float): CameraShot = when {
        seconds < turnStart -> reveal.shotAt(seconds.coerceAtLeast(0f))
        seconds < planStart -> turn.shotAt(seconds - turnStart)
        else -> toPlan.shotAt(seconds - planStart)
    }

    /**
     * Plays the film on [animator] from its first frame; [onLanded] once the crane is on the plan.
     * The hold that follows is the animator at rest on the plan: the caller stops the recording
     * [durationSeconds] after it started, so the clip ends on a still frame of the plan.
     */
    fun play(animator: CameraAnimator, onLanded: () -> Unit = {}) {
        animator.play(reveal, onFinished = {
            animator.play(turn, onFinished = {
                animator.play(toPlan, onFinished = onLanded)
            })
        })
    }

    companion object {
        /** The turn: one lap, about the pace of a person walking round a table. */
        const val TURN_SECONDS = 4.2f

        /** How far the turn lifts the camera at its half, degrees: the top of the room comes in. */
        const val TURN_RISE_DEGREES = 8f

        /** The crane onto the plan, and how long the clip stays on it. */
        const val PLAN_SECONDS = 1.6f
        const val HOLD_SECONDS = 1.2f

        /** The clip's frame rate: what the tests step the film at. */
        const val FPS = 30

        /**
         * The film from [home], the result's framing, to [plan], the floor plan's framing (the
         * same turn, straight down). The reveal is the result's own entrance, [revealSeconds] long.
         */
        fun of(home: CameraShot, plan: CameraShot, revealSeconds: Float = CameraPath.REVEAL_SECONDS): ResultFilm {
            val reveal = CameraPath.reveal(home, durationSeconds = revealSeconds)
            val half = TURN_SECONDS / 2f
            val turn = CameraPath(
                keyframes = listOf(
                    CameraKeyframe(home, 0f),
                    CameraKeyframe(
                        home.copy(
                            azimuthDegrees = home.azimuthDegrees + HALF_TURN,
                            elevationDegrees = (home.elevationDegrees + TURN_RISE_DEGREES)
                                .coerceAtMost(CameraShot.MAX_ELEVATION),
                        ),
                        half,
                    ),
                    CameraKeyframe(home.copy(azimuthDegrees = home.azimuthDegrees + FULL_TURN), TURN_SECONDS),
                ),
                // Out of the reveal's landing at rest and onto the crane at rest: no jolt either side.
                easing = CameraEasing.InOut,
                shortestArc = false,
            )
            // The plan at the turn's end azimuth, a full turn on: the crane goes straight up.
            val landed = home.copy(azimuthDegrees = home.azimuthDegrees + FULL_TURN)
            val toPlan = CameraPath(
                keyframes = listOf(
                    CameraKeyframe(landed, 0f),
                    CameraKeyframe(plan.copy(azimuthDegrees = landed.azimuthDegrees), PLAN_SECONDS),
                ),
                easing = CameraEasing.Expressive,
                shortestArc = false,
            )
            return ResultFilm(reveal, turn, toPlan)
        }

        private const val VIDEO_LONG_EDGE = 1280
        private const val VIDEO_SHORT_EDGE = 720
        private const val VIDEO_BIT_RATE = 8_000_000
        private const val MILLIS = 1000f
        private const val STOP_MARGIN_MILLIS = 1_000L
        private const val HALF_TURN = 180f
        private const val FULL_TURN = 360f
    }
}
