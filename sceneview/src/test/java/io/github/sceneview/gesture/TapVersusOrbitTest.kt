package io.github.sceneview.gesture

import android.view.MotionEvent
import io.github.sceneview.math.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A tap is not an orbit (#3641).
 *
 * The camera detector used to start an orbit on the third `ACTION_MOVE` of a one-finger stream,
 * whatever the distance. `adb shell input tap` emits no move at all, so every scripted check of
 * double-tap-to-zoom passed — while on a device, where a fingertip reports a few moves a pixel
 * apart during any tap, the second tap's own jitter called `grabBegin` and cancelled the zoom it
 * had just started. These tests drive the detector with the streams a finger really produces.
 */
@RunWith(RobolectricTestRunner::class)
class TapVersusOrbitTest {

    /** Records the manipulator calls the detector makes, in order. */
    private class RecordingManipulator : CameraGestureDetector.CameraManipulator {
        val calls = mutableListOf<String>()

        override fun setViewport(width: Int, height: Int) = Unit
        override fun getTransform(): Transform = Transform()
        override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
            calls += "grabBegin($x,$y,$strafe)"
        }

        override fun grabUpdate(x: Int, y: Int) {
            calls += "grabUpdate($x,$y)"
        }

        override fun grabEnd() {
            calls += "grabEnd"
        }

        override fun scrollBegin(x: Int, y: Int, separation: Float) {
            calls += "scrollBegin"
        }

        override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) {
            calls += "scrollUpdate"
        }

        override fun scrollEnd() {
            calls += "scrollEnd"
        }

        override fun update(deltaTime: Float) = Unit

        override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) {
            calls += "doubleTapZoom($x,$y,$zoomIn)"
        }
    }

    private val manipulator = RecordingManipulator()
    private val detector = CameraGestureDetector({ VIEW_HEIGHT }, manipulator).apply {
        orbitTouchSlop = SLOP
    }
    private var clock = 1_000L

    private fun event(action: Int, x: Float, y: Float): MotionEvent {
        clock += 8L
        return MotionEvent.obtain(clock, clock, action, x, y, 0)
    }

    /** One finger down, [moves] as `(dx, dy)` offsets from the touch point, finger up. */
    private fun touch(x: Float, y: Float, moves: List<Pair<Float, Float>>, doubleTap: Boolean = false) {
        val down = event(MotionEvent.ACTION_DOWN, x, y)
        detector.onTouchEvent(down)
        // `SceneView` feeds the platform GestureDetector first: it confirms the double-tap on the
        // second ACTION_DOWN, before that finger has produced a single move.
        if (doubleTap) detector.onDoubleTap(down)
        moves.forEach { (dx, dy) ->
            detector.onTouchEvent(event(MotionEvent.ACTION_MOVE, x + dx, y + dy))
        }
        detector.onTouchEvent(event(MotionEvent.ACTION_UP, x, y))
    }

    // ── The predicate ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a stream that has not travelled past the slop is not an orbit, however many moves`() {
        assertFalse(isOrbitDrag(moveCount = 3, travel = 2f, touchSlop = SLOP))
        assertFalse(isOrbitDrag(moveCount = 60, travel = SLOP - 0.5f, touchSlop = SLOP))
    }

    @Test
    fun `a stream past the slop still needs enough moves to have a direction`() {
        assertFalse(isOrbitDrag(moveCount = 1, travel = 200f, touchSlop = SLOP))
        assertFalse(isOrbitDrag(moveCount = 2, travel = 200f, touchSlop = SLOP))
        assertTrue(isOrbitDrag(moveCount = 3, travel = 200f, touchSlop = SLOP))
    }

    @Test
    fun `a zero slop leaves the decision to the move count alone`() {
        assertTrue(isOrbitDrag(moveCount = 3, travel = 0f, touchSlop = 0f))
        assertFalse(isOrbitDrag(moveCount = 2, travel = 0f, touchSlop = 0f))
    }

    // ── The detector, fed what a finger produces ─────────────────────────────────────────────────

    @Test
    fun `a jittery tap never grabs the camera`() {
        touch(540f, 1200f, FINGER_JITTER)

        assertEquals(
            "a tap must reach the manipulator as nothing but the closing grabEnd",
            listOf("grabEnd"),
            manipulator.calls,
        )
    }

    @Test
    fun `the second tap of a double-tap does not cancel its own zoom`() {
        touch(540f, 1200f, FINGER_JITTER)
        manipulator.calls.clear()

        touch(542f, 1198f, FINGER_JITTER, doubleTap = true)

        val zoom = manipulator.calls.indexOfFirst { it.startsWith("doubleTapZoom") }
        assertTrue("the double-tap must reach the manipulator: ${manipulator.calls}", zoom >= 0)
        assertTrue("it zooms IN: ${manipulator.calls}", manipulator.calls[zoom].endsWith("true)"))
        assertFalse(
            "grabBegin is every manipulator's 'the user took over' signal — after a double-tap " +
                "it cancels the animation that tap just started: ${manipulator.calls}",
            manipulator.calls.drop(zoom).any { it.startsWith("grabBegin") },
        )
    }

    @Test
    fun `a drag past the slop orbits, and grabs where the finger IS so the view cannot jump`() {
        val down = event(MotionEvent.ACTION_DOWN, 500f, 1000f)
        detector.onTouchEvent(down)
        // 6 px steps: the stream crosses the 20 px slop on the fifth move (x = 530, 24 px from
        // the first move at x = 506).
        (1..8).forEach { step ->
            detector.onTouchEvent(event(MotionEvent.ACTION_MOVE, 500f + 6f * step, 1000f))
        }
        detector.onTouchEvent(event(MotionEvent.ACTION_UP, 548f, 1000f))

        val flippedY = VIEW_HEIGHT - 1000
        assertEquals(
            listOf(
                "grabBegin(530,$flippedY,false)",
                "grabUpdate(536,$flippedY)",
                "grabUpdate(542,$flippedY)",
                "grabUpdate(548,$flippedY)",
                "grabEnd",
            ),
            manipulator.calls,
        )
    }

    @Test
    fun `a zero slop restores the orbit on the third move`() {
        detector.orbitTouchSlop = 0f

        touch(540f, 1200f, FINGER_JITTER)

        assertTrue(
            "with the guard off the third move begins the orbit: ${manipulator.calls}",
            manipulator.calls.first().startsWith("grabBegin"),
        )
    }

    @Test
    fun `the default slop is the platform's 8 dp, never zero`() {
        val fresh = CameraGestureDetector({ VIEW_HEIGHT }, manipulator)

        assertTrue(
            "a detector built without SceneView must still tell a tap from a drag",
            fresh.orbitTouchSlop >= 8f,
        )
    }

    private companion object {
        const val VIEW_HEIGHT = 2_000
        const val SLOP = 20f

        /** What a fingertip reports while it presses and lifts: five moves, within 3 px. */
        val FINGER_JITTER = listOf(1f to 0f, 1f to -1f, 2f to -1f, 2f to -2f, 3f to -2f)
    }
}
