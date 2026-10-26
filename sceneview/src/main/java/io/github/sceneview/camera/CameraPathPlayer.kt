package io.github.sceneview.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.sceneview.ExperimentalSceneViewApi
import kotlin.math.floor

/**
 * Plays a [CameraTimeline]: a playhead with play, pause, seek and loop.
 *
 * The player owns only the playhead, [seconds]. The pose always comes from
 * [CameraTimeline.poseAt] at that time, so playback, scrubbing and an offline render at a fixed
 * step agree on the pose for a given time. [advance] moves the playhead by the frame's time, not
 * by a fixed step per frame: a dropped frame does not slow the move down, and a 30 fps and a
 * 120 fps device reach the same pose at the same time.
 *
 * Its state is Compose snapshot state, so a slider or a clock that reads [seconds] or
 * [isPlaying] recomposes as the move plays. Call [advance] or [advanceFrame] from the main thread,
 * typically from `SceneView`'s `onFrame`, and write the returned pose with
 * [io.github.sceneview.node.CameraNode.applyPose]:
 *
 * ```kotlin
 * val player = rememberCameraPathPlayer(path)
 * SceneView(
 *     cameraNode = cameraNode,
 *     cameraManipulator = null, // the path owns the camera
 *     frameRatePolicy = FrameRatePolicy.Continuous(),
 *     onFrame = { nanos -> cameraNode.applyPose(player.advanceFrame(nanos)) },
 * )
 * ```
 *
 * The step is not capped: after a long stall the playhead jumps to where the wall clock says it
 * should be. [CameraAnimator] caps its step instead, because it simulates a spring; a timeline
 * has no state to destabilize.
 *
 * **Remember the timeline.** Build it inside `remember(...)` keyed on its inputs, not in the
 * composable body: a timeline built on every composition is a new object every time, which
 * rebuilds the spline on every recomposition. The player does not recompose because of it (only
 * a changed [durationSeconds] or [loop] is observable), but the work is wasted.
 *
 * **Offline rendering.** The player follows the wall clock. To render a video at a fixed rate,
 * sample the timeline directly, `timeline.poseAt(frameIndex / fps)`, rather than stepping a
 * player.
 *
 * @param timeline The move to play.
 * @param loop Whether the playhead wraps at the end. Defaults to the timeline's own
 * [CameraTimeline.loop].
 */
