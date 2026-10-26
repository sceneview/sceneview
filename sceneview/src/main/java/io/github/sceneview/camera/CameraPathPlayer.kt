package io.github.sceneview.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.length
import io.github.sceneview.ExperimentalSceneViewApi
import kotlin.math.abs
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
     * The move being played. Replacing it keeps the playhead time and brings it into the new
     * duration at once, wrapped when [loop] is set and clamped otherwise: a looping player at
     * 15 s that switches from a 20 s to a 10 s timeline goes on from 5 s, and [seconds],
     * [progress] and [pose] agree from the next read. A looping player also keeps its lap count,
     * so a rebuilt [CameraPath.tour] of 1.5 turns does not jump back half a turn.
     *
     * [loop] changes only when the new timeline's [CameraTimeline.loop] differs from the old
     * one's, so a `loop` chosen on the player survives a rebuilt timeline of the same kind.
     *
     * A timeline of zero length holds the playhead at `0`. Unless [loop] is set, the next
     * [advance] then stops playback ([isPlaying] turns `false`), and it does not resume by itself
     * when a longer timeline comes back: call [play].
     *
     * Not snapshot state itself: assigning an equivalent timeline (a new object with the same
     * duration and loop) invalidates nothing, so a composition that rebuilds its timeline cannot
     * recompose itself through the player. [durationSeconds] and [loop] are observable.
     */
    var timeline: CameraTimeline = timeline
        set(value) {
            if (value === field) return
            val oldDuration = durationSeconds.toDouble()
            val wasLooping = loopState
            val laps = if (wasLooping && oldDuration > 0.0) floor(elapsed / oldDuration) else 0.0
            val local = elapsed - laps * oldDuration
            if (value.loop != field.loop) loopState = value.loop
            field = value
            durationState = value.durationSeconds
            cycleSeconds = cycleOf(value)
            setPlayhead(if (loopState) laps * value.durationSeconds + local else local)
        }

    private var loopState by mutableStateOf(loop)

    /**
     * Whether the playhead wraps to the start at the end, instead of stopping there. Turning it
     * off keeps the playhead where [seconds] shows it, within the current lap.
     */
    var loop: Boolean
        get() = loopState
        set(value) {
            if (value == loopState) return
            loopState = value
            if (!value) setPlayhead(seconds.toDouble())
        }

    /**
     * Length after which a looping [timeline] repeats exactly, a whole number of its laps; `0`
     * when none was found. See [cycleOf].
     */
    private var cycleSeconds: Double = cycleOf(timeline)

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

    /**
     * The playhead before wrapping: [seconds] plus the laps already played when [loop] is set,
     * reduced by whole [cycleSeconds]. A looping timeline is sampled here rather than at
     * [seconds], so a path that moves on from lap to lap carries on across the seam. Kept in
     * double precision so a long run of small frame steps does not drift.
     */
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

    /**
     * The pose at the playhead. Without [loop], or for a timeline that does not loop itself, it
     * is `timeline.poseAt(seconds)`. When both loop, the timeline is sampled at the playhead
     * before wrapping, so the laps it counts itself apply: a [CameraPath.tour] of 1.5 turns
     * starts its second lap half a turn on, with no jump at the seam. [seconds] and [progress]
     * stay within the current lap either way.
     */
    val pose: CameraPose
        get() = timeline.poseAt(if (loopState && timeline.loop) elapsed.toFloat() else seconds)

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
     * set, within the current lap). Playback state is unchanged. A non-finite value is ignored.
     */
    fun seekTo(seconds: Float) {
        if (!seconds.isFinite()) return
        val duration = durationSeconds.toDouble()
        val lapStart = if (loopState && duration > 0.0) floor(elapsed / duration) * duration else 0.0
        setPlayhead(lapStart + normalized(seconds.toDouble()))
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
                loop -> setPlayhead(next)
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

    /**
     * Sets the playhead from a time before wrapping: [seconds] gets it wrapped or clamped into
     * the timeline, [elapsed] keeps the laps when looping, less whole cycles.
     */
    private fun setPlayhead(value: Double) {
        val cycle = cycleSeconds
        elapsed = when {
            !loopState || durationSeconds <= 0f -> normalized(value)
            cycle > 0.0 -> value - floor(value / cycle) * cycle
            else -> value
        }
        seconds = normalized(value).toFloat()
    }

    /**
     * How long [timeline] takes to come back exactly to where it started, as a whole number of
     * its laps: one lap for a timeline that wraps by itself (a [CameraPosePath] or a whole-turn
     * [CameraPath.tour]), two for a tour of 1.5 turns, the duration itself for a timeline that
     * does not loop. Probed by comparing poses a whole number of laps apart, up to
     * [MAX_CYCLE_LAPS]; `0` when none matches, and the playhead then grows in double precision.
     */
    private fun cycleOf(timeline: CameraTimeline): Double {
        val duration = timeline.durationSeconds
        if (!duration.isFinite() || duration <= 0f) return 0.0
        if (!timeline.loop) return duration.toDouble()
        val probes = FloatArray(CYCLE_PROBES) { it * duration / CYCLE_PROBES }
        val reference = probes.map { timeline.poseAt(it) }
        for (laps in 1..MAX_CYCLE_LAPS) {
            val matches = probes.indices.all { i ->
                samePose(reference[i], timeline.poseAt(probes[i] + laps * duration))
            }
            if (matches) return laps * duration.toDouble()
        }
        return 0.0
    }

    private fun samePose(a: CameraPose, b: CameraPose): Boolean =
        length(a.position - b.position) <= CYCLE_TOLERANCE * (1f + length(a.position)) &&
            abs(dot(a.quaternion, b.quaternion)) >= 1f - CYCLE_TOLERANCE
}

/** [CameraPathPlayer]'s marker for "no frame timestamp recorded yet". */
private const val NO_FRAME = Long.MIN_VALUE

private const val NANOS_PER_SECOND = 1_000_000_000.0

/** Most laps [CameraPathPlayer] tries when looking for the cycle of a looping timeline. */
private const val MAX_CYCLE_LAPS = 12

/** Times per lap compared when looking for that cycle. */
private const val CYCLE_PROBES = 3

/** Relative tolerance of that comparison, on positions and on rotations. */
private const val CYCLE_TOLERANCE = 1e-4f

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
