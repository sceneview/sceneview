package io.github.sceneview.gesture

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node

/**
 * Observable Compose state describing the editing gesture currently applied to a [Node].
 *
 * Create with [rememberNodeEditingFeedback] — it registers a [NodeEditingListener] on the
 * node and mirrors the gesture into snapshot state, so any composable reading these
 * properties recomposes live during the gesture. Feed it to
 * [io.github.sceneview.gesture.NodeEditingOverlay] for the ready-made on-model visuals,
 * or read the properties directly to build custom feedback.
 *
 * All values are updated on the main thread by the gesture pipeline.
 *
 * @property node The observed node.
 * @property scaleBaseline The local scale (per-axis uniform, X component) that reads as
 * "100 %". Captured when the state is created — for a `ModelNode(scaleToUnits = …)` this
 * is the as-placed scale, so the badge shows the size relative to initial placement, not
 * the raw local scale.
 */
@Stable
class NodeEditingFeedbackState(
    val node: Node,
    scaleBaseline: Float = node.scale.x,
) : NodeEditingListener {

    /** The gestures currently active on [node] — twist and pinch can run simultaneously. */
    var activeKinds by mutableStateOf(emptySet<NodeEditingKind>())
        private set

    /**
     * `true` from the moment a finger goes down on [node] until it lifts — including the
     * window before any gesture is recognized. Drive the "armed" visual with this, not
     * with [isEditing], or the feedback only appears once the model is already moving.
     */
    var isPressed by mutableStateOf(false)
        private set

    /** `true` while any editing gesture is active on [node]. */
    val isEditing: Boolean get() = activeKinds.isNotEmpty()

    /** Pressed, but the gesture is still undecided — the "armed, no mode yet" state. */
    val isArmed: Boolean get() = isPressed && activeKinds.isEmpty()

    /**
     * Heading of the node around the world-up axis in degrees, `(-180°, 180°]`.
     *
     * Derived from the quaternion with an `atan2`-based extraction that covers the full
     * turn — deliberately **not** `node.rotation.y`, whose Euler decomposition saturates
     * at ±90°.
     */
    var yawDegrees by mutableFloatStateOf(quaternionYawDegrees(node.quaternion))
        private set

    /** Signed yaw accumulated since the current rotate gesture began, in degrees. */
    var rotationDeltaDegrees by mutableFloatStateOf(0f)
        private set

    /**
     * See [NodeEditingFeedbackState] — the scale that reads as `100 %`.
     *
     * A non-positive baseline is ignored by [scalePercent] rather than producing `NaN`.
     */
    var scaleBaseline by mutableFloatStateOf(scaleBaseline)

    /** Current scale of the node as a percentage of [scaleBaseline]. */
    var scalePercent by mutableFloatStateOf(percentOf(node.scale.x, scaleBaseline))
        private set

    /**
     * The [Node.editableScaleRange] bound the pinch is currently pressing against, or
     * `null` while scaling freely. Non-null exactly while updates are being rejected —
     * the moment the user pinches back inside the range it clears.
     */
    var scaleLimit by mutableStateOf<NodeScaleLimit?>(null)
        private set

    /**
     * Incremented on every pinch update rejected by [Node.editableScaleRange]. Key a
     * bounce/shake animation on it to re-trigger while the user keeps pinching outward.
     */
    var scaleLimitHits by mutableIntStateOf(0)
        private set

    /**
     * `true` when the pinch is currently growing the node, `false` when shrinking.
     * Drives the direction of the scale affordance arrows.
     */
    var isGrowing by mutableStateOf(true)
        private set

    /** World position of the last applied drag update, `null` outside a move gesture. */
    var moveWorldPosition by mutableStateOf<Position?>(null)
        private set

    override fun onEditingPressed(node: Node) {
        isPressed = true
    }

    override fun onEditingReleased(node: Node) {
        isPressed = false
    }

    override fun onEditingBegin(node: Node, kind: NodeEditingKind) {
        activeKinds = activeKinds + kind
        when (kind) {
            NodeEditingKind.Rotate -> {
                rotationDeltaDegrees = 0f
                yawDegrees = quaternionYawDegrees(node.quaternion)
            }
            NodeEditingKind.Scale -> {
                scalePercent = percentOf(node.scale.x, scaleBaseline)
                scaleLimit = null
            }
            NodeEditingKind.Move -> moveWorldPosition = node.worldPosition
        }
    }

    override fun onEditingEnd(node: Node, kind: NodeEditingKind) {
        activeKinds = activeKinds - kind
        when (kind) {
            NodeEditingKind.Scale -> scaleLimit = null
            NodeEditingKind.Move -> moveWorldPosition = null
            NodeEditingKind.Rotate -> Unit
        }
    }

    override fun onPositionEdited(node: Node, worldPosition: Position) {
        moveWorldPosition = worldPosition
    }

    override fun onRotationEdited(node: Node, yawDeltaDegrees: Float) {
        rotationDeltaDegrees += yawDeltaDegrees
        yawDegrees = quaternionYawDegrees(node.quaternion)
    }

    override fun onScaleEdited(node: Node, edit: NodeScaleEdit) {
        // Ignore the dead zone: a factor of exactly 1 carries no direction.
        if (edit.factor != 1f) isGrowing = edit.factor > 1f
        if (edit.applied) {
            scalePercent = percentOf(edit.scale.x, scaleBaseline)
            scaleLimit = null
        } else {
            scaleLimit = edit.limit
            scaleLimitHits++
        }
    }

    private companion object {
        /** `100 %` when the baseline is not a usable divisor (a node placed at scale 0). */
        fun percentOf(scale: Float, baseline: Float) =
            if (baseline > 0f) scale / baseline * 100f else 100f
    }
}

/**
 * Remembers a [NodeEditingFeedbackState] attached to [node].
 *
 * Opt-in gesture feedback, step 1 of 2 — pair it with
 * [io.github.sceneview.gesture.NodeEditingOverlay] drawn over the scene:
 *
 * ```kotlin
 * val engine = rememberEngine()
 * val view = rememberView(engine)
 * val feedback = modelNode?.let { rememberNodeEditingFeedback(it) }
 * Box {
 *     SceneView(engine = engine, view = view, …)
 *     feedback?.let {
 *         NodeEditingOverlay(state = it, view = view, modifier = Modifier.matchParentSize())
 *     }
 * }
 * ```
 *
 * The listener is registered while the composable is in composition and removed on
 * disposal or when [node] changes.
 */
@Composable
fun rememberNodeEditingFeedback(node: Node): NodeEditingFeedbackState {
    val state = remember(node) { NodeEditingFeedbackState(node) }
    DisposableEffect(node) {
        node.addEditingListener(state)
        onDispose { node.removeEditingListener(state) }
    }
    return state
}
