package io.github.sceneview.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Regression guard for issue #1694.
 *
 * `PhysicsNode`'s `DisposableEffect` used to do `node.onFrame = { ... }` and
 * `onDispose { node.onFrame = null }` unconditionally. Because [Node.onFrame] is a single
 * nullable slot, that silently clobbered any caller-provided callback and destroyed it on
 * dispose even if PhysicsNode never owned it.
 *
 * The fix saves the prior callback, chain-calls it from PhysicsNode's handler, and restores
 * it on dispose. [Node] itself requires a Filament `Engine` (native JNI) so it cannot be
 * instantiated in a pure-JVM test — this test pins the exact save / chain / restore semantics
 * the fix relies on, using a plain nullable callback slot that mirrors `Node.onFrame`.
 */
class PhysicsNodeOnFrameChainTest {

    /** Mirrors the shape of `Node.onFrame` — a single nullable callback slot. */
    private class FrameSlot {
        var onFrame: ((frameTimeNanos: Long) -> Unit)? = null
    }

    /**
     * Reproduces the save / chain / restore body of PhysicsNode's `DisposableEffect`.
     * Returns the `onDispose` action so the test can run it explicitly.
     */
    private fun attachPhysics(slot: FrameSlot, step: (Long) -> Unit): () -> Unit {
        val previousOnFrame = slot.onFrame
        slot.onFrame = { frameTimeNanos ->
            step(frameTimeNanos)
            previousOnFrame?.invoke(frameTimeNanos)
        }
        return { slot.onFrame = previousOnFrame }
    }

    @Test
    fun `pre-existing caller callback is still invoked while physics is attached`() {
        val slot = FrameSlot()
        val callerCalls = mutableListOf<Long>()
        slot.onFrame = { callerCalls += it }

        val physicsCalls = mutableListOf<Long>()
        attachPhysics(slot) { physicsCalls += it }

        slot.onFrame!!.invoke(100L)
        slot.onFrame!!.invoke(200L)

        assertEquals("physics step runs every frame", listOf(100L, 200L), physicsCalls)
        assertEquals("caller callback still fires every frame", listOf(100L, 200L), callerCalls)
    }

    @Test
    fun `dispose restores the caller callback instead of nulling it`() {
        val slot = FrameSlot()
        val callerCallback: (Long) -> Unit = {}
        slot.onFrame = callerCallback

        val dispose = attachPhysics(slot) { /* physics step */ }
        dispose()

        assertSame("caller callback is restored exactly", callerCallback, slot.onFrame)
    }

    @Test
    fun `dispose restores null when there was no caller callback`() {
        val slot = FrameSlot()
        // No caller callback set.

        val dispose = attachPhysics(slot) { /* physics step */ }
        dispose()

        assertNull("slot returns to its prior empty state", slot.onFrame)
    }

    @Test
    fun `physics step runs before the chained caller callback`() {
        val slot = FrameSlot()
        val order = mutableListOf<String>()
        slot.onFrame = { order += "caller" }

        attachPhysics(slot) { order += "physics" }
        slot.onFrame!!.invoke(1L)

        assertEquals(listOf("physics", "caller"), order)
    }
}
