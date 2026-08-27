package io.github.sceneview.node

import android.view.GestureDetector
import android.view.GestureDetector.OnContextClickListener
import android.view.GestureDetector.OnDoubleTapListener
import android.view.MotionEvent
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.degrees
import io.github.sceneview.collision.HitResult
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.gesture.NodeEditingKind
import io.github.sceneview.gesture.NodeEditingListener
import io.github.sceneview.gesture.RotateGestureDetector
import io.github.sceneview.gesture.ScaleGestureDetector
import io.github.sceneview.gesture.evaluateScaleEdit
import io.github.sceneview.gesture.quaternionYawDegrees
import io.github.sceneview.math.Position
import kotlin.reflect.KProperty1

/**
 * Handles all gesture detection and callback logic for a [Node].
 *
 * This delegate implements all Android gesture listener interfaces and manages the
 * per-node gesture callback lambdas. It is responsible for:
 * - Touch event forwarding via [onTouchEvent]
 * - Standard gesture detection (tap, double-tap, fling, long-press, scroll)
 * - Move/rotate/scale editing gestures with parent delegation
 * - Editing state tracking via [editingTransforms]
 *
 * @param node The owning [Node] whose transforms and collision system are used.
 */
class NodeGestureDelegate(
    private val node: Node
) : GestureDetector.OnGestureListener,
    OnDoubleTapListener,
    OnContextClickListener,
    MoveGestureDetector.OnMoveListener,
    RotateGestureDetector.OnRotateListener,
    ScaleGestureDetector.OnScaleListener {

    // ── Callback lambdas ──────────────────────────────────────────────────────────────────────────

    /** Called on raw touch events. Return `true` to consume the event. */
    var onTouch: ((e: MotionEvent, hitResult: HitResult) -> Boolean)? = null
    /** Called when a touch-down event is detected. */
    var onDown: ((e: MotionEvent) -> Boolean)? = null
    /** Called when a touch-down event has occurred but no tap or scroll yet. */
    var onShowPress: ((e: MotionEvent) -> Unit)? = null
    /** Called when a single tap up (finger lifted) is detected. */
    var onSingleTapUp: ((e: MotionEvent) -> Boolean)? = null
    /** Called during a scroll gesture with the scroll distance. */
    var onScroll: ((e1: MotionEvent?, e2: MotionEvent, distance: Float2) -> Boolean)? = null
    /** Called when a long-press gesture is detected. */
    var onLongPress: ((e: MotionEvent) -> Unit)? = null
    /** Called when a fling gesture is detected with the fling velocity. */
    var onFling: ((e1: MotionEvent?, e2: MotionEvent, velocity: Float2) -> Boolean)? = null
    /** Called when a single tap is confirmed (no second tap followed). */
    var onSingleTapConfirmed: ((e: MotionEvent) -> Boolean)? = null
    /** Called when a double-tap gesture is detected. */
    var onDoubleTap: ((e: MotionEvent) -> Boolean)? = null
    /** Called for events within a double-tap gesture (down, move, up). */
    var onDoubleTapEvent: ((e: MotionEvent) -> Boolean)? = null
    /** Called when a context click (e.g. mouse right-click) is detected. */
    var onContextClick: ((e: MotionEvent) -> Boolean)? = null
    /** Called when a move (drag) editing gesture begins. */
    var onMoveBegin: ((detector: MoveGestureDetector, e: MotionEvent) -> Boolean)? = null
    /** Called during a move editing gesture with the new world position. */
    var onMove: ((detector: MoveGestureDetector, e: MotionEvent, worldPosition: Position) -> Boolean)? =
        null
    /** Called when a move editing gesture ends. */
    var onMoveEnd: ((detector: MoveGestureDetector, e: MotionEvent) -> Unit)? = null
    /** Called when a rotation editing gesture begins. */
    var onRotateBegin: ((detector: RotateGestureDetector, e: MotionEvent) -> Boolean)? = null
    /** Called during a rotation editing gesture with the rotation delta. */
    var onRotate: ((detector: RotateGestureDetector, e: MotionEvent, rotationDelta: Quaternion) -> Boolean)? =
        null
    /** Called when a rotation editing gesture ends. */
    var onRotateEnd: ((detector: RotateGestureDetector, e: MotionEvent) -> Unit)? = null
    /** Called when a pinch-to-scale editing gesture begins. */
    var onScaleBegin: ((detector: ScaleGestureDetector, e: MotionEvent) -> Boolean)? = null
    /** Called during a pinch-to-scale editing gesture with the scale factor. */
    var onScale: ((detector: ScaleGestureDetector, e: MotionEvent, scaleFactor: Float) -> Boolean)? =
        null
    /** Called when a pinch-to-scale editing gesture ends. */
    var onScaleEnd: ((detector: ScaleGestureDetector, e: MotionEvent) -> Unit)? = null

    /** Called whenever the set of actively-edited transform properties changes. */
    var onEditingChanged: ((editingTransforms: Set<KProperty1<Node, Any>?>) -> Unit)? = null

    /**
     * Multi-consumer observers of editing gestures — see [NodeEditingListener].
     *
     * Unlike the single-slot lambdas above, any number of listeners can be attached
     * without clobbering each other or the app's own callbacks. Managed through
     * [Node.addEditingListener] / [Node.removeEditingListener]; iterated on a snapshot
     * copy so a listener may remove itself from its own callback.
     */
    internal val editingListeners = mutableListOf<NodeEditingListener>()

    private inline fun notifyEditingListeners(block: NodeEditingListener.() -> Unit) {
        if (editingListeners.isNotEmpty()) editingListeners.toList().forEach(block)
    }

    /**
     * Touch-down / touch-up on this node, dispatched by [io.github.sceneview.gesture.GestureDetector]
     * ahead of any gesture recognition — see [NodeEditingListener.onEditingPressed].
     */
    internal fun notifyEditingPressed() = notifyEditingListeners { onEditingPressed(node) }

    internal fun notifyEditingReleased() = notifyEditingListeners { onEditingReleased(node) }

    /** The set of [Node] transform properties currently being edited by a gesture. */
    var editingTransforms = setOf<KProperty1<Node, Any>>()
        set(value) {
            if (field != value) {
                field = value
                onEditingChanged?.invoke(value)
            }
        }

    // ---- Touch event ----

    fun onTouchEvent(e: MotionEvent, hitResult: HitResult) =
        onTouch?.invoke(e, hitResult) ?: false

    // ---- GestureDetector.OnGestureListener ----

    override fun onDown(e: MotionEvent) = onDown?.invoke(e) ?: false

    override fun onShowPress(e: MotionEvent) {
        onShowPress?.invoke(e)
    }

    override fun onSingleTapUp(e: MotionEvent) = onSingleTapUp?.invoke(e) ?: false

    override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
    ) = onScroll?.invoke(e1, e2, Float2(distanceX, distanceY)) ?: false

    override fun onLongPress(e: MotionEvent) {
        onLongPress?.invoke(e)
    }

    override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
    ) = onFling?.invoke(e1, e2, Float2(velocityX, velocityY)) ?: false

    // ---- OnDoubleTapListener ----

    override fun onSingleTapConfirmed(e: MotionEvent) = onSingleTapConfirmed?.invoke(e) ?: false
    override fun onDoubleTap(e: MotionEvent) = onDoubleTap?.invoke(e) ?: false
    override fun onDoubleTapEvent(e: MotionEvent) = onDoubleTapEvent?.invoke(e) ?: false

    // ---- OnContextClickListener ----

    override fun onContextClick(e: MotionEvent) = onContextClick?.invoke(e) ?: false

    // ---- MoveGestureDetector.OnMoveListener ----

    override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent): Boolean {
        return if (node.isPositionEditable && onMoveBegin?.invoke(detector, e) != false) {
            editingTransforms = editingTransforms + Node::position
            notifyEditingListeners { onEditingBegin(node, NodeEditingKind.Move) }
            true
        } else {
            // Delegate to parent via its virtual method to preserve polymorphic overrides
            node.parent?.onMoveBegin(detector, e) ?: false
        }
    }

    override fun onMove(detector: MoveGestureDetector, e: MotionEvent): Boolean {
        return if (node.isPositionEditable) {
            // Find the hit test location in the parent to place the child at the
            // corresponding location
            node.collisionSystem?.hitTest(e)?.firstOrNull { it.node == node.parent }?.let {
                onMove(detector, e, it.getWorldPosition())
            } ?: false
        } else {
            // Delegate to parent via its virtual method to preserve polymorphic overrides
            node.parent?.onMove(detector, e) ?: false
        }
    }

    fun onMove(
        detector: MoveGestureDetector,
        e: MotionEvent,
        worldPosition: Position
    ): Boolean {
        return if (onMove?.invoke(detector, e, worldPosition) != false) {
            node.worldPosition = worldPosition
            notifyEditingListeners { onPositionEdited(node, worldPosition) }
            true
        } else {
            false
        }
    }

    override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent) {
        if (node.isPositionEditable) {
            editingTransforms = editingTransforms - Node::position
            notifyEditingListeners { onEditingEnd(node, NodeEditingKind.Move) }
        } else {
            node.parent?.onMoveEnd(detector, e)
        }
    }

    // ---- RotateGestureDetector.OnRotateListener ----

    override fun onRotateBegin(detector: RotateGestureDetector, e: MotionEvent): Boolean {
        return if (node.isRotationEditable && onRotateBegin?.invoke(detector, e) != false) {
            editingTransforms = editingTransforms + Node::quaternion
            notifyEditingListeners { onEditingBegin(node, NodeEditingKind.Rotate) }
            true
        } else {
            node.parent?.onRotateBegin(detector, e) ?: false
        }
    }

    override fun onRotate(detector: RotateGestureDetector, e: MotionEvent): Boolean {
        return if (node.isRotationEditable) {
            val deltaRadians = detector.currentAngle - detector.lastAngle
            onRotate(
                detector, e,
                // Y_AXIS is a shared read-only constant: Quaternion.fromAxisAngle only reads it
                // (it returns a fresh Quaternion), so it is never retained or mutated — safe to
                // hoist out of this per-rotate-event path (#2328 SV9).
                rotationDelta = Quaternion.fromAxisAngle(Y_AXIS, degrees(-deltaRadians))
            )
        } else {
            node.parent?.onRotate(detector, e) ?: false
        }
    }

    fun onRotate(
        detector: RotateGestureDetector,
        e: MotionEvent,
        rotationDelta: Quaternion
    ): Boolean {
        return if (onRotate?.invoke(detector, e, rotationDelta) != false) {
            node.quaternion *= rotationDelta
            notifyEditingListeners {
                onRotationEdited(node, quaternionYawDegrees(rotationDelta))
            }
            true
        } else {
            false
        }
    }

    override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent) {
        if (node.isRotationEditable) {
            editingTransforms = editingTransforms - Node::quaternion
            notifyEditingListeners { onEditingEnd(node, NodeEditingKind.Rotate) }
        } else {
            node.parent?.onRotateEnd(detector, e)
        }
    }

    // ---- ScaleGestureDetector.OnScaleListener ----

    override fun onScaleBegin(detector: ScaleGestureDetector, e: MotionEvent): Boolean {
        return if (node.isScaleEditable && onScaleBegin?.invoke(detector, e) != false) {
            // Note: `editingTransforms` only gains Node::scale on the first onScale delta,
            // but feedback UIs want the badge up as soon as the pinch is recognized.
            notifyEditingListeners { onEditingBegin(node, NodeEditingKind.Scale) }
            true
        } else {
            node.parent?.onScaleBegin(detector, e) ?: false
        }
    }

    override fun onScale(detector: ScaleGestureDetector, e: MotionEvent): Boolean {
        return if (node.isScaleEditable) {
            editingTransforms = editingTransforms + Node::scale
            onScale(detector, e, detector.scaleFactor)
        } else {
            node.parent?.onScale(detector, e) ?: false
        }
    }

    fun onScale(detector: ScaleGestureDetector, e: MotionEvent, scaleFactor: Float): Boolean {
        return if (onScale?.invoke(detector, e, scaleFactor) != false) {
            val damped = 1f + (scaleFactor - 1f) * node.scaleGestureSensitivity
            val edit = evaluateScaleEdit(node.scale, damped, node.editableScaleRange)
            if (edit.applied) {
                node.scale = edit.scale
            }
            // Rejected updates are reported too: the range gate silently freezes the node
            // and feedback UIs need `edit.limit` to show why.
            notifyEditingListeners { onScaleEdited(node, edit) }
            edit.applied
        } else {
            false
        }
    }

    override fun onScaleEnd(detector: ScaleGestureDetector, e: MotionEvent) {
        if (node.isScaleEditable) {
            editingTransforms = editingTransforms - Node::scale
            notifyEditingListeners { onEditingEnd(node, NodeEditingKind.Scale) }
        } else {
            node.parent?.onScaleEnd(detector, e)
        }
    }

    private companion object {
        /**
         * The world-up axis used as the rotation axis for one-finger rotate gestures. Hoisted
         * to a shared read-only constant so [onRotate] no longer allocates a fresh `Float3` on
         * every rotate event (#2328 SV9). It is treated as read-only — [Quaternion.fromAxisAngle]
         * only reads the axis (it returns a fresh [Quaternion]) and no code path here mutates it,
         * so sharing one instance is safe.
         */
        private val Y_AXIS = Float3(y = 1.0f)
    }
}
