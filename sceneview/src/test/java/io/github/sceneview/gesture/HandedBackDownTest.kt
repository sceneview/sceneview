package io.github.sceneview.gesture

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import dev.romainguy.kotlin.math.Float2
import io.github.sceneview.math.Transform
import io.github.sceneview.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * A drag handed back to the scene by a [io.github.sceneview.node.ViewNode] (#4033) reaches the
 * scene's [GestureDetector] through [GestureDetector.onHandedBackDown]: a `DOWN` replayed at the
 * current pointer, in the middle of a stream that is already a drag. Fed to the platform tap
 * detector as a plain `DOWN`, that replay turned a short drag into a tap, a press held before the
 * drag into an immediate long press, and a drag right after a tap into a double tap. These tests
 * pin all three, that the drag itself still scrolls, and that suppressing it does not cancel a
 * legitimate single-tap confirmation still pending from the preceding stream.
 */
@RunWith(RobolectricTestRunner::class)
class HandedBackDownTest {

    /** Records which scene gesture callbacks fired. */
    private class RecordingListener : GestureDetector.SimpleOnGestureListener() {
        val calls = mutableListOf<String>()

        override fun onSingleTapUp(e: MotionEvent, node: Node?) {
            calls += "singleTapUp"
        }

        override fun onSingleTapConfirmed(e: MotionEvent, node: Node?) {
            calls += "singleTapConfirmed"
        }

        override fun onLongPress(e: MotionEvent, node: Node?) {
            calls += "longPress"
        }

        override fun onDoubleTap(e: MotionEvent, node: Node?) {
            calls += "doubleTap"
        }

        override fun onDoubleTapEvent(e: MotionEvent, node: Node?) {
            calls += "doubleTapEvent"
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, node: Node?, distance: Float2) {
            calls += "scroll"
        }
    }

    /** Records whether the camera accepted the handed-back stream as an orbit. */
    private class RecordingManipulator : CameraGestureDetector.CameraManipulator {
        var orbitBegins = 0

        override fun setViewport(width: Int, height: Int) = Unit
        override fun getTransform() = Transform()
        override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
            if (!strafe) orbitBegins++
        }

        override fun grabUpdate(x: Int, y: Int) = Unit
        override fun grabEnd() = Unit
        override fun scrollBegin(x: Int, y: Int, separation: Float) = Unit
        override fun scrollUpdate(
            x: Int,
            y: Int,
            prevSeparation: Float,
            currSeparation: Float,
        ) = Unit
        override fun scrollEnd() = Unit
        override fun update(deltaTime: Float) = Unit
    }

    private val listener = RecordingListener()
    private val cameraDoubleTaps = mutableListOf<MotionEvent>()
    private val detector = GestureDetector(RuntimeEnvironment.getApplication(), listener).apply {
        onDoubleTapCamera = { cameraDoubleTaps += it }
    }

    private fun event(action: Int, x: Float, downTime: Long = SystemClock.uptimeMillis()) =
        MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, 100.0f, 0)

    private fun advance(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private val tapCallbacks get() = listener.calls.filter { it != "scroll" }

    @Test
    fun `control - a plain DOWN at the replay point would have reported a tap`() {
        // What the dispatcher did before: the replayed DOWN went through onTouchEvent.
        val downTime = SystemClock.uptimeMillis() - 50L
        detector.onTouchEvent(event(MotionEvent.ACTION_DOWN, 100.0f, downTime), null)
        detector.onTouchEvent(event(MotionEvent.ACTION_MOVE, 103.0f, downTime), null)
        detector.onTouchEvent(event(MotionEvent.ACTION_UP, 103.0f, downTime), null)
        advance(1_000L)

        assertTrue(listener.calls.toString(), "singleTapUp" in listener.calls)
    }

    @Test
    fun `a short drag handed back does not end in a tap`() {
        val downTime = SystemClock.uptimeMillis() - 50L
        detector.onHandedBackDown(event(MotionEvent.ACTION_DOWN, 100.0f, downTime), null)
        detector.onTouchEvent(event(MotionEvent.ACTION_MOVE, 103.0f, downTime), null)
        detector.onTouchEvent(event(MotionEvent.ACTION_UP, 103.0f, downTime), null)
        advance(1_000L)

        assertEquals(emptyList<String>(), tapCallbacks)
    }

    @Test
    fun `a press held before the drag does not fire a long press on hand back`() {
        // The finger rested on the card for a second, then dragged: the stream's down time is
        // already past the long-press timeout when the scene gets it.
        val downTime = SystemClock.uptimeMillis() - 1_000L
        detector.onHandedBackDown(event(MotionEvent.ACTION_DOWN, 100.0f, downTime), null)
        advance(1_000L)
        detector.onTouchEvent(event(MotionEvent.ACTION_MOVE, 102.0f, downTime), null)
        detector.onTouchEvent(event(MotionEvent.ACTION_UP, 102.0f, downTime), null)
        advance(1_000L)

        assertEquals(emptyList<String>(), tapCallbacks)
    }

    @Test
    fun `a ViewNode drag preserves the preceding scene tap and orbits instead of double tapping`() {
        val manipulator = RecordingManipulator()
        val cameraDetector = CameraGestureDetector({ 1_000 }, manipulator).apply {
            orbitTouchSlop = 10.0f
        }
        val tapDown = SystemClock.uptimeMillis()
        detector.onTouchEvent(event(MotionEvent.ACTION_DOWN, 100.0f, tapDown), null)
        cameraDetector.onTouchEvent(event(MotionEvent.ACTION_DOWN, 100.0f, tapDown))
        advance(40L)
        detector.onTouchEvent(event(MotionEvent.ACTION_UP, 100.0f, tapDown), null)
        cameraDetector.onTouchEvent(event(MotionEvent.ACTION_UP, 100.0f, tapDown))
        advance(60L)

        // This DOWN was consumed by the ViewNode. Once its MOVE crosses the touch slop, the node
        // hands the stream back and SceneView replays a DOWN at the current pointer to both scene
        // and camera detectors before dispatching that MOVE.
        val dragDown = SystemClock.uptimeMillis() - 20L
        val replayedDown = event(MotionEvent.ACTION_DOWN, 130.0f, dragDown)
        detector.onHandedBackDown(replayedDown, null)
        cameraDetector.onTouchEvent(replayedDown)
        listOf(130.0f, 160.0f, 190.0f).forEach { x ->
            val move = event(MotionEvent.ACTION_MOVE, x, dragDown)
            detector.onTouchEvent(move, null)
            cameraDetector.onTouchEvent(move)
        }
        val up = event(MotionEvent.ACTION_UP, 190.0f, dragDown)
        detector.onTouchEvent(up, null)
        cameraDetector.onTouchEvent(up)
        advance(1_000L)

        assertEquals(1, listener.calls.count { it == "singleTapUp" })
        assertEquals(1, listener.calls.count { it == "singleTapConfirmed" })
        assertEquals(0, listener.calls.count { it == "doubleTap" })
        assertEquals(0, listener.calls.count { it == "doubleTapEvent" })
        assertEquals(0, listener.calls.count { it == "longPress" })
        assertEquals("the camera must not zoom", 0, cameraDoubleTaps.size)
        assertEquals("the handed-back drag must orbit", 1, manipulator.orbitBegins)
    }

    @Test
    fun `the handed-back drag still reaches the scene as a scroll`() {
        val downTime = SystemClock.uptimeMillis() - 50L
        detector.onHandedBackDown(event(MotionEvent.ACTION_DOWN, 100.0f, downTime), null)
        detector.onTouchEvent(event(MotionEvent.ACTION_MOVE, 150.0f, downTime), null)
        detector.onTouchEvent(event(MotionEvent.ACTION_MOVE, 200.0f, downTime), null)
        detector.onTouchEvent(event(MotionEvent.ACTION_UP, 200.0f, downTime), null)
        advance(1_000L)

        assertTrue(listener.calls.toString(), "scroll" in listener.calls)
        assertEquals(emptyList<String>(), tapCallbacks)
    }
}
