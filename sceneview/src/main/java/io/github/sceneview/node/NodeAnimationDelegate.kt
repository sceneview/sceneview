package io.github.sceneview.node

import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Transform
import io.github.sceneview.math.equals
import io.github.sceneview.math.quaternion
import io.github.sceneview.math.slerp
import io.github.sceneview.math.toColumnsFloatArray
import io.github.sceneview.math.toTransform
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

    // #2324: cache the smooth-transform target's decomposed TRS so the per-frame slerp does
    // not re-run the three `Mat4` decompositions on a target held stable for the whole
    // animation. See [SmoothTargetTRSCache].
    private val targetTRSCache = SmoothTargetTRSCache()

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
            // unchanged. (The single-read landed via #2280; this PR additionally feeds slerp
            // the cached components below.)
            val current = node.transform
            if (target != current) {
                // START operand: feed slerp the node's #2187-cached local TRS (Node.position /
                // quaternion / scale return the pristine `_position` / `_quaternion` / `_scale`
                // — no extra Mat4 decomposition). TARGET operand: feed slerp the #2324-cached
                // target TRS ([SmoothTargetTRSCache] decomposes `target` only when its value
                // changed, not every frame). Both sides are now decomposition-free while target is
                // stable, taking the per-frame matrix decompositions from 6 (pre-#2289) → 3
                // (#2289, only target) → 0. The TRS values are byte-identical to decomposing
                // `current`/`target` each frame, so the trajectory is unchanged.
                val (targetPosition, targetQuaternion, targetScale) = targetTRSCache.get(target)
                val (slerpPosition, slerpQuaternion, slerpScale) = slerp(
                    startPosition = node.position,
                    startQuaternion = node.quaternion,
                    startScale = node.scale,
                    endPosition = targetPosition,
                    endQuaternion = targetQuaternion,
                    endScale = targetScale,
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

/**
 * Caches a smooth-transform target's decomposed `(position, quaternion, scale)` so the
 * per-frame slerp does not re-run the three `Mat4` decompositions
 * (`target.position` / `target.quaternion` / `target.scale` — each a polar decomposition
 * plus column-length `sqrt`s) on a target that is held stable for the whole animation
 * (#2324). Before #2324 the slerp re-decomposed the target every frame, re-deriving the
 * same TRS for the animation's entire duration.
 *
 * The cache is keyed on the target's **value**, not its identity: [get] keeps a deep value
 * snapshot of the `Transform` it last decomposed and recomputes only when the supplied
 * target differs from that snapshot under structural `Mat4` equality (the same equality the
 * delegate already uses for its `target != current` convergence check — no decomposition,
 * no allocation on the hot path). Keying on value invalidates correctly on **both** mutation
 * paths: re-assigning `smoothTransform` to a different target, *and* mutating the same target
 * `Mat4` (or one of its `Float4` columns) in place. Returned TRS values are byte-identical to
 * decomposing the target each frame, so the interpolated trajectory is unchanged.
 *
 * Frame-thread confinement: the animation delegate's `onFrame` is the sole caller and runs
 * on the render/main thread, so the mutable cache fields need no synchronization.
 */
internal class SmoothTargetTRSCache {
    private var source: Transform? = null
    private var position: Position = Position()
    private var quaternion: Quaternion = Quaternion()
    private var scale: Scale = Scale(1.0f)

    /**
     * Returns [target]'s decomposed TRS, recomputing (and re-caching) it only when [target]
     * differs structurally from the value last decomposed.
     */
    fun get(target: Transform): Triple<Position, Quaternion, Scale> {
        if (target != source) {
            // Snapshot a deep *value* copy of the target (fresh columns, not shared
            // references): if the same `Mat4` instance — or one of its `Float4` columns — is
            // mutated in place after assignment, comparing the live target against a stored
            // reference (or a shallow copy that shares the column objects) would still be `==`
            // and miss the change. Round-tripping through the column floats yields an
            // independent snapshot, so the structural compare above detects any in-place
            // mutation and recomputes exactly once.
            source = target.toColumnsFloatArray().toTransform()
            position = target.position
            quaternion = target.quaternion
            scale = target.scale
        }
        return Triple(position, quaternion, scale)
    }
}
