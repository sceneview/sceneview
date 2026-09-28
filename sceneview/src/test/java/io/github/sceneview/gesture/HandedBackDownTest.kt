package io.github.sceneview.gesture

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import dev.romainguy.kotlin.math.Float2
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
 * pin all three, and that the drag itself still scrolls.
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

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, node: Node?, distance: Float2) {
            calls += "scroll"
        }
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
    fun `a drag handed back right after a tap is not a double tap`() {
        val tapDown = SystemClock.uptimeMillis()
        detector.onTouchEvent(event(MotionEvent.ACTION_DOWN, 100.0f, tapDown), null)
        advance(40L)
        detector.onTouchEvent(event(MotionEvent.ACTION_UP, 100.0f, tapDown), null)
        advance(60L)
        listener.calls.clear()

        val dragDown = SystemClock.uptimeMillis() - 20L
        detector.onHandedBackDown(event(MotionEvent.ACTION_DOWN, 102.0f, dragDown), null)
        detector.onTouchEvent(event(MotionEvent.ACTION_UP, 102.0f, dragDown), null)
        advance(1_000L)

        assertEquals(emptyList<String>(), tapCallbacks)
        assertEquals("the camera must not zoom", 0, cameraDoubleTaps.size)
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
