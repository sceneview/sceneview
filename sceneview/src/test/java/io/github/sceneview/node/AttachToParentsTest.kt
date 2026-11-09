package io.github.sceneview.node

import com.google.android.filament.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How [ModelNode] wires its glTF nodes into a tree, without a model to load. */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class AttachToParentsTest {

    private val engine = fakeEngine()

    @Test
    fun `each node goes under its glTF parent, or under the root when that parent is not a node`() {
        val root = Node(engine, 1)
        val a = Node(engine, 2)
        val b = Node(engine, 3)
        val c = Node(engine, 4)
        val stray = Node(engine, 5)
        val parents = mapOf(a to 1, b to 2, c to 2, stray to 99)

        attachToParents(root, listOf(a, b, c, stray)) { parents[it] }

        assertSame(root, a.parent)
        assertSame(a, b.parent)
        assertSame(a, c.parent)
        assertSame(root, stray.parent)
        assertEquals(listOf(a, stray), root.childNodes.toList())
        assertEquals(listOf(b, c), a.childNodes.toList())
    }

    @Test
    fun `a node already under the root moves to its glTF parent`() {
        val root = Node(engine, 1)
        val a = Node(engine, 2)
        val camera = Node(engine, 3).apply { parent = root }

        attachToParents(root, listOf(a, camera)) { if (it === camera) 2 else 1 }

        assertSame(a, camera.parent)
        assertEquals(setOf(a), root.childNodes)
        assertEquals(setOf(camera), a.childNodes)
    }

    @Test
    fun `a wide model is wired in linear time`() {
        val root = Node(engine, 1)
        val children = List(20_000) { Node(engine, 10 + it) }

        val started = System.nanoTime()
        attachToParents(root, children) { 1 }
        val ms = (System.nanoTime() - started) / 1_000_000

        assertEquals(children.size, root.childNodes.size)
        assertTrue(children.all { it.parent === root })
        // The quadratic version copied a 20,000-node set once per child; 63 ms locally, room for CI.
        assertTrue("took $ms ms", ms < 5_000)
    }

    /** A JVM-only [Engine] handle: nodes need one, these tests never render. */
    private fun fakeEngine(): Engine = Engine::class.java
        .getDeclaredConstructor(Long::class.javaPrimitiveType, Engine.Config::class.java)
        .apply { isAccessible = true }
        .newInstance(1L, null)
}
