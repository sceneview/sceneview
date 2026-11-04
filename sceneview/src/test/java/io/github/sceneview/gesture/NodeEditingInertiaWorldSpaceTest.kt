package io.github.sceneview.gesture

import android.view.MotionEvent
import com.google.android.filament.Engine
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.collision.HitResult
import io.github.sceneview.math.Position
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
import kotlin.math.abs

/**
 * A drag released mid-flick keeps coasting. In AR the phone keeps moving: turned mid-coast, a
 * pointer that coasts across the screen hit-tests another spot of the floor every frame, and the
 * object follows the camera. A [MoveCoastSpace] makes the coast slide along the surface instead.
 *
 * The fake space below is a floor seen from above, 1 px = 1 mm, with a camera that pans by
 * [cameraShift] px: a node's hit under the pointer is `(x - cameraShift) / 1000` metres.
 */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class NodeEditingInertiaWorldSpaceTest {

    private lateinit var node: Node
    private lateinit var detector: GestureDetector
    private val hits = mutableListOf<Float>()
    private var ends = 0

    private var cameraShift = 0f
    private var inView = true
    private var surfaceAtRelease = true

    private val space = object : MoveCoastSpace {
        override fun release(owner: Node, x: Float, y: Float) = if (surfaceAtRelease) {
            MoveCoastSpace.Surface(
                origin = Position((x - cameraShift) / PX_PER_M, 0f, y / PX_PER_M),
                perPixelX = Float3(1f / PX_PER_M, 0f, 0f),
                perPixelY = Float3(0f, 0f, 1f / PX_PER_M),
            )
        } else {
            null
        }

        override fun toScreen(position: Position) =
            Float2(position.x * PX_PER_M + cameraShift, position.z * PX_PER_M).takeIf { inView }
    }

    private var downTime = 0L
    private var clock = 0L

    @Before
    fun setUp() {
        val engine = Engine::class.java
            .getDeclaredConstructor(Long::class.javaPrimitiveType, Engine.Config::class.java)
            .apply { isAccessible = true }
            .newInstance(1L, null)
        node = object : Node(engine, 1) {
            override fun onMoveBegin(detector: MoveGestureDetector, e: MotionEvent) = true

            override fun onMove(detector: MoveGestureDetector, e: MotionEvent): Boolean {
                // Where the floor is under the pointer, as the camera sees it now.
                hits += (e.x - cameraShift) / PX_PER_M
                return true
            }

            override fun onMoveEnd(detector: MoveGestureDetector, e: MotionEvent) {
                ends++
            }
        }.apply { isEditable = true }
        detector = GestureDetector(RuntimeEnvironment.getApplication(), null)
    }

    private fun send(action: Int, x: Float) {
        val event = MotionEvent.obtain(downTime, clock, action, x, Y, 0)
        detector.onTouchEvent(event, HitResult(node))
        event.recycle()
    }

    /** A fast flick to the right, released at speed. */
    private fun flick() {
        downTime = 1_000L
        clock = downTime
        var x = 100f
        send(MotionEvent.ACTION_DOWN, x)
        repeat(10) {
            clock += 16L
            x += 24f
            send(MotionEvent.ACTION_MOVE, x)
        }
        send(MotionEvent.ACTION_UP, x)
        assertTrue("a fast flick must coast", detector.isCoasting)
        hits.clear()
    }

    /** One coast frame with the camera still, then the phone turns, then one more frame. */
    private fun turnMidCoast(): Float {
        detector.advanceInertia(FRAME_SECONDS)
        val before = hits.last()
        cameraShift += 300f
        detector.advanceInertia(FRAME_SECONDS)
        return hits.last() - before
    }

    @Test
    fun `without a space the turning phone drags the node`() {
        flick()
        val jump = turnMidCoast()
        assertTrue("the screen-space coast must show the bug it fixes, moved $jump m", jump < -0.25f)
    }

    @Test
    fun `with a space the node keeps sliding along the floor`() {
        detector.moveCoastSpace = space
        flick()
        val step = turnMidCoast()
        assertTrue("one frame of coast, not the camera's 0.3 m, moved $step m", step > 0f && step < 0.05f)
    }

    @Test
    fun `the world coast decelerates and closes the edit once at rest`() {
        detector.moveCoastSpace = space
        flick()
        var frames = 0
        while (detector.isCoasting && frames < 1_000) {
            detector.advanceInertia(FRAME_SECONDS)
            cameraShift += 2f
            frames++
        }
        assertFalse("the coast must come to rest", detector.isCoasting)
        assertTrue("it must slide forward first", hits.size > 2)
        hits.zipWithNext().forEach { (a, b) -> assertTrue("never backwards: $a → $b", b >= a) }
        assertEquals("the deferred end must fire once", 1, ends)
    }

    @Test
    fun `out of view the coast ends where the node was last seen`() {
        detector.moveCoastSpace = space
        flick()
        detector.advanceInertia(FRAME_SECONDS)
        val seen = hits.size
        inView = false
        detector.advanceInertia(FRAME_SECONDS)
        assertFalse(detector.isCoasting)
        assertEquals("no hit test once out of view", seen, hits.size)
        assertEquals(1, ends)
    }

    @Test
    fun `no surface at release keeps the screen-space coast`() {
        detector.moveCoastSpace = space
        surfaceAtRelease = false
        flick()
        val jump = turnMidCoast()
        assertTrue("screen space, as without a space, moved $jump m", abs(jump + 0.3f) < 0.05f)
    }

    private companion object {
        const val PX_PER_M = 1000f
        const val Y = 500f
        const val FRAME_SECONDS = 1f / 60f
    }
}