@ExperimentalSceneViewApi
@Stable
class CameraPathPlayer(
    timeline: CameraTimeline,
    loop: Boolean = timeline.loop,
) {

    /** Duration of [timeline], read when the timeline is set; observable by composition. */
    private var durationState by mutableFloatStateOf(timeline.durationSeconds)

    /**
     * The move being played. Replacing it keeps the playhead; [pose] clamps it to the new
     * duration, and the next [advance] or [seekTo] brings it back within range. [loop] follows
     * the new timeline's [CameraTimeline.loop].
     *
     * Not snapshot state itself: assigning an equivalent timeline (a new object with the same
     * duration and loop) invalidates nothing, so a composition that rebuilds its timeline cannot
     * recompose itself through the player. [durationSeconds] and [loop] are observable.
     */
    var timeline: CameraTimeline = timeline
        set(value) {
            if (value === field) return
            field = value
            durationState = value.durationSeconds
            loop = value.loop
        }

    /** Whether the playhead wraps to the start at the end, instead of stopping there. */
    var loop: Boolean by mutableStateOf(loop)

    private var speedState by mutableFloatStateOf(1f)

    /**
     * Playback rate: `1` is real time, `2` twice as fast, negative plays backward, `0` holds the
     * playhead while playing.
     *
     * @throws IllegalArgumentException when set to a non-finite value.
     */
    var speed: Float
        get() = speedState
        set(value) {
            require(value.isFinite()) { "speed must be finite, was $value" }
            speedState = value
        }

    /** Kept in double precision so a long run of small frame steps does not drift. */
    private var elapsed: Double = 0.0

    /** The playhead, in seconds from the start of [timeline], within `[0, durationSeconds]`. */
    var seconds: Float by mutableFloatStateOf(0f)
        private set

    /** `true` while [advance] moves the playhead. */
    var isPlaying: Boolean by mutableStateOf(false)
        private set

    /** Length of [timeline], in seconds, as read when the timeline was set. */
    val durationSeconds: Float get() = durationState

    /** The playhead as a fraction of [durationSeconds], in `[0, 1]`; `0` for an empty timeline. */
    val progress: Float
        get() = durationSeconds.let { if (it > 0f) (seconds / it).coerceIn(0f, 1f) else 0f }

    /** `true` once a non-looping playhead has reached the end (or the start, playing backward). */
    val isFinished: Boolean
        get() = !loop && if (speed < 0f) seconds <= 0f else seconds >= durationSeconds

    /** The pose at the playhead: `timeline.poseAt(seconds)`. */
    val pose: CameraPose get() = timeline.poseAt(seconds)

    private var lastFrameNanos: Long = NO_FRAME

    /** Starts or resumes playback. A finished, non-looping player restarts from the beginning. */
    fun play() {
        if (isFinished) setPlayhead(if (speed < 0f) durationSeconds.toDouble() else 0.0)
        isPlaying = true
        lastFrameNanos = NO_FRAME
    }

    /** Stops the playhead where it is. */
    fun pause() {
        isPlaying = false
        lastFrameNanos = NO_FRAME
    }

    /** [play] when paused, [pause] when playing. */
    fun toggle() {
        if (isPlaying) pause() else play()
    }

    /**
     * Moves the playhead to [seconds], clamped to `[0, durationSeconds]` (wrapped when [loop] is
     * set). Playback state is unchanged. A non-finite value is ignored.
     */
    fun seekTo(seconds: Float) {
        if (!seconds.isFinite()) return
        setPlayhead(normalized(seconds.toDouble()))
    }

    /**
     * Moves the playhead by [deltaSeconds] of wall-clock time times [speed] while playing, and
     * returns the pose at the new playhead. A non-looping player stops when the playhead reaches
     * the end it is heading for: the end playing forward, the start playing backward. At speed
     * `0` it keeps playing in place.
     *
     * A zero, negative or non-finite [deltaSeconds] leaves the playhead where it is.
     */
    fun advance(deltaSeconds: Float): CameraPose {
        if (isPlaying && deltaSeconds.isFinite() && deltaSeconds > 0f) {
            val duration = durationSeconds.toDouble()
            val next = elapsed + deltaSeconds.toDouble() * speed
            when {
                duration <= 0.0 -> {
                    setPlayhead(0.0)
                    if (!loop) isPlaying = false
                }
                loop -> setPlayhead(normalized(next))
                speed > 0f && next >= duration -> {
                    setPlayhead(duration)
                    isPlaying = false
                }
                speed < 0f && next <= 0.0 -> {
                    setPlayhead(0.0)
                    isPlaying = false
                }
                else -> setPlayhead(next.coerceIn(0.0, duration))
            }
        }
        return pose
    }

    /**
     * [advance] by the time since the previous call, from a frame timestamp such as the one
     * `SceneView`'s `onFrame` receives. The first frame after [play], [pause] or construction
     * only records the timestamp.
     */
    fun advanceFrame(frameTimeNanos: Long): CameraPose {
        val previous = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        val delta = if (previous == NO_FRAME || frameTimeNanos <= previous) {
            0f
        } else {
            ((frameTimeNanos - previous) / NANOS_PER_SECOND).toFloat()
        }
        return advance(delta)
    }

    private fun normalized(value: Double): Double {
        val duration = durationSeconds.toDouble()
        if (duration <= 0.0) return 0.0
        if (!loop || value in 0.0..duration) return value.coerceIn(0.0, duration)
        return (value - floor(value / duration) * duration).coerceIn(0.0, duration)
    }

    private fun setPlayhead(value: Double) {
        elapsed = value
        seconds = value.toFloat()
    }

    private companion object {
        const val NO_FRAME = Long.MIN_VALUE
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

/**
 * Remembers a [CameraPathPlayer] for [timeline].
 *
 * The player survives recomposition; a different [timeline] object replaces the one it plays and
 * keeps the playhead. [autoPlay] starts playback when the player is first created.
 *
 * Pass a remembered timeline, `remember(bounds, aspect) { CameraPosePath.orbit(...) }`: one built
 * in the composable body is a new object on every composition (see [CameraPathPlayer]).
 */
@ExperimentalSceneViewApi
@Composable
fun rememberCameraPathPlayer(
    timeline: CameraTimeline,
    autoPlay: Boolean = true,
): CameraPathPlayer {
    val player = remember { CameraPathPlayer(timeline).also { if (autoPlay) it.play() } }
    SideEffect { if (player.timeline !== timeline) player.timeline = timeline }
    return player
}
