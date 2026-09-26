package io.github.sceneview

import dev.romainguy.kotlin.math.mix
import dev.romainguy.kotlin.math.slerp
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import io.github.sceneview.math.quaternion
import kotlin.math.max
import kotlin.math.min

// Motion continuity: the few rules that keep what is on screen from jumping between two frames.
//
// Every one of them answers the same failure: a value that is *on screen* being replaced, in a
// single frame, by a value computed somewhere else — a rebuilt camera manipulator, a re-centred
// content root, a time step that swallowed a stall. The pieces here are pure (no Filament, no
// Android), so their behaviour is pinned on the JVM by `MotionContinuityTest`; `SceneView`'s
// render loop, `NodeAnimationDelegate` and `SceneAutoCenterState` only wire them in.

/**
 * Time step handed to a frame when there is no previous frame to measure against: the loop
 * just started, or it resumed after an on-demand park. One nominal 60 Hz frame — small enough
 * that nothing visibly moves, large enough that an exponential ease still makes progress (a zero
 * step reads as "converged" to a convergence test built on "did it move?").
 */
internal const val NOMINAL_FRAME_SECONDS: Double = 1.0 / 60.0

/**
 * Largest time step a single frame may advance motion by. A main-thread stall — a GLB upload, a
 * material link, an HDR decode on the UI thread — delivers the next frame hundreds of
 * milliseconds late; advancing by the whole gap spends an ease or a fling in one frame, which is
 * a cut. Capped, the motion resumes where it stopped: it runs a little long, it never jumps.
 */
internal const val MAX_FRAME_DELTA_SECONDS: Double = 0.05

/** How long a camera swap glides from the pose on screen to the new manipulator's pose. */
internal const val CAMERA_SWAP_BLEND_SECONDS: Float = 0.6f

/** How long an auto-centre correction glides when the content it moves is already on screen. */
internal const val AUTO_CENTER_GLIDE_SECONDS: Float = 0.4f

/**
 * Seconds a frame at [frameTimeNanos] should advance motion by, given the previous frame at
 * [previousFrameTimeNanos].
 *
 * - no previous frame (`null`, or the loop's `0` "unset" marker) → [NOMINAL_FRAME_SECONDS].
 *   The previous `intervalSeconds(null)` measured from time zero, so the first frame after
 *   every on-demand park handed `CameraManipulator.update()` the whole device uptime;
 * - a timestamp that went backwards → `0`;
 * - anything longer than [MAX_FRAME_DELTA_SECONDS] (a stall, a park) → that cap.
 */
internal fun frameDeltaSeconds(frameTimeNanos: Long, previousFrameTimeNanos: Long?): Double {
    if (previousFrameTimeNanos == null || previousFrameTimeNanos == 0L) return NOMINAL_FRAME_SECONDS
    val seconds = (frameTimeNanos - previousFrameTimeNanos) / 1_000_000_000.0
    return seconds.coerceIn(0.0, MAX_FRAME_DELTA_SECONDS)
}

/** Cubic ease-out: fast start, gentle landing. `t` is clamped to `[0, 1]`. */
internal fun easeOutCubic(t: Float): Float {
    val clamped = t.coerceIn(0f, 1f)
    val inverse = 1f - clamped
    return 1f - inverse * inverse * inverse
}

/**
 * Glides from a fixed [from] transform to a *moving* target over [durationSeconds].
 *
 * The target is sampled every frame rather than captured once, so a target that keeps moving
 * during the glide — a manipulator the user is already dragging, a turntable that is turning —
 * is followed, and the glide lands exactly on it. Position and scale are mixed, rotation is
 * slerped, all with [easeOutCubic].
 */
internal class TransformBlend(from: Transform, private val durationSeconds: Float) {
    private val fromPosition = from.position
    private val fromQuaternion = from.quaternion
    private val fromScale = from.scale

    /** Seconds advanced so far, never past [durationSeconds]. */
    var elapsedSeconds: Float = 0f
        private set

    /** `true` once the glide has reached its target; [sample] then returns the target as is. */
    val isFinished: Boolean get() = durationSeconds <= 0f || elapsedSeconds >= durationSeconds

    /** Advances the glide by [deltaSeconds]; a negative step is ignored. */
    fun advance(deltaSeconds: Float) {
        elapsedSeconds = min(elapsedSeconds + max(deltaSeconds, 0f), max(durationSeconds, 0f))
    }

