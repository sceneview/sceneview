package io.github.sceneview.gesture

import android.view.MotionEvent
import com.google.android.filament.Engine
import io.github.sceneview.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A drag begun on empty space edits [GestureDetector.emptySpaceEditTarget] (the placed object in
 * `AutoPlacementScene`): the user never has to aim at the object.
 */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class EmptySpaceEditTargetTest {

    @Test
    fun `a drag begun on empty space edits the empty-space target`() {
        var begins = 0
        var moves = 0
        val target = object : Node(fakeEngine(), 1) {
            override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent): Boolean {
                begins++
                return true
            }

            override fun onMove(detector: MoveGestureDetector, e: MotionEvent): Boolean {
                moves++
                return true
            }
        }.apply { isEditable = true }
        val detector = GestureDetector(RuntimeEnvironment.getApplication(), null)
        detector.emptySpaceEditTarget = { target }
        drag(detector)
        assertEquals("one drag, one begin on the target", 1, begins)
        assertTrue("the drag's moves reach the target", moves > 0)
    }

    @Test
    fun `without a target a drag on empty space edits nothing`() {
        var begins = 0
        val bystander = object : Node(fakeEngine(), 1) {
            override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent): Boolean {
                begins++
                return true
            }
        }.apply { isEditable = true }
        val detector = GestureDetector(RuntimeEnvironment.getApplication(), null)
        drag(detector)
        assertEquals("no node is edited by an empty-space drag ($bystander)", 0, begins)
    }

    /** One finger down on empty space, then dragged 300 px to the right. */
    private fun drag(detector: GestureDetector) {
        var clock = 1_000L
        fun send(action: Int, x: Float) {
            val event = MotionEvent.obtain(1_000L, clock, action, x, 500f, 0)
            detector.onTouchEvent(event, null)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 200f)
        repeat(15) { step ->
            clock += 16L
            send(MotionEvent.ACTION_MOVE, 200f + 20f * (step + 1))
        }
        clock += 16L
        send(MotionEvent.ACTION_UP, 500f)
    }

    /** A JVM-only [Engine] handle: nodes need one, these tests never render. */
    private fun fakeEngine(): Engine = Engine::class.java
        .getDeclaredConstructor(Long::class.javaPrimitiveType, Engine.Config::class.java)
        .apply { isAccessible = true }
        .newInstance(1L, null)
}
