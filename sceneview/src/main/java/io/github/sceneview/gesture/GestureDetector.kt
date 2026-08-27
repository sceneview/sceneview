package io.github.sceneview.gesture

import android.content.Context
import android.view.MotionEvent
import dev.romainguy.kotlin.math.Float2
import io.github.sceneview.collision.HitResult
import io.github.sceneview.node.Node

/**
 * Detects various gestures and events using the supplied {@link MotionEvent}s.
 *
 * The gesture listener callback will notify users when a particular motion event has occurred.
 * This class should only be used with [MotionEvent]s reported via touch (don't use for trackball
 * events).
 *
 * Responds to Android touch events with listeners.
 */
open class GestureDetector(context: Context, var listener: OnGestureListener?) {
    interface OnGestureListener {
        fun onDown(e: MotionEvent, node: Node?)
        fun onShowPress(e: MotionEvent, node: Node?)
        fun onSingleTapUp(e: MotionEvent, node: Node?)
        fun onScroll(e1: MotionEvent?, e2: MotionEvent, node: Node?, distance: Float2)
        fun onLongPress(e: MotionEvent, node: Node?)
        fun onFling(e1: MotionEvent?, e2: MotionEvent, node: Node?, velocity: Float2)
        fun onSingleTapConfirmed(e: MotionEvent, node: Node?)
        fun onDoubleTap(e: MotionEvent, node: Node?)
        fun onDoubleTapEvent(e: MotionEvent, node: Node?)
        fun onContextClick(e: MotionEvent, node: Node?)
        fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent, node: Node?)
        fun onMove(detector: MoveGestureDetector, e: MotionEvent, node: Node?)
        fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent, node: Node?)
        fun onRotateBegin(detector: RotateGestureDetector, e: MotionEvent, node: Node?)
        fun onRotate(detector: RotateGestureDetector, e: MotionEvent, node: Node?)
        fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent, node: Node?)
        fun onScaleBegin(detector: ScaleGestureDetector, e: MotionEvent, node: Node?)
        fun onScale(detector: ScaleGestureDetector, e: MotionEvent, node: Node?)
        fun onScaleEnd(detector: ScaleGestureDetector, e: MotionEvent, node: Node?)
    }

    open class SimpleOnGestureListener : OnGestureListener {
        override fun onDown(e: MotionEvent, node: Node?) {}
        override fun onShowPress(e: MotionEvent, node: Node?) {}
        override fun onSingleTapUp(e: MotionEvent, node: Node?) {}
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, node: Node?, distance: Float2) {}
        override fun onLongPress(e: MotionEvent, node: Node?) {}
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, node: Node?, velocity: Float2) {}
        override fun onSingleTapConfirmed(e: MotionEvent, node: Node?) {}
        override fun onDoubleTap(e: MotionEvent, node: Node?) {}
        override fun onDoubleTapEvent(e: MotionEvent, node: Node?) {}
        override fun onContextClick(e: MotionEvent, node: Node?) {}
        override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent, node: Node?) {}
        override fun onMove(detector: MoveGestureDetector, e: MotionEvent, node: Node?) {}
        override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent, node: Node?) {}
        override fun onRotateBegin(detector: RotateGestureDetector, e: MotionEvent, node: Node?) {}
        override fun onRotate(detector: RotateGestureDetector, e: MotionEvent, node: Node?) {}
        override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent, node: Node?) {}
        override fun onScaleBegin(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) {}
        override fun onScale(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) {}
        override fun onScaleEnd(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) {}
    }

    var touchedNode: Node? = null

    private var lastTouchEvent: MotionEvent? = null

    private val gestureDetector = android.view.GestureDetector(context,
        object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = super.onDown(e).also {
                touchedNode?.onDown(e)
                listener?.onDown(e, touchedNode)
            }

            override fun onShowPress(e: MotionEvent) = super.onShowPress(e).also {
                touchedNode?.onShowPress(e)
                listener?.onShowPress(e, touchedNode)
            }

            override fun onSingleTapUp(e: MotionEvent) = super.onSingleTapUp(e).also {
                touchedNode?.onSingleTapUp(e)
                listener?.onSingleTapUp(e, touchedNode)
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ) = super.onScroll(e1, e2, distanceX, distanceY).also {
                touchedNode?.onScroll(e1, e2, distanceX, distanceY)
                listener?.onScroll(e1, e2, touchedNode, Float2(distanceX, distanceY))
            }

            override fun onLongPress(e: MotionEvent) = super.onLongPress(e).also {
                touchedNode?.onLongPress(e)
                listener?.onLongPress(e, touchedNode)
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ) = super.onFling(e1, e2, velocityX, velocityY).also {
                touchedNode?.onFling(e1, e2, velocityX, velocityY)
                listener?.onFling(e1, e2, touchedNode, Float2(velocityX, velocityY))
            }

            override fun onSingleTapConfirmed(e: MotionEvent) = super.onSingleTapConfirmed(e).also {
                touchedNode?.onSingleTapConfirmed(e)
                listener?.onSingleTapConfirmed(e, touchedNode)
            }

            override fun onDoubleTap(e: MotionEvent) = super.onDoubleTap(e).also {
                touchedNode?.onDoubleTap(e)
                listener?.onDoubleTap(e, touchedNode)
            }

            override fun onDoubleTapEvent(e: MotionEvent) = super.onDoubleTapEvent(e).also {
                touchedNode?.onDoubleTapEvent(e)
                listener?.onDoubleTapEvent(e, touchedNode)
            }

            override fun onContextClick(e: MotionEvent) = super.onContextClick(e).also {
                touchedNode?.onContextClick(e)
                listener?.onContextClick(e, touchedNode)
            }
        }
    )

    private val moveGestureDetector = MoveGestureDetector(context,
        object : MoveGestureDetector.SimpleOnMoveListener {
            // Pin the whole gesture to the node it began on. The node and an explicit
            // in-progress flag are stored separately (not `e to touchedNode`): dispatch must
            // keep firing at the listener when the gesture began on empty space (node == null),
            // and retaining the begin MotionEvent is both useless and unsafe — the framework
            // recycles it. The old code destructured the stored begin Pair with a binding named
            // `e`, SHADOWING the live `e` parameter — every onMove delivered the finger-DOWN
            // event, so AR drag re-hit-tested the same start pixel and nodes never moved (#2629).
            var moveBeginNode: Node? = null
            var moveInProgress = false

            override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent) =
                super.onMoveBegin(detector, e).also {
                    moveBeginNode = touchedNode
                    moveInProgress = true
                    touchedNode?.onMoveBegin(detector, e)
                    listener?.onMoveBegin(detector, e, touchedNode)
                }

            override fun onMove(detector: MoveGestureDetector, e: MotionEvent) =
                super.onMove(detector, e).also {
                    if (moveInProgress) {
                        moveBeginNode?.onMove(detector, e)
                        listener?.onMove(detector, e, moveBeginNode)
                    }
                }

            override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent) =
                super.onMoveEnd(detector, e).also {
                    if (moveInProgress) {
                        moveBeginNode?.onMoveEnd(detector, e)
                        listener?.onMoveEnd(detector, e, moveBeginNode)
                    }
                    moveBeginNode = null
                    moveInProgress = false
                }
        }
    )

    private val rotateGestureDetector = RotateGestureDetector(context,
        object : RotateGestureDetector.SimpleOnRotateListener {
            // Same live-event contract as the move listener above (#2629).
            var rotateBeginNode: Node? = null
            var rotateInProgress = false

            override fun onRotateBegin(detector: RotateGestureDetector, e: MotionEvent) =
                super.onRotateBegin(detector, e).also {
                    rotateBeginNode = touchedNode
                    rotateInProgress = true
                    touchedNode?.onRotateBegin(detector, e)
                    listener?.onRotateBegin(detector, e, touchedNode)
                }

            override fun onRotate(detector: RotateGestureDetector, e: MotionEvent) =
                super.onRotate(detector, e).also {
                    if (rotateInProgress) {
                        rotateBeginNode?.onRotate(detector, e)
                        listener?.onRotate(detector, e, rotateBeginNode)
                    }
                }

            override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent) {
                if (rotateInProgress) {
                    rotateBeginNode?.onRotateEnd(detector, e)
                    listener?.onRotateEnd(detector, e, rotateBeginNode)
                }
                rotateBeginNode = null
                rotateInProgress = false
            }
        })

    private val scaleGestureDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleListener {
            // Same live-event contract as the move listener above (#2629).
            var scaleBeginNode: Node? = null
            var scaleInProgress = false

            override fun onScaleBegin(detector: ScaleGestureDetector, e: MotionEvent) =
                super.onScaleBegin(detector, e).also {
                    scaleBeginNode = touchedNode
                    scaleInProgress = true
                    touchedNode?.onScaleBegin(detector, e)
                    listener?.onScaleBegin(detector, e, touchedNode)
                }

            override fun onScale(detector: ScaleGestureDetector, e: MotionEvent) =
                super.onScale(detector, e).also {
                    if (scaleInProgress) {
                        scaleBeginNode?.onScale(detector, e)
                        listener?.onScale(detector, e, scaleBeginNode)
                    }
                }

            override fun onScaleEnd(detector: ScaleGestureDetector, e: MotionEvent) {
                if (scaleInProgress) {
                    scaleBeginNode?.onScaleEnd(detector, e)
                    listener?.onScaleEnd(detector, e, scaleBeginNode)
                }
                scaleBeginNode = null
                scaleInProgress = false
            }
        }
    )

    /**
     * The editable node currently held down, so its press can be released on UP/CANCEL
     * even when the finger has since travelled off it.
     */
    private var pressedNode: Node? = null

    fun onTouchEvent(event: MotionEvent, hitResult: HitResult?) {
        lastTouchEvent = event
        touchedNode = hitResult?.node

        // Press/release is dispatched ahead of gesture recognition: the sub-detectors only
        // report a Begin once their threshold is crossed, which is far too late for
        // on-model feedback to acknowledge the touch (#3357).
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Editing gestures bubble to the first editable ancestor (see
                // NodeGestureDelegate's parent delegation), so the press does too.
                val node = generateSequence(hitResult?.node) { it.parent }
                    .firstOrNull { it.isEditable }
                if (node !== pressedNode) {
                    pressedNode?.gestureDelegate?.notifyEditingReleased()
                    pressedNode = node
                    node?.gestureDelegate?.notifyEditingPressed()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressedNode?.gestureDelegate?.notifyEditingReleased()
                pressedNode = null
            }
        }

        gestureDetector.onTouchEvent(event)
        moveGestureDetector.onTouchEvent(event)
        rotateGestureDetector.onTouchEvent(event)
        scaleGestureDetector.onTouchEvent(event)
    }
}