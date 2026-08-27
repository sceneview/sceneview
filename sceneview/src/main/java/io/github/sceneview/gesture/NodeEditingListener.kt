package io.github.sceneview.gesture

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.Node
import kotlin.math.atan2

/**
 * The transform an editing gesture is acting on.
 *
 * Mirrors the three editing gestures of [io.github.sceneview.node.NodeGestureDelegate]:
 * one-finger drag ([Move]), two-finger twist ([Rotate]) and pinch ([Scale]).
 */
enum class NodeEditingKind { Move, Rotate, Scale }

/** Which bound of [Node.editableScaleRange] a rejected pinch update ran into. */
enum class NodeScaleLimit { Min, Max }

/**
 * Outcome of one pinch-to-scale update.
 *
 * [Node.editableScaleRange] is an **absolute local-scale window** (default `0.1f..10.0f`),
 * not a factor relative to the initial scale — and the gesture handler drops the whole
 * update when any axis would leave it. [applied] is `false` for such a dropped update and
 * [limit] names the bound that was hit, so feedback UIs can show *why* the node stopped
 * responding instead of appearing frozen.
 *
 * @param factor The damped scale factor that was applied (or attempted) this update.
 * @param scale  The resulting local scale when [applied], otherwise the rejected scale.
 * @param applied Whether the node's scale was actually changed.
 * @param limit  The [Node.editableScaleRange] bound that rejected the update, or `null`
 *               when [applied].
 */
data class NodeScaleEdit(
    val factor: Float,
    val scale: Scale,
    val applied: Boolean,
    val limit: NodeScaleLimit?
)

/**
 * Observes editing gestures applied to a [Node] without taking over its single-consumer
 * `onMove`/`onRotate`/`onScale` callback slots.
 *
 * Register with [Node.addEditingListener] and remove with [Node.removeEditingListener].
 * Any number of listeners can be attached; the per-node callback lambdas keep their
 * veto power (a lambda returning `false` cancels the update and listeners are not
 * notified of a change that never happened — except [onScaleEdited], which also reports
 * updates rejected by [Node.editableScaleRange] so UIs can surface the clamp).
 *
 * All methods are invoked on the main thread and have empty default implementations.
 *
 * This is the low-level hook behind the Compose feedback API — see
 * `rememberNodeEditingFeedback` and `NodeEditingOverlay`.
 */
interface NodeEditingListener {

    /**
     * A finger went down on [node] and it is editable — no gesture is identified yet.
     *
     * The move / rotate / scale detectors only fire their `Begin` callback once the touch
     * passes a recognition threshold (a real translation, ~2° of twist, a pinch span
     * change), which is several tens of milliseconds of finger movement later. Feedback
     * that waits for that reads as unresponsive on first contact, so this is the signal
     * to show the "armed" visual — the node is grabbed, the gesture is still undecided.
     */
    fun onEditingPressed(node: Node) {}

    /** The finger that pressed [node] lifted or the stream was cancelled. */
    fun onEditingReleased(node: Node) {}

    /** An editing gesture of the given [kind] started on [node]. */
    fun onEditingBegin(node: Node, kind: NodeEditingKind) {}

    /** The editing gesture of the given [kind] on [node] ended. */
    fun onEditingEnd(node: Node, kind: NodeEditingKind) {}

    /** [node] was dragged to a new [worldPosition]. */
    fun onPositionEdited(node: Node, worldPosition: Position) {}

    /**
     * [node] was rotated around the world-up axis by [yawDeltaDegrees] (signed).
     *
     * Accumulate this delta for a live angle readout — never read `node.rotation.y`
     * per frame: the Euler decomposition saturates at ±90° on the Y axis.
     */
    fun onRotationEdited(node: Node, yawDeltaDegrees: Float) {}

    /** A pinch update was applied to (or rejected from) [node] — see [NodeScaleEdit]. */
    fun onScaleEdited(node: Node, edit: NodeScaleEdit) {}
}

/**
 * Evaluates one pinch update against [range] the exact way the gesture handler does:
 * all three axes of `currentScale * dampedFactor` must stay inside the **absolute**
 * range or the whole update is rejected.
 */
internal fun evaluateScaleEdit(
    currentScale: Float3,
    dampedFactor: Float,
    range: ClosedFloatingPointRange<Float>
): NodeScaleEdit {
    val newScale = currentScale * dampedFactor
    val applied = newScale.x in range && newScale.y in range && newScale.z in range
    // Read the bound off the rejected scale, not off the pinch direction: a node whose
    // scale already sits outside the window (`scaleToUnits` on a model authored in
    // hundreds of units lands far under the 0.1f default floor) is pinned against the
    // SAME bound whichever way the user pinches, and the direction would name the
    // opposite one.
    val belowFloor = newScale.x < range.start || newScale.y < range.start ||
        newScale.z < range.start
    val limit = when {
        applied -> null
        belowFloor -> NodeScaleLimit.Min
        else -> NodeScaleLimit.Max
    }
    return NodeScaleEdit(factor = dampedFactor, scale = newScale, applied = applied, limit = limit)
}

/**
 * Heading of [quaternion] around the world-up axis, in degrees within `(-180°, 180°]`.
 *
 * Computed from the rotated forward vector with `atan2`, so it covers the full turn —
 * unlike the Euler `rotation.y` decomposition, whose middle-axis `asin` saturates at
 * ±90° (with X/Z flipping past it).
 */
fun quaternionYawDegrees(quaternion: Quaternion): Float {
    val x = quaternion.x
    val y = quaternion.y
    val z = quaternion.z
    val w = quaternion.w
    // (0,0,1) rotated by the quaternion, projected on the XZ plane.
    val dirX = 2f * (x * z + w * y)
    val dirZ = 1f - 2f * (x * x + y * y)
    return Math.toDegrees(atan2(dirX.toDouble(), dirZ.toDouble())).toFloat()
}
