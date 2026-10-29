package io.github.sceneview.gesture

import android.view.MotionEvent
import com.google.android.filament.Engine
import io.github.sceneview.collision.HitResult
import io.github.sceneview.node.Node
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class NodeEditingInertiaOwnerTest {

    @Test
    fun `a vetoing editable child coasts the parent that accepted the rotation`() {
        val engine = fakeEngine()
        var parentAccepted = false
        val parent = Node(engine, 1).apply {
            isEditable = true
            onRotateBegin = { _, _ ->
                parentAccepted = true
                true
            }
        }
        val child = Node(engine, 2).apply {
            this.parent = parent
            isEditable = true
            editingInertia = Inertia.None
            onRotateBegin = { _, _ -> false }
        }
        val detector = GestureDetector(RuntimeEnvironment.getApplication(), null)
        var clock = 1_000L

        fun send(action: Int, degrees: Double, count: Int) {
            val angle = Math.toRadians(degrees)
            val properties = Array(count) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
            val coordinates = Array(count) { index ->
                val sign = if (index == 0) 1 else -1
                MotionEvent.PointerCoords().apply {
                    x = (CENTER + sign * RADIUS * cos(angle)).toFloat()
                    y = (CENTER + sign * RADIUS * sin(angle)).toFloat()
                    pressure = 1f
                    size = 1f
                }
            }
            val event = MotionEvent.obtain(
                1_000L, clock, action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, 0, 0,
            )
            detector.onTouchEvent(event, HitResult(child))
            event.recycle()
        }

        send(MotionEvent.ACTION_DOWN, 0.0, 1)
        send(
            MotionEvent.ACTION_POINTER_DOWN or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            0.0,
            2,
        )
        repeat(10) { step ->
            clock += 16L
            send(MotionEvent.ACTION_MOVE, 9.0 * (step + 1), 2)
        }
        assertTrue("the veto must delegate begin to the parent", parentAccepted)
        assertEquals(
            "the parent must own the open rotation",
            parent,
            child.gestureDelegate.claimedNode(NodeEditingKind.Rotate),
        )
        clock += 1L
        send(
            MotionEvent.ACTION_POINTER_UP or
                (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            90.0,
            2,
        )
        clock += 1L
        send(MotionEvent.ACTION_UP, 90.0, 1)

        assertTrue("the accepting parent's default inertia should coast", detector.isCoasting)
        val parentBeforeCoast = parent.quaternion
        val childBeforeCoast = child.quaternion

        detector.advanceInertia(1f / 60f)

        assertNotEquals(
            "the synthetic coast step must rotate the parent",
            parentBeforeCoast,
            parent.quaternion,
        )
        assertEquals(
            "the child vetoed the edit and must stay unchanged",
            childBeforeCoast,
            child.quaternion,
        )
    }

    /** A JVM-only [Engine] handle: nodes need one, these tests never render. */
    private fun fakeEngine(): Engine = Engine::class.java
        .getDeclaredConstructor(Long::class.javaPrimitiveType, Engine.Config::class.java)
        .apply { isAccessible = true }
        .newInstance(1L, null)

    private companion object {
        const val CENTER = 500.0
        const val RADIUS = 200.0
    }
}
