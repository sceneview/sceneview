package io.github.sceneview.demo.ui.viewer

import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.lookAt
import io.github.sceneview.gesture.CameraGestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.math.Transform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ViewerOpeningMoveTest {

    private val target = Position(0f, 0.2f, 0f)
    private val rest = Position(0f, 0.6f, 2f)

    private fun distance(a: Position, b: Position): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val dz = a.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun assertSame(expected: Position, actual: Position, tolerance: Float = 1e-4f) {
        assertEquals(expected.x, actual.x, tolerance)
        assertEquals(expected.y, actual.y, tolerance)
        assertEquals(expected.z, actual.z, tolerance)
    }

    @Test
    fun `the move ends exactly on the resting framing`() {
        assertSame(rest, ViewerOpeningMove.eye(rest, target, 1f), 0f)
        assertSame(rest, ViewerOpeningMove.eye(rest, target, Float.NaN), 0f)
    }

    @Test
    fun `the move starts wider and higher`() {
        val start = ViewerOpeningMove.eye(rest, target, 0f)
        val restDistance = distance(rest, target)
        assertTrue(distance(start, target) > restDistance * 1.5f)
        assertTrue(start.y > rest.y)
    }

    @Test
    fun `the camera closes in monotonically`() {
        var previous = Float.MAX_VALUE
        for (i in 0..20) {
            val d = distance(ViewerOpeningMove.eye(rest, target, i / 20f), target)
            assertTrue("step $i: $d after $previous", d <= previous + 1e-5f)
            previous = d
        }
    }

    @Test
    fun `progress runs from 0 to 1 on the expressive curve`() {
        assertEquals(0f, ViewerOpeningMove.progressAt(0f, 0.7f), 1e-6f)
        assertEquals(1f, ViewerOpeningMove.progressAt(0.7f, 0.7f), 1e-6f)
        assertEquals(1f, ViewerOpeningMove.progressAt(5f, 0.7f), 1e-6f)
        assertEquals(1f, ViewerOpeningMove.progressAt(0f, 0f), 0f)
        val mid = ViewerOpeningMove.progressAt(0.35f, 0.7f)
        assertTrue(mid > 0f && mid < 1f)
        assertEquals(700, ViewerOpeningMove.DURATION_MILLIS)
    }

    @Test
    fun `a dropped frame only advances the move by the cap`() {
        assertEquals(ViewerOpeningMove.MAX_STEP_SECONDS, ViewerOpeningMove.step(1.5f), 0f)
        assertEquals(0.016f, ViewerOpeningMove.step(0.016f), 0f)
        assertEquals(0f, ViewerOpeningMove.step(-1f), 0f)
        assertEquals(0f, ViewerOpeningMove.step(Float.NaN), 0f)
    }

    @Test
    fun `the handover starts where the touch stopped the move and folds to nothing`() {
        assertEquals(0.4f, ViewerOpeningMove.handoverRemaining(0.4f, 0f), 0f)
        assertEquals(0f, ViewerOpeningMove.handoverRemaining(0.4f, ViewerOpeningMove.HANDOVER_SECONDS), 0f)
        val half = ViewerOpeningMove.handoverRemaining(0.4f, ViewerOpeningMove.HANDOVER_SECONDS / 2f)
        assertEquals(0.1f, half, 1e-6f)
    }

    @Test
    fun `a zoom eases between its two distances`() {
        assertEquals(2f, ViewerOpeningMove.zoomDistance(2f, 0.5f, 0f), 1e-5f)
        assertEquals(0.5f, ViewerOpeningMove.zoomDistance(2f, 0.5f, 1f), 1e-5f)
        val mid = ViewerOpeningMove.zoomDistance(2f, 0.5f, 0.5f)
        assertTrue(mid in 0.5f..2f)
        assertEquals(0.5f, ViewerOpeningMove.zoomDistance(0f, 0.5f, 0.3f), 0f)
    }

    /** A gesture camera that sits still at [rest] looking at [target]. */
    private inner class StillCamera : CameraGestureDetector.CameraManipulator {
        var grabs = 0
        override fun setViewport(width: Int, height: Int) = Unit
        override fun getTransform(): Transform = Transform(lookAt(rest, target, Float3(0f, 1f, 0f)))
        override fun grabBegin(x: Int, y: Int, strafe: Boolean) {
            grabs++
        }
        override fun grabUpdate(x: Int, y: Int) = Unit
        override fun grabEnd() = Unit
        override fun scrollBegin(x: Int, y: Int, separation: Float) = Unit
        override fun scrollUpdate(x: Int, y: Int, prevSeparation: Float, currSeparation: Float) = Unit
        override fun scrollEnd() = Unit
        override fun update(deltaTime: Float) = Unit
    }

    @Test
    fun `a touch mid-move takes the camera over without a jump`() {
        var progress = 0.4f
        val inner = StillCamera()
        val move = OpeningMoveCameraManipulator(inner, target = { target }, progress = { progress })
        val before = move.getTransform().position
        move.grabBegin(0, 0, false)
        assertEquals(1, inner.grabs)
        assertTrue(move.isTouched)
        assertSame(before, move.getTransform().position)
        // The animation keeps running underneath: a stopped move ignores it.
        progress = 0.9f
        assertSame(before, move.getTransform().position)
        // Then what was left folds into the inner camera, decelerating: each frame's step is no
        // bigger than the one before, the first one a few centimetres, never a cut.
        var last = before
        var lastStep = Float.MAX_VALUE
        repeat(30) {
            move.update(1f / 60f)
            val now = move.getTransform().position
            val step = distance(now, last)
            assertTrue("step $it: $step", step < 0.15f && step <= lastStep + 1e-4f)
            last = now
            lastStep = step
        }
        assertEquals(0f, move.remaining, 0f)
        assertSame(rest, move.getTransform().position)
    }

    @Test
    fun `an untouched move ends on the inner camera`() {
        var progress = 0f
        val move = OpeningMoveCameraManipulator(StillCamera(), target = { target }, progress = { progress })
        assertTrue(distance(move.getTransform().position, target) > distance(rest, target) * 1.5f)
        progress = 1f
        assertSame(rest, move.getTransform().position)
        assertFalse(move.isFrameActive)
    }

    @Test
    fun `start over replays the move for a new subject`() {
        val move = OpeningMoveCameraManipulator(StillCamera(), target = { target }, progress = { 0.5f })
        move.grabBegin(0, 0, false)
        assertTrue(move.isTouched)
        move.startOver()
        assertFalse(move.isTouched)
        assertEquals(0.5f, move.remaining, 1e-6f)
    }
}
