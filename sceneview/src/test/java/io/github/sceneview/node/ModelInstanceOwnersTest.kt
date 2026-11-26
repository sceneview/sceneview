package io.github.sceneview.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInstanceOwnersTest {
    private class FakeNode(var destroyed: Boolean = false)

    private val owners = ModelInstanceOwners<Any, FakeNode> { !it.destroyed }

    @Test
    fun `one node on its instance is not reported`() {
        val instance = Any()
        val node = FakeNode()
        owners.claim(instance, node)
        assertTrue(owners.othersThan(instance, node).isEmpty())
    }

    @Test
    fun `two live nodes on one instance are reported to each other`() {
        val instance = Any()
        val first = FakeNode()
        val second = FakeNode()
        owners.claim(instance, first)
        owners.claim(instance, second)
        assertEquals(listOf(first), owners.othersThan(instance, second))
        assertEquals(listOf(second), owners.othersThan(instance, first))
    }

    @Test
    fun `a node that replaced a destroyed one is not reported`() {
        val instance = Any()
        val old = FakeNode()
        owners.claim(instance, old)
        val replacement = FakeNode()
        owners.claim(instance, replacement)
        // The recomposition destroys the old wrapper after creating the new one; the check runs later.
        old.destroyed = true
        assertTrue(owners.othersThan(instance, replacement).isEmpty())
    }

    @Test
    fun `nodes on different instances are not reported`() {
        val a = FakeNode()
        val b = FakeNode()
        val instanceA = Any()
        val instanceB = Any()
        owners.claim(instanceA, a)
        owners.claim(instanceB, b)
        assertTrue(owners.othersThan(instanceA, a).isEmpty())
        assertTrue(owners.othersThan(instanceB, b).isEmpty())
    }

    @Test
    fun `claiming twice with the same node keeps one entry`() {
        val instance = Any()
        val node = FakeNode()
        owners.claim(instance, node)
        owners.claim(instance, node)
        val other = FakeNode()
        owners.claim(instance, other)
        assertEquals(listOf(node), owners.othersThan(instance, other))
    }
}
