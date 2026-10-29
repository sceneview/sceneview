package io.github.sceneview.gesture

import android.view.MotionEvent
import com.google.android.filament.Engine
import io.github.sceneview.collision.HitResult
import io.github.sceneview.node.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

/**
 * A coasting twist holds its `onRotateEnd` back until the node is at rest (#4653). Whatever ends
 * the coast, that deferred end must close the edit, or [NodeEditingFeedbackState] keeps the
 * node "editing" and [NodeEditingOverlay] never brings the selection ring back.
 *
 * A real two-finger stream goes through the scene's [GestureDetector] onto a real [Node] (its
 * Filament engine instrumented to no-op natives), observed by the real feedback state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class NodeEditingInertiaEndTest {

    private lateinit var engine: Engine
    private lateinit var node: Node
    private lateinit var feedback: NodeEditingFeedbackState
    private val ends = mutableListOf<Node?>()
    private lateinit var detector: GestureDetector

    private var downTime = 0L
    private var clock = 0L

    @Before
    fun setUp() {
        engine = Engine::class.java
            .getDeclaredConstructor(Long::class.javaPrimitiveType, Engine.Config::class.java)
            .apply { isAccessible = true }
            .newInstance(1L, null)
        node = Node(engine, 1).apply { isEditable = true }
        feedback = NodeEditingFeedbackState(node, scaleBaseline = 1f)
        node.addEditingListener(feedback)
        detector = GestureDetector(
            RuntimeEnvironment.getApplication(),
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onRotateEnd(detector: RotateGestureDetector, e: MotionEvent, node: Node?) {
                    ends += node
                }
            },
        )
    }

    // ── Event stream ────────────────────────────────────────────────────────────────────────────

    private val hit get() = HitResult(node)

    private fun pointers(degrees: Double, count: Int): Pair<Array<MotionEvent.PointerProperties>,
        Array<MotionEvent.PointerCoords>> {
        val t = Math.toRadians(degrees)
        val props = Array(count) { i ->
            MotionEvent.PointerProperties().apply {
                id = i
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            val sign = if (i == 0) 1 else -1
            MotionEvent.PointerCoords().apply {
                x = (CENTER + sign * RADIUS * cos(t)).toFloat()
                y = (CENTER + sign * RADIUS * sin(t)).toFloat()
                pressure = 1f
                size = 1f
            }
        }
        return props to coords
    }

    private fun send(action: Int, degrees: Double, count: Int, hitResult: HitResult? = hit) {
        val (props, coords) = pointers(degrees, count)
        val event = MotionEvent.obtain(
            downTime, clock, action, count, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0,
        )
        detector.onTouchEvent(event, hitResult)
        event.recycle()
    }

    /**
     * Two fingers twist from 0° to [sweep]° in [steps] moves of [stepMillis] each, then lift
     * (second finger first, as a hand does).
     */
    private fun twist(sweep: Double, steps: Int, stepMillis: Long) {
        clock += 1_000L
        downTime = clock
        send(MotionEvent.ACTION_DOWN, 0.0, 1)
        send(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 0.0, 2)
        var angle = 0.0
        repeat(steps) { i ->
            clock += stepMillis
            angle = sweep * (i + 1) / steps
            send(MotionEvent.ACTION_MOVE, angle, 2)
        }
        clock += 1L
        send(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), angle, 2)
        clock += 1L
        send(MotionEvent.ACTION_UP, angle, 1)
    }

    private fun tap() {
        clock += 50L
        downTime = clock
        send(MotionEvent.ACTION_DOWN, 0.0, 1)
        clock += 30L
        send(MotionEvent.ACTION_UP, 0.0, 1)
    }

    private fun assertEditClosed() {
        assertFalse("still coasting", detector.isCoasting)
        assertEquals("active kinds", emptySet<NodeEditingKind>(), feedback.activeKinds)
        assertFalse("still pressed", feedback.isPressed)
        assertFalse("still editing", feedback.isEditing)
        assertEquals("app onRotateEnd", listOf<Node?>(node), ends)
    }

    // ── Cases ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a fast twist coasts and closes the edit once at rest`() {
        twist(sweep = 90.0, steps = 10, stepMillis = 16L)
        assertTrue("a fast twist should coast", detector.isCoasting)
        assertTrue("the edit stays open while coasting", NodeEditingKind.Rotate in feedback.activeKinds)
        assertTrue("onRotateEnd is held back while coasting", ends.isEmpty())

        var frames = 0
        while (detector.isCoasting && frames < MAX_FRAMES) {
            detector.advanceInertia(FRAME_SECONDS)
            frames++
        }
        assertTrue("the coast never came to rest", frames < MAX_FRAMES)
        assertEditClosed()
    }

    @Test
    fun `a tap during the coast stops it and closes the edit`() {
        twist(sweep = 90.0, steps = 10, stepMillis = 16L)
        assertTrue(detector.isCoasting)
        repeat(3) { detector.advanceInertia(FRAME_SECONDS) }

        tap()
        assertEditClosed()
    }

    @Test
    fun `a slow twist does not coast and closes the edit on release`() {
        twist(sweep = 30.0, steps = 30, stepMillis = 100L)
        assertEditClosed()
    }

    @Test
    fun `a node destroyed mid-coast ends the coast and closes the edit`() {
        twist(sweep = 90.0, steps = 10, stepMillis = 16L)
        assertTrue(detector.isCoasting)
        detector.advanceInertia(FRAME_SECONDS)

        node.destroy()
        detector.advanceInertia(FRAME_SECONDS)
        assertEditClosed()
    }

    private companion object {
        const val CENTER = 500.0
        const val RADIUS = 200.0
        const val FRAME_SECONDS = 1f / 60f
        const val MAX_FRAMES = 60 * 30
    }
}
