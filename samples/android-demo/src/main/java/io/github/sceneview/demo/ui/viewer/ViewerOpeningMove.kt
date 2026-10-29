package io.github.sceneview.demo.ui.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.lookAt
import io.github.sceneview.camera.CinematicCameraManipulator
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.awaitSteadyFrames
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The opening move every 3D viewer of the demo shares (#4651, #3406): when the subject arrives,
 * the camera does not cut to the resting framing, it is flown there — from wider, a little to
 * the side and higher, onto the framing, over `motion-entrance` (`tween(700ms, ease-expressive)`
 * in DESIGN.md). The subject never moves, so the lights and reflections land where the resting
 * frame shows them.
 *
 * Pure maths here; [EntranceCameraManipulator][io.github.sceneview.demo.EntranceCameraManipulator]
 * (the Model Viewer) and [OpeningMoveCameraManipulator] (every viewer on a hero orbit) draw it.
 * A zoom eases on the same curve ([zoomDistance]), so a double tap reads as the same camera.
 */
object ViewerOpeningMove {
    /** `motion-entrance`: long enough to be seen, short enough that no drag waits on it. */
    const val DURATION_MILLIS: Int = SceneViewTokens.Duration.longMillis

    /** `ease-expressive`: leaves gently, lands softly. */
    val easing: Easing = SceneViewTokens.Ease.expressive

    /**
     * Largest step, in seconds, one frame may advance the move by. The frames that first show a
     * model are the ones its upload drops; a move timed on the wall clock spends itself in those
     * gaps and reaches the screen as a cut. Capped, a dropped frame only makes it run longer.
     */
    const val MAX_STEP_SECONDS: Float = 1f / 20f

    /** How many times further out than the resting framing the move starts. */
    const val START_DISTANCE_SCALE: Float = 1.55f

    /** How far round the subject, in degrees, the move starts. */
    const val START_YAW_DEGREES: Float = 24f

    /** How much higher the move starts, in resting eye-to-target distances. */
    const val START_LIFT_FRACTION: Float = 0.22f

    /**
     * How long the rest of a move takes to fold into the camera after a touch stops it: the SDK's
     * cinematic handover, brisk under a finger.
     */
    const val HANDOVER_SECONDS: Float = CinematicCameraManipulator.HANDOVER_SECONDS

    /**
     * The eye at [progress] (`0` the start, `1` the end) of the move onto [rest], looking at
     * [target]: the offset from the target swung round world +Y, pushed out along itself and
     * lifted, each by what is left of the move. `progress >= 1` is [rest] itself.
     */
    @Suppress("LongParameterList")
    fun eye(
        rest: Position,
        target: Position,
        progress: Float,
        startDistanceScale: Float = START_DISTANCE_SCALE,
        startYawDegrees: Float = START_YAW_DEGREES,
        startLiftFraction: Float = START_LIFT_FRACTION,
    ): Position {
        val p = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        if (p >= 1f) return rest
        val remaining = 1f - p
        val dx = rest.x - target.x
        val dy = rest.y - target.y
        val dz = rest.z - target.z
        val radius = sqrt(dx * dx + dy * dy + dz * dz)
        // Degenerate framing: nothing to fly along, sit at the resting pose.
        if (!radius.isFinite() || radius <= MIN_RADIUS) return rest
        val yaw = Math.toRadians((startYawDegrees * remaining).toDouble()).toFloat()
        val c = cos(yaw)
        val s = sin(yaw)
        val scale = 1f + (startDistanceScale - 1f) * remaining
        val lift = radius * startLiftFraction * remaining
        return Position(
            x = target.x + (dx * c + dz * s) * scale,
            y = target.y + (dy + lift) * scale,
            z = target.z + (dz * c - dx * s) * scale,
        )
    }

    /** The move's eased progress [elapsedSeconds] into a move of [durationSeconds]. */
    fun progressAt(elapsedSeconds: Float, durationSeconds: Float = DURATION_MILLIS / MILLIS_PER_SECOND): Float {
        if (durationSeconds <= 0f) return 1f
        return easing.transform((elapsedSeconds / durationSeconds).coerceIn(0f, 1f))
    }

    /** One frame's advance: [frameSeconds] capped at [MAX_STEP_SECONDS], never negative. */
    fun step(frameSeconds: Float): Float =
        frameSeconds.takeIf { it.isFinite() }?.coerceIn(0f, MAX_STEP_SECONDS) ?: 0f

    /**
     * What is left of a move a touch stopped with [remainingAtTouch] of it left, [seconds] after
     * the touch: it folds into the camera over [HANDOVER_SECONDS], decelerating, so the camera
     * neither jumps under the finger nor keeps flying away from it.
     */
    fun handoverRemaining(remainingAtTouch: Float, seconds: Float): Float {
        val r = remainingAtTouch.coerceIn(0f, 1f)
        val t = (seconds / HANDOVER_SECONDS).coerceIn(0f, 1f)
        val left = 1f - t
        return r * left * left
    }

    /**
     * The camera distance [progress] of the way through a zoom from [start] to [target], eased on
     * the opening move's curve, in log space so every step is the same share of the distance.
     */
    fun zoomDistance(start: Float, target: Float, progress: Float): Float {
        if (!start.isFinite() || start <= 0f) return target
        if (!target.isFinite() || target <= 0f) return start
        val t = easing.transform(progress.coerceIn(0f, 1f))
        val d = start * exp(ln(target / start) * t)
        return if (d.isFinite() && d > 0f) d else target
    }

    private const val MIN_RADIUS = 1e-6f
    private const val MILLIS_PER_SECOND = 1000f
}

/**
 * Animates this value from where it is to `1f` over [durationMillis] of *frames*, eased by
 * [easing]. Unlike `animateTo(tween(...))`, which reads the wall clock, each frame advances by at
 * most [ViewerOpeningMove.MAX_STEP_SECONDS], so a stall pauses the move instead of skipping it.
 */
suspend fun Animatable<Float, *>.flyToOne(
    durationMillis: Int = ViewerOpeningMove.DURATION_MILLIS,
    easing: Easing = ViewerOpeningMove.easing,
) {
    val duration = durationMillis / 1000f
    val start = value
    var elapsed = 0f
    var last = withFrameNanos { it }
    while (elapsed < duration) {
        val now = withFrameNanos { it }
        elapsed += ViewerOpeningMove.step((now - last) / 1_000_000_000f)
        last = now
        val t = easing.transform((elapsed / duration).coerceAtMost(1f))
        snapTo(start + (1f - start) * t)
    }
}

/**
 * The [ViewerOpeningMove] over any gesture camera — the hero orbits of the Explore viewer and
 * the other viewers that do not own their pose the way the Model Viewer does.
 *
 * The move is an *offset* over [inner]'s live pose that shrinks to nothing as [progress] reaches
 * `1`: [inner] keeps orbiting underneath, and the last frame of the move is exactly [inner]'s
 * pose, so the end is no cut either. The first touch stops the move: what is left of it folds into
 * the camera over [ViewerOpeningMove.HANDOVER_SECONDS] from where it stands, while [inner] already
 * follows the finger — the handover the SDK's [CinematicCameraManipulator] gives a manipulator that
 * keeps its own pose. Nothing replays under a finger: a touched move stays stopped until
 * [startOver].
 */
class OpeningMoveCameraManipulator(
    val inner: CameraGestureDetector.CameraManipulator,
    private val target: () -> Position,
    private val progress: () -> Float,
) : CameraGestureDetector.CameraManipulator {

    /** What was left of the move when a touch stopped it; `null` while untouched. */
    private var remainingAtTouch: Float? = null
    private var handoverSeconds = 0f

    /** Share of the move still to show, `0` once it is over. */
    val remaining: Float
        get() {
            val touched = remainingAtTouch
            if (touched != null) return ViewerOpeningMove.handoverRemaining(touched, handoverSeconds)
            val p = progress().takeIf { it.isFinite() } ?: 1f
            return (1f - p).coerceIn(0f, 1f)
        }

    /** `true` once a touch has stopped the move. */
    val isTouched: Boolean get() = remainingAtTouch != null

    override val isFrameActive: Boolean
        get() = inner.isFrameActive || (remaining > 0f && (isTouched || progress() > 0f))

    /** A new subject: the next move plays from its start again. */
    fun startOver() {
        remainingAtTouch = null
        handoverSeconds = 0f
    }

    override fun setViewport(width: Int, height: Int) = inner.setViewport(width, height)

    override fun getTransform(): Transform {
        val live = inner.getTransform()
        val left = remaining
        if (left <= 0f) return live
        val center = target()
        val eye = ViewerOpeningMove.eye(rest = live.position, target = center, progress = 1f - left)
        return Transform(lookAt(eye = eye, target = center, up = WORLD_UP))
    }

    private fun stop() {
        if (remainingAtTouch != null) return
        remainingAtTouch = remaining
        handoverSeconds = 0f
    }

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        stop()
        inner.grabBegin(x, y, strafe)
    }

    override fun grabUpdate(x: Int, y: Int) = inner.grabUpdate(x, y)

    override fun grabEnd() = inner.grabEnd()

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        stop()
        inner.scrollBegin(x, y, separation)
    }

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) =
        inner.scrollUpdate(x, y, prevSeparation, currSeparation)

    override fun scrollEnd() = inner.scrollEnd()

    override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) {
        stop()
        inner.doubleTapZoom(x, y, zoomIn)
    }

    override fun update(deltaTime: Float) {
        if (remainingAtTouch != null) handoverSeconds += ViewerOpeningMove.step(deltaTime)
        inner.update(deltaTime)
    }

    private companion object {
        val WORLD_UP = Float3(0f, 1f, 0f)
    }
}

