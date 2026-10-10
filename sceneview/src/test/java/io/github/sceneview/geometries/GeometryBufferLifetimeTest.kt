package io.github.sceneview.geometries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins when a [Geometry]'s buffers are released (#4344).
 *
 * The buffers themselves need the native library, so the bookkeeping is exercised on plain
 * strings standing in for a `VertexBuffer`/`IndexBuffer` pair. What matters is the order: a pair
 * is released only once no tracked renderable can still draw from it, and then at once — not on
 * a later rendered frame, which never comes when no view is rendering.
 */
class GeometryBufferLifetimeTest {

    /** A node standing in for `RenderableNode`'s consumer: records what it was pointed at. */
    private inner class FakeNode(val failing: Boolean = false) : Geometry.Consumer {
        var boundTo: String? = null
        var rebinds = 0
        val validated = mutableListOf<Int>()

        override fun validate(primitiveCount: Int) {
            validated += primitiveCount
        }

        override fun rebind() {
            if (failing) error("rebind failed")
            rebinds++
            boundTo = current
        }
    }

    private val lifetime = GeometryBufferLifetime<String>()
    private var current = "buffers-1"
    private val released = mutableListOf<String>()
    private val release: (String) -> Unit = { released += it }

    private fun replaceBuffers(with: String) {
        lifetime.retire(current)
        current = with
    }

    @Test
    fun `destroy is a no-op while a node is still bound`() {
        val node = FakeNode()
        lifetime.attach(node)

        assertFalse(lifetime.destroy(current, release))

        assertTrue(released.isEmpty())
        assertFalse(lifetime.isDestroyed)
    }

    @Test
    fun `the last node to detach lets the buffers go immediately`() {
        val first = FakeNode()
        val second = FakeNode()
        lifetime.attach(first)
        lifetime.attach(second)

        lifetime.detach(first)
        assertFalse("a shared geometry outlives its first node", lifetime.destroy(current, release))
        assertEquals(1, lifetime.consumerCount)

        lifetime.detach(second)
        assertTrue(lifetime.destroy(current, release))
        assertEquals(listOf("buffers-1"), released)
    }

    @Test
    fun `an unbound geometry is released at once`() {
        // No node, no rendered frame: nothing to wait for.
        assertTrue(lifetime.destroy(current, release))
        assertEquals(listOf("buffers-1"), released)
    }

    @Test
    fun `destroy releases once however often it is called`() {
        assertTrue(lifetime.destroy(current, release))
        assertFalse(lifetime.destroy(current, release))
        assertFalse(lifetime.destroy(current, release))

        assertEquals(listOf("buffers-1"), released)
    }

    @Test
    fun `attaching the same node twice counts once`() {
        val node = FakeNode()
        lifetime.attach(node)
        lifetime.attach(node)
        assertEquals(1, lifetime.consumerCount)

        lifetime.detach(node)
        assertTrue(lifetime.destroy(current, release))
    }

    @Test
    fun `a destroyed geometry refuses new nodes`() {
        lifetime.destroy(current, release)

        assertThrows(IllegalStateException::class.java) { lifetime.attach(FakeNode()) }
    }

    @Test
    fun `replaced buffers are released only after every node was re-pointed`() {
        val first = FakeNode()
        val second = FakeNode()
        lifetime.attach(first)
        lifetime.attach(second)

        replaceBuffers(with = "buffers-2")
        assertEquals(1, lifetime.retiredCount)
        assertTrue("nothing is released before the nodes are rebound", released.isEmpty())

        lifetime.rebind { buffers ->
            // By the time the old pair is released, both nodes draw from the new one.
            assertEquals("buffers-2", first.boundTo)
            assertEquals("buffers-2", second.boundTo)
            released += buffers
        }

        assertEquals(listOf("buffers-1"), released)
        assertEquals(0, lifetime.retiredCount)
    }

    @Test
    fun `an in-place update releases nothing`() {
        val node = FakeNode()
        lifetime.attach(node)

        repeat(3) { lifetime.rebind(release) }

        assertEquals(3, node.rebinds)
        assertTrue(released.isEmpty())
    }

    @Test
    fun `a failed rebind keeps the replaced buffers alive and the next one retries`() {
        val failing = FakeNode(failing = true)
        lifetime.attach(failing)
        replaceBuffers(with = "buffers-2")

        assertThrows(IllegalStateException::class.java) { lifetime.rebind(release) }
        assertTrue("the failing node may still draw from the old pair", released.isEmpty())
        assertEquals(1, lifetime.retiredCount)

        // The node goes away; the retry has nothing left pointing at the old pair.
        lifetime.detach(failing)
        lifetime.rebind(release)
        assertEquals(listOf("buffers-1"), released)
    }

    @Test
    fun `successive rebuilds between two rebinds release every replaced pair exactly once`() {
        val node = FakeNode()
        lifetime.attach(node)

        replaceBuffers(with = "buffers-2")
        replaceBuffers(with = "buffers-3")
        lifetime.rebind(release)
        lifetime.rebind(release)

        assertEquals(listOf("buffers-1", "buffers-2"), released)
        assertEquals("buffers-3", node.boundTo)
    }

    @Test
    fun `destroy also releases a pair left retired by a failed rebind`() {
        val failing = FakeNode(failing = true)
        lifetime.attach(failing)
        replaceBuffers(with = "buffers-2")
        assertThrows(IllegalStateException::class.java) { lifetime.rebind(release) }

        lifetime.detach(failing)
        assertTrue(lifetime.destroy(current, release))

        assertEquals(listOf("buffers-1", "buffers-2"), released)
        assertEquals(0, lifetime.retiredCount)
    }

    @Test
    fun `a throwing release never hands the same pair out twice`() {
        replaceBuffers(with = "buffers-2")
        var calls = 0

        assertThrows(IllegalStateException::class.java) {
            lifetime.rebind { calls++; error("native destroy failed") }
        }
        lifetime.rebind { calls++ }

        assertEquals(1, calls)
    }

    @Test
    fun `validate reaches every bound node before any buffer is touched`() {
        val first = FakeNode()
        val second = FakeNode()
        lifetime.attach(first)
        lifetime.attach(second)

        lifetime.validate(primitiveCount = 3)

        assertEquals(listOf(3), first.validated)
        assertEquals(listOf(3), second.validated)
        assertTrue(released.isEmpty())
    }
}
