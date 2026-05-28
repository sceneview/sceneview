package io.github.sceneview.node

import io.github.sceneview.math.Transform
import io.github.sceneview.math.equals
import io.github.sceneview.math.quaternion
import io.github.sceneview.math.slerp
import io.github.sceneview.utils.intervalSeconds

/**
 * Handles smooth (interpolated) transform animation for a [Node].
 *
 * When [smoothTransform] is set, the delegate interpolates the node's current transform
 * towards the target each frame using spherical-linear interpolation at the given
 * [smoothTransformSpeed]. Once the target is reached (within a tolerance of 0.001),
 * [onSmoothEnd] is invoked and the animation clears itself.
 *
 * Typical usage: call [Node.transform] with `smooth = true` which sets [smoothTransform].
 * The per-frame interpolation is driven by [onFrame], which the owning [Node] calls from
 * its own `onFrame`.
 *
 * @param node The owning [Node] whose transform is being animated.
 */
class NodeAnimationDelegate(
    private val node: Node
) {
    /** Whether [Node.transform] calls should default to smooth interpolation. */
    var isSmoothTransformEnabled = false

    /**
     * The smooth position, rotation and scale speed.
     *
     * Higher values make the interpolation converge faster.
     */
    var smoothTransformSpeed = 5.0f

    /** Target transform for smooth interpolation, or `null` when no animation is active. */
    var smoothTransform: Transform? = null

    /** Invoked when a smooth transform animation reaches its target. */
    var onSmoothEnd: ((node: Node) -> Unit)? = null

    private var lastFrameTimeNanos: Long? = null

    /**
     * Advances the smooth interpolation by one frame tick.
     *
     * Should be called from [Node.onFrame] **before** propagating to children.
     *
     * @param frameTimeNanos wall-clock time of the current frame in nanoseconds.
     */
    fun onFrame(frameTimeNanos: Long) {
        smoothTransform?.let { target ->
            // #2265: read node.transform ONCE per frame (was 3 Filament JNI round-trips —
            // the `target != node.transform` check, the slerp `start`, and the convergence
            // `equals`). `current` is reused for both exact comparisons so behaviour is
            // unchanged.
            val current = node.transform
            if (target != current) {
                // Feed slerp the node's #2187-cached local TRS (Node.position / quaternion /
                // scale return the pristine `_position` / `_quaternion` / `_scale` — no extra
                // Mat4 decomposition) via the TRS-tuple overload, instead of re-decomposing
                // `current` inside slerp. Cuts the per-call matrix decompositions from 6 to 3
                // (only `target` is decomposed). `node.position/quaternion/scale` are the same
                // decomposition `current` would yield, so the trajectory is identical.
                val (slerpPosition, slerpQuaternion, slerpScale) = slerp(
                    startPosition = node.position,
                    startQuaternion = node.quaternion,
                    startScale = node.scale,
                    endPosition = target.position,
                    endQuaternion = target.quaternion,
                    endScale = target.scale,
                    deltaSeconds = frameTimeNanos.intervalSeconds(lastFrameTimeNanos),
                    speed = smoothTransformSpeed
                )
                val slerpTransform = Transform(slerpPosition, slerpQuaternion, slerpScale)
                if (!slerpTransform.equals(current, delta = 0.001f)) {
                    node.transform = slerpTransform
                } else {
                    node.transform = target
                    smoothTransform = null
                    onSmoothEnd?.invoke(node)
                }
            } else {
                smoothTransform = null
            }
        }
        lastFrameTimeNanos = frameTimeNanos
    }
}
