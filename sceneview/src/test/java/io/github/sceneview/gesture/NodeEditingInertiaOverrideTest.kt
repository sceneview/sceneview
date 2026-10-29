package io.github.sceneview.gesture

import android.view.MotionEvent
import com.google.android.filament.Engine
import io.github.sceneview.collision.HitResult
import io.github.sceneview.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * A `Node` subclass that accepts a drag by overriding `onMoveBegin` — never touching its gesture
 * delegate, as `AutoPlacementScene`'s anchor does — still owns the edit, so it coasts with its
 * default inertia (follow-up of #4661).
 */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class NodeEditingInertiaOverrideTest {

    private class DraggableNode(engine: Engine, entity: Int) : Node(engine, entity) {
        var moves = 0
        var ends = 0

        override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent) = true
        override fun onMove(detector: MoveGestureDetector, e: MotionEvent): Boolean {
            moves++
            return true
        }
        override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent) {
            ends++
        }
    }

    @Test
    fun `a touched node whose overridden begin accepted the drag coasts`() {
        val node = DraggableNode(fakeEngine(), 1)
        val detector = GestureDetector(RuntimeEnvironment.getApplication(), null)

        flick(detector, node)

        assertTrue("the overriding node keeps Inertia.Default and must coast", detector.isCoasting)
        assertEquals("the end is deferred until the coast stops", 0, node.ends)
        val movesAtRelease = node.moves
        detector.advanceInertia(1f / 60f)
        assertTrue("a coast step must drive the node's own onMove", node.moves > movesAtRelease)
    }

    @Test
    fun `a parent whose overridden begin accepted a child's drag coasts`() {
        val engine = fakeEngine()
        val parent = DraggableNode(engine, 1)
        // A plain, non-editable model under the anchor: the hit lands on it and bubbles up.
        val child = Node(engine, 2).apply { this.parent = parent }
        val detector = GestureDetector(RuntimeEnvironment.getApplication(), null)

        flick(detector, child)

        assertEquals(
            "the overriding parent must own the drag",
            parent,
            child.gestureDelegate.claimedNode(NodeEditingKind.Move),
        )
        assertTrue("the parent keeps Inertia.Default and must coast", detector.isCoasting)
        val movesAtRelease = parent.moves
        detector.advanceInertia(1f / 60f)
        assertTrue("a coast step must drive the parent's onMove", parent.moves > movesAtRelease)
        repeat(600) { if (detector.isCoasting) detector.advanceInertia(1f / 60f) }
        assertEquals("the coast comes to rest and closes the edit once", 1, parent.ends)
    }

    private fun flick(detector: GestureDetector, target: Node) {
        var clock = 1_000L
        fun send(action: Int, x: Float) {
            val event = MotionEvent.obtain(1_000L, clock, action, x, 500f, 0)
            detector.onTouchEvent(event, HitResult(target))
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 100f)
        repeat(10) { step ->
            clock += 16L
            send(MotionEvent.ACTION_MOVE, 100f + 30f * (step + 1))
        }
        clock += 1L
        send(MotionEvent.ACTION_UP, 400f)
    }

    /** A JVM-only [Engine] handle: nodes need one, these tests never render. */
    private fun fakeEngine(): Engine = Engine::class.java
        .getDeclaredConstructor(Long::class.javaPrimitiveType, Engine.Config::class.java)
        .apply { isAccessible = true }
        .newInstance(1L, null)
}
