package io.github.sceneview.ar

import android.util.Log
import android.view.MotionEvent
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.NotTrackingException
import com.google.ar.core.exceptions.SessionPausedException
import dev.romainguy.kotlin.math.Float2
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.gesture.MoveGestureDetector
import io.github.sceneview.gesture.RotateGestureDetector
import io.github.sceneview.gesture.ScaleGestureDetector
import io.github.sceneview.node.Node

private const val TAG = "ARCore"

/**
 * `true` for the ARCore errors a gesture callback hits when it touches the session after it
 * stopped: [SessionPausedException] (the session paused between the touch and the callback),
 * [NotTrackingException] (tracking was lost in that window) and [FatalException] (ARCore failed
 * the call, seen in the field on the same tap-after-pause path).
 */
internal fun isStaleArSessionError(error: Throwable): Boolean =
    error is SessionPausedException || error is NotTrackingException || error is FatalException

/**
 * Runs [block], a gesture callback that may call into ARCore. A stale-session error
 * ([isStaleArSessionError]) is logged and dropped, so the gesture is lost instead of crashing the
 * app; anything else is rethrown untouched.
 */
internal inline fun <T> arSessionGuarded(callback: String, fallback: T, block: () -> T): T =
    try {
        block()
    } catch (error: RuntimeException) {
        if (!isStaleArSessionError(error)) throw error
        Log.w(TAG, "$callback dropped: the ARCore session is no longer running", error)
        fallback
    }

/**
 * Wraps this listener so an ARCore stale-session error thrown by any of its callbacks is logged
 * instead of crashing the app.
 *
 * A single tap is confirmed by a `Handler` message after the double-tap timeout, so it can run
 * after the session paused. A listener doing `frame.hitTest(...).createAnchor()` then throws
 * `SessionPausedException` from the main looper, out of reach of any `try` in the app. Wrapping
 * twice keeps one guard.
 */
internal fun GestureDetector.OnGestureListener.arSessionSafe(): GestureDetector.OnGestureListener =
    this as? ARSessionSafeGestureListener ?: ARSessionSafeGestureListener(this)

internal class ARSessionSafeGestureListener(
    private val delegate: GestureDetector.OnGestureListener,
) : GestureDetector.OnGestureListener {

    private inline fun guard(callback: String, block: () -> Unit) =
        arSessionGuarded(callback, Unit, block)

    override fun onDown(e: MotionEvent, node: Node?) =
        guard("onDown") { delegate.onDown(e, node) }

    override fun onShowPress(e: MotionEvent, node: Node?) =
        guard("onShowPress") { delegate.onShowPress(e, node) }

    override fun onSingleTapUp(e: MotionEvent, node: Node?) =
        guard("onSingleTapUp") { delegate.onSingleTapUp(e, node) }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, node: Node?, distance: Float2) =
        guard("onScroll") { delegate.onScroll(e1, e2, node, distance) }

    override fun onLongPress(e: MotionEvent, node: Node?) =
        guard("onLongPress") { delegate.onLongPress(e, node) }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, node: Node?, velocity: Float2) =
        guard("onFling") { delegate.onFling(e1, e2, node, velocity) }

    override fun onSingleTapConfirmed(e: MotionEvent, node: Node?) =
        guard("onSingleTapConfirmed") { delegate.onSingleTapConfirmed(e, node) }

    override fun onDoubleTap(e: MotionEvent, node: Node?) =
        guard("onDoubleTap") { delegate.onDoubleTap(e, node) }

    override fun onDoubleTapEvent(e: MotionEvent, node: Node?) =
        guard("onDoubleTapEvent") { delegate.onDoubleTapEvent(e, node) }

    override fun onContextClick(e: MotionEvent, node: Node?) =
        guard("onContextClick") { delegate.onContextClick(e, node) }

    override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent, node: Node?) =
        guard("onMoveBegin") { delegate.onMoveBegin(detector, e, node) }

    override fun onMove(detector: MoveGestureDetector, e: MotionEvent, node: Node?) =
        guard("onMove") { delegate.onMove(detector, e, node) }

    override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent, node: Node?) =
        guard("onMoveEnd") { delegate.onMoveEnd(detector, e, node) }

    override fun onRotateBegin(detector: RotateGestureDetector, e: MotionEvent, node: Node?) =
        guard("onRotateBegin") { delegate.onRotateBegin(detector, e, node) }

    override fun onRotate(detector: RotateGestureDetector, e: MotionEvent, node: Node?) =
        guard("onRotate") { delegate.onRotate(detector, e, node) }

    override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent, node: Node?) =
        guard("onRotateEnd") { delegate.onRotateEnd(detector, e, node) }

    override fun onScaleBegin(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) =
        guard("onScaleBegin") { delegate.onScaleBegin(detector, e, node) }

    override fun onScale(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) =
        guard("onScale") { delegate.onScale(detector, e, node) }

    override fun onScaleEnd(detector: ScaleGestureDetector, e: MotionEvent, node: Node?) =
        guard("onScaleEnd") { delegate.onScaleEnd(detector, e, node) }
}