/**
 * [inner] with the shared [ViewerOpeningMove] in front of it: the move plays once [ready] — the
 * frame that shows [subject] is up — and again for each new [subject]. Until then the camera holds
 * the move's first shot, so the subject appears *as* the move starts. In
 * [DemoSettings.qaMode] the camera rests at once: a screenshot taken mid-move frames the subject
 * differently every run.
 *
 * @param target The point the camera orbits, the one [inner] looks at.
 * @param requestRender Wakes an on-demand render loop for the move's frames.
 */
@Composable
fun rememberViewerOpeningMove(
    inner: CameraGestureDetector.CameraManipulator,
    target: Position,
    subject: Any?,
    ready: Boolean,
    requestRender: () -> Unit = {},
): OpeningMoveCameraManipulator {
    val progress = remember { Animatable(0f) }
    val liveTarget = rememberUpdatedState(target)
    val move = remember(inner) {
        OpeningMoveCameraManipulator(inner, target = { liveTarget.value }, progress = { progress.value })
    }
    val qaMode = DemoSettings.qaMode
    LaunchedEffect(move, subject, ready, qaMode) {
        if (qaMode) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        move.startOver()
        progress.snapTo(0f)
        if (!ready) return@LaunchedEffect
        // The frame that shows the subject is followed by the ones its upload drops; starting
        // inside them would spend the move behind a frozen picture.
        awaitSteadyFrames()
        requestRender()
        progress.flyToOne()
    }
    return move
}
