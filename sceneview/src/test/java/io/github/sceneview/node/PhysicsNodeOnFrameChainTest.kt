package io.github.sceneview.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract between `SceneScope.PhysicsNode` and the node it drives — issue #1694, then #3718.
 *
 * #1694: `PhysicsNode` took [Node.onFrame], a single nullable slot that belongs to the caller, so
 * attaching physics silently discarded whatever the caller had put there and `onDispose` destroyed
 * it. That was patched with a save / chain / restore dance.
 *
 * #3718 replaced the dance with a separation. `Node.internalOnFrame` is the library's own per-frame
 * hook and — the point of it — it is *not* read by `Node.isFrameActive`, whereas the public
 * `onFrame` is. A body that had come to rest therefore kept its whole scene at full cadence: the
 * sphere stack in the animation-physics demo held 878 frames per 15 s on a picture identical to the
 * byte. `PhysicsBody` already knew it was finished (`isAsleep`); nothing asked it.
 *
 * [Node] needs a Filament `Engine` (native JNI), so it cannot be built in a pure-JVM test. This
 * pins the semantics the composable relies on, against a double with the same shape as the two
 * slots and the activity-provider list.
 */
class PhysicsNodeOnFrameChainTest {

    /** Mirrors the shape of the two `Node` callback slots and its activity-provider list. */
    private class FrameSlots {
        /** The caller's. Read by `isFrameActive` as a standing request for frames. */
        var onFrame: ((frameTimeNanos: Long) -> Unit)? = null

        /** The library's. Carries no meaning for `isFrameActive`. */
        var internalOnFrame: ((frameTimeNanos: Long) -> Unit)? = null

        private val activityProviders = mutableListOf<() -> Boolean>()

        fun addFrameActivityProvider(provider: () -> Boolean): () -> Unit {
            activityProviders += provider
            return { activityProviders -= provider }
        }

        /** The rule under test, as `Node.isFrameActive` composes it. */
        val isFrameActive: Boolean
            get() = onFrame != null || activityProviders.any { it() }

        fun tick(frameTimeNanos: Long) {
            internalOnFrame?.invoke(frameTimeNanos)
            onFrame?.invoke(frameTimeNanos)
        }
    }

    /** A stand-in for `PhysicsBody`: steps until it falls asleep. */
    private class Body(var stepsUntilAsleep: Int) {
        var isAsleep = false
            private set
        var steps = 0
            private set

        fun step() {
            if (isAsleep) return
            steps++
            if (steps >= stepsUntilAsleep) isAsleep = true
        }
    }

    /** Reproduces the body of `PhysicsNode`'s `DisposableEffect`. Returns its `onDispose`. */
    private fun attachPhysics(slots: FrameSlots, body: Body): () -> Unit {
        slots.internalOnFrame = { body.step() }
        val removeActivityProvider = slots.addFrameActivityProvider { !body.isAsleep }
        return {
            slots.internalOnFrame = null
            removeActivityProvider()
        }
    }

    @Test
    fun `a body still in flight keeps the scene rendering`() {
        val slots = FrameSlots()
        val body = Body(stepsUntilAsleep = 3)
        attachPhysics(slots, body)

        assertTrue("a body that has not settled needs a frame every tick", slots.isFrameActive)
        slots.tick(1L)
        assertTrue(slots.isFrameActive)
    }

    @Test
    fun `a body that has come to rest lets the scene park`() {
        val slots = FrameSlots()
        val body = Body(stepsUntilAsleep = 2)
        attachPhysics(slots, body)

        slots.tick(1L)
        slots.tick(2L)

        assertTrue("the stand-in body reached its rest state", body.isAsleep)
        assertFalse(
            "a settled simulation must stop asking — this is the 878 frames / 15 s defect",
            slots.isFrameActive
        )
    }

    @Test
    fun `attaching physics does not make an otherwise idle node active`() {
        val slots = FrameSlots()
        val body = Body(stepsUntilAsleep = 1)
        attachPhysics(slots, body)
        slots.tick(1L)

        assertNull(
            "physics must not occupy the caller's slot — that is what used to pin the loop",
            slots.onFrame
        )
        assertFalse(slots.isFrameActive)
    }

    @Test
    fun `the caller's callback is untouched and still fires every frame`() {
        val slots = FrameSlots()
        val callerCalls = mutableListOf<Long>()
        val callerCallback: (Long) -> Unit = { callerCalls += it }
        slots.onFrame = callerCallback

        val body = Body(stepsUntilAsleep = 99)
        val dispose = attachPhysics(slots, body)

        slots.tick(100L)
        slots.tick(200L)

        assertEquals("physics steps every frame", 2, body.steps)
        assertEquals("caller callback still fires every frame", listOf(100L, 200L), callerCalls)

        dispose()
        assertSame("and is still exactly the caller's own", callerCallback, slots.onFrame)
    }

    @Test
    fun `the library hook runs before the caller's, so an observer sees this frame's write-back`() {
        val slots = FrameSlots()
        val order = mutableListOf<String>()
        slots.onFrame = { order += "caller" }
        slots.internalOnFrame = { order += "physics" }

        slots.tick(1L)

        assertEquals(listOf("physics", "caller"), order)
    }

    @Test
    fun `dispose stops the simulation asking for frames`() {
        val slots = FrameSlots()
        val body = Body(stepsUntilAsleep = 99)
        val dispose = attachPhysics(slots, body)

        assertTrue(slots.isFrameActive)
        dispose()

        assertFalse("a removed provider stops voting", slots.isFrameActive)
        slots.tick(1L)
        assertEquals("and the simulation no longer steps", 0, body.steps)
    }
}
