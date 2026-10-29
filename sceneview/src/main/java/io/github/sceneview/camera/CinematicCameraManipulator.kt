package io.github.sceneview.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.sceneview.RenderInvalidator
import io.github.sceneview.TransformBlend
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Transform

/**
 * A gesture manipulator that can be handed the camera mid-move: where a cinematic stops, the
 * gesture starts from that very [shot], at that very [velocity], so a drag on a turning camera
 * keeps turning under the finger. Implement it on an orbit manipulator that keeps its own pose
 * and inertia; [CinematicCameraManipulator] calls it on the first touch of a playing move, when a
 * move ends on its own (with [CameraShotVelocity.Zero]), and on [CameraAnimator.snapTo].
 */
interface CameraShotSink {
    /** The camera is yours from [shot], moving at [velocity]. */
    fun takeOver(shot: CameraShot, velocity: CameraShotVelocity)
}

/**
 * Cinematic moves over a gesture camera, in one `cameraManipulator`.
 *
 * While the [animator] plays, the camera is its [CameraAnimator.shot]; the first touch
 * interrupts the move and the [inner] manipulator takes over, without a cut:
 *
 * - an [inner] that is a [CameraShotSink] is handed the shot and the velocity, and carries on
 *   from there — the demo app's orbit camera does this;
 * - any other manipulator (the default orbit manipulator included) keeps its own pose, so the
 *   camera glides from the move's last shot onto that pose over [handoverSeconds], following it
 *   as the finger drags, the same glide a `cameraManipulator` swap gets.
 *
 * A move that reaches its end hands over the same way, at zero velocity: a sink is given the
 * last shot and the camera stays on it; any other manipulator is glided onto from it. A
 * [CameraAnimator.snapTo] is handed over too. Nothing cuts back to the gesture camera's old pose.
 *
 * Under `FrameRatePolicy.OnDemand` a parked scene wakes for a [CameraAnimator.play],
 * [CameraAnimator.flyTo] or [CameraAnimator.snapTo] made from anywhere — a button, a
 * `LaunchedEffect` — and keeps drawing for as long as [isFrameActive] says.
 *
 * `getTransform()` is called once per frame by the render loop and only then, as the
 * [CameraGestureDetector.CameraManipulator] contract requires; this calls [inner]'s at most
 * once per frame too.
 *
 * ```kotlin
 * val animator = remember { CameraAnimator(initial = home) }
 * SceneView(cameraManipulator = rememberCinematicCameraManipulator(animator)) { … }
 * LaunchedEffect(model) { animator.play(CameraPath.reveal(home)) }
 * ```
 */
class CinematicCameraManipulator(
    val animator: CameraAnimator,
    val inner: CameraGestureDetector.CameraManipulator,
    private val handoverSeconds: Float = HANDOVER_SECONDS,
) : CameraGestureDetector.CameraManipulator {

    private var blend: TransformBlend? = null

    /** The last transform shown while the animator played: where a handover glide starts. */
    private var lastAnimated: Transform? = null

    /** The animator owned the camera at the end of the last [update]: a stop since is a handover. */
    private var owned = false

    /** The [CameraAnimator.snapCount] already handed over. */
    private var seenSnaps = animator.snapCount

    /** `true` while the animator owns the camera. */
    val isPlaying: Boolean get() = animator.isPlaying

    /**
     * `true` while a move plays, a handover glide is in flight, a [CameraAnimator.snapTo] waits
     * for its frame, or [inner] owes frames: what keeps a `FrameRatePolicy.OnDemand` scene drawing.
     */
    override val isFrameActive: Boolean
        get() = animator.isPlaying || blend?.isFinished == false ||
            seenSnaps != animator.snapCount || inner.isFrameActive

    override fun setViewport(width: Int, height: Int) = inner.setViewport(width, height)

    override fun getTransform(): Transform {
        if (animator.isPlaying) {
            blend = null
            return animator.shot.transform().also { lastAnimated = it }
        }
        val live = inner.getTransform()
        val glide = blend ?: return live
        if (glide.isFinished) {
            blend = null
            return live
        }
        return glide.sample(live)
    }

    /** A touch-down stops [inner]'s coast too, as it would without the cinematic wrapper. */
    override fun stopInertia() = inner.stopInertia()

    override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
        interrupt()
        inner.grabBegin(x, y, strafe)
    }

    override fun grabUpdate(x: Int, y: Int) = inner.grabUpdate(x, y)

    override fun grabEnd() = inner.grabEnd()

    override fun scrollBegin(x: Int, y: Int, separation: Float) {
        interrupt()
        inner.scrollBegin(x, y, separation)
    }

    override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) =
        inner.scrollUpdate(x, y, prevSeparation, currSeparation)

    override fun scrollEnd() = inner.scrollEnd()

    override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) {
        interrupt()
        inner.doubleTapZoom(x, y, zoomIn)
    }

    override fun update(deltaTime: Float) {
        if (animator.isPlaying) animator.advance(deltaTime)
        blend?.advance(deltaTime)
        val snaps = animator.snapCount
        when {
            // A snap — at rest, or cutting a move: the camera stays on the snapped shot.
            snaps != seenSnaps -> {
                seenSnaps = snaps
                if (!animator.isPlaying) handOver(animator.shot, animator.shot.transform())
            }
            // The move ended (or was stopped on the animator) since the last frame: carry on from
            // its last shot instead of cutting back to the inner's old pose.
            owned && !animator.isPlaying -> handOver(animator.shot, animator.shot.transform())
        }
        owned = animator.isPlaying
        inner.update(deltaTime)
    }

    /**
     * Stops a playing move and hands the camera to [inner]: by shot and velocity when it is a
     * [CameraShotSink], by a glide from the last animated transform otherwise. Nothing happens
     * at rest.
     */
    fun interrupt() {
        if (!animator.isPlaying) return
        val velocity = animator.interrupt()
        owned = false
        handOver(animator.shot, lastAnimated ?: animator.shot.transform(), velocity)
    }

    /** Gives the camera to [inner]: [shot] and [velocity] to a sink, a glide from [from] otherwise. */
    private fun handOver(
        shot: CameraShot,
        from: Transform,
        velocity: CameraShotVelocity = CameraShotVelocity.Zero,
    ) {
        val sink = inner as? CameraShotSink
        if (sink != null) {
            sink.takeOver(shot, velocity)
            blend = null
        } else {
            blend = TransformBlend(from, handoverSeconds)
        }
    }

    /**
     * Wakes [invalidator] on every move started on [animator]; returns the call that stops it.
     * `SceneView` wires its own render invalidator here, because a parked render loop polls
     * nothing — not even [isFrameActive].
     */
    internal fun wakeOnMoves(invalidator: RenderInvalidator): () -> Unit =
        animator.addChangeListener { invalidator.requestRender() }

    companion object {
        /** The handover glide onto a manipulator that keeps its own pose: brisk, under a finger. */
        const val HANDOVER_SECONDS = 0.35f
    }
}

/**
 * Remembers a [CinematicCameraManipulator] over [animator] and [inner], rebuilt when either
 * changes. The render loop's camera-swap glide covers a rebuild, so neither cuts.
 */
@Composable
fun rememberCinematicCameraManipulator(
    animator: CameraAnimator,
    inner: CameraGestureDetector.CameraManipulator,
): CinematicCameraManipulator = remember(animator, inner) { CinematicCameraManipulator(animator, inner) }