    /** The pose to show this frame, given where the target is ([live]) this frame. */
    fun sample(live: Transform): Transform {
        if (isFinished) return live
        val t = easeOutCubic(elapsedSeconds / durationSeconds)
        return Transform(
            position = mix(fromPosition, live.position, t),
            quaternion = slerp(fromQuaternion, live.quaternion, t),
            scale = mix(fromScale, live.scale, t)
        )
    }
}

/**
 * Keeps a camera-manipulator swap from cutting.
 *
 * A manipulator owns the whole camera pose. Handing `SceneView` a different instance — the
 * documented "rebuild it once the model has loaded" pattern, a keyed `rememberCameraManipulator`
 * whose `orbitRadius` changed, a mode picker — used to show the new manipulator's pose on the
 * very next frame. Now the first frame of a new manipulator starts a [TransformBlend] from the
 * pose that is on screen, and the camera glides onto the new manipulator's live pose over
 * [durationSeconds]. A second swap mid-glide starts again from the blended pose on screen, so a
 * burst of rebuilds never jumps either.
 *
 * The first manipulator of a scene that has not presented a frame yet is not blended: there is
 * nothing on screen to be continuous with.
 */
internal class CameraSwapContinuity(
    private val durationSeconds: Float = CAMERA_SWAP_BLEND_SECONDS
) {
    private var source: Any? = null
    private var blend: TransformBlend? = null

    /** `true` while a swap glide is in flight — the render loop must keep drawing. */
    val isBlending: Boolean get() = blend != null

    /**
     * Returns the pose to show this frame.
     *
     * @param manipulator  the instance driving the camera this frame (compared by identity).
     * @param livePose     that manipulator's pose this frame, after its `update()`.
     * @param deltaSeconds this frame's time step (see [frameDeltaSeconds]).
     * @param canBlend     whether a frame has already been presented — i.e. whether there is a
     *                     pose on screen to glide from.
     * @param shownPose    the pose on screen right now; read only when a swap is detected.
     */
    fun resolve(
        manipulator: Any,
        livePose: Transform,
        deltaSeconds: Float,
        canBlend: Boolean,
        shownPose: () -> Transform
    ): Transform {
        if (manipulator !== source) {
            val hadSource = source != null
            source = manipulator
            blend = if (canBlend || hadSource) {
                TransformBlend(from = shownPose(), durationSeconds = durationSeconds)
            } else {
                null
            }
        } else {
            blend?.advance(deltaSeconds)
        }
        val active = blend ?: return livePose
        val pose = active.sample(livePose)
        if (active.isFinished) blend = null
        return pose
    }

    /**
     * The scene has no manipulator this frame. The next one to arrive is a swap — the camera it
     * replaces is whatever is on screen — so only the identity is forgotten.
     */
    fun clearSource() {
        source = null
        blend = null
    }
}

/**
 * Eases a position from where it is to a new target over [durationSeconds] instead of writing
 * the target in one frame. Used by the auto-centre pass when content that is already on screen
 * has to move to keep the union centred (a second model joined the scene).
 */
internal class PositionEase(private val durationSeconds: Float = AUTO_CENTER_GLIDE_SECONDS) {
    private var from: Position = Position()
    private var to: Position = Position()
    private var elapsedSeconds = 0f

    /** `true` while the ease still has distance to cover. */
    var isActive: Boolean = false
        private set

    /**
     * Starts (or re-targets) the ease from [from] — pass the position on screen, so a re-target
     * mid-flight continues from wherever the eased value is — to [to].
     */
    fun start(from: Position, to: Position) {
        this.from = from
        this.to = to
        elapsedSeconds = 0f
        isActive = from != to && durationSeconds > 0f
    }

    /** Stops the ease where it is. */
    fun cancel() {
        isActive = false
    }

    /**
     * Advances by [deltaSeconds] and returns the position to show this frame, or `null` when no
     * ease is in flight. The last call of an ease returns exactly the target.
     */
    fun advance(deltaSeconds: Float): Position? {
        if (!isActive) return null
        elapsedSeconds = min(elapsedSeconds + max(deltaSeconds, 0f), durationSeconds)
        if (elapsedSeconds >= durationSeconds) {
            isActive = false
            return to
        }
        return mix(from, to, easeOutCubic(elapsedSeconds / durationSeconds))
    }
}
