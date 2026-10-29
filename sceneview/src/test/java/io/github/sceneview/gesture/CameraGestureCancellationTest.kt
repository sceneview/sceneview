package io.github.sceneview.gesture

import android.view.MotionEvent
import io.github.sceneview.math.Transform
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CameraGestureCancellationTest {

    @Test
    fun `a cancelled camera gesture ends callbacks then stops inertia`() {
        val manipulator = RecordingManipulator()
        val detector = CameraGestureDetector({ VIEW_HEIGHT }, manipulator).apply {
            orbitTouchSlop = 1f
        }
        var clock = 1_000L

        fun send(action: Int, x: Float) {
            clock += 16L
            MotionEvent.obtain(clock, clock, action, x, 500f, 0).also {
                detector.onTouchEvent(it)
                it.recycle()
            }
        }

        send(MotionEvent.ACTION_DOWN, 100f)
        manipulator.calls.clear()
        send(MotionEvent.ACTION_MOVE, 102f)
        send(MotionEvent.ACTION_MOVE, 104f)
        send(MotionEvent.ACTION_MOVE, 106f)
        send(MotionEvent.ACTION_CANCEL, 106f)

        assertEquals(listOf("grabBegin", "grabEnd", "stopInertia"), manipulator.calls)
    }

    private class RecordingManipulator : CameraGestureDetector.CameraManipulator {
        val calls = mutableListOf<String>()

        override fun setViewport(width: Int, height: Int) = Unit
        override fun getTransform(): Transform = Transform()
        override fun grabBegin(x: Int, y: Int, strafe: Boolean) { calls += "grabBegin" }
        override fun grabUpdate(x: Int, y: Int) = Unit
        override fun grabEnd() { calls += "grabEnd" }
        override fun scrollBegin(x: Int, y: Int, separation: Float) = Unit
        override fun scrollUpdate(
            x: Int,
            y: Int,
            prevSeparation: Float,
            currSeparation: Float,
        ) = Unit
        override fun scrollEnd() = Unit
        override fun update(deltaTime: Float) = Unit
        override fun stopInertia() { calls += "stopInertia" }
    }

    private companion object {
        const val VIEW_HEIGHT = 1_000
    }
}
