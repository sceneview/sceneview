package io.github.sceneview.gesture

import android.view.MotionEvent
import io.github.sceneview.ViewportPadding
import io.github.sceneview.clippedViewport
import io.github.sceneview.math.Transform
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CameraGestureViewportTest {
    @Test
    fun doubleTapUsesViewportPixelsAndLeavesTheOriginalEventUntouched() {
        val manipulator = RecordingManipulator()
        val detector = CameraGestureDetector({ 800 }, manipulator)
        val viewport = clippedViewport(1000, 800, ViewportPadding(100f, 50f, 200f, 150f))
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 450f, 350f, 0)
        try {
            detector.onDoubleTap(event)
            assertEquals(450 to 450, manipulator.zoom)
            detector.viewportCoordinates = { viewport }
            detector.onDoubleTap(event)
            assertEquals(350 to 300, manipulator.zoom)
            assertEquals(450f, event.x, 0f)
            assertEquals(350f, event.y, 0f)
            detector.viewportCoordinates = { null }
            detector.onDoubleTap(event)
            assertEquals(450 to 450, manipulator.zoom)
        } finally {
            event.recycle()
        }
    }

    @Test
    fun orbitBeginAndUpdateSubtractTheViewportOrigin() {
        val manipulator = RecordingManipulator()
        val detector = CameraGestureDetector({ 800 }, manipulator).apply {
            viewportCoordinates = { clippedViewport(1000, 800, ViewportPadding(100f, 50f, 200f, 150f)) }
            orbitTouchSlop = 1f
        }
        var time = 1000L
        fun send(action: Int, x: Float) {
            time += 16
            MotionEvent.obtain(1000, time, action, x, 350f, 0).also {
                detector.onTouchEvent(it)
                it.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 450f)
        send(MotionEvent.ACTION_MOVE, 452f)
        send(MotionEvent.ACTION_MOVE, 454f)
        send(MotionEvent.ACTION_MOVE, 456f)
        assertEquals(356 to 300, manipulator.begin)
        send(MotionEvent.ACTION_MOVE, 460f)
        assertEquals(360 to 300, manipulator.update)
        send(MotionEvent.ACTION_CANCEL, 460f)
    }

    private class RecordingManipulator : CameraGestureDetector.CameraManipulator {
        var zoom: Pair<Int, Int>? = null
        var begin: Pair<Int, Int>? = null
        var update: Pair<Int, Int>? = null
        override fun setViewport(width: Int, height: Int) = Unit
        override fun getTransform(): Transform = Transform()
        override fun grabBegin(x: Int, y: Int, strafe: Boolean) { begin = x to y }
        override fun grabUpdate(x: Int, y: Int) { update = x to y }
        override fun grabEnd() = Unit
        override fun scrollBegin(x: Int, y: Int, separation: Float) = Unit
        override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) = Unit
        override fun scrollEnd() = Unit
        override fun update(deltaTime: Float) = Unit
        override fun doubleTapZoom(x: Int, y: Int, zoomIn: Boolean) { zoom = x to y }
    }
}
