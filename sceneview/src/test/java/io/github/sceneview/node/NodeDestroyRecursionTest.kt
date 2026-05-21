package io.github.sceneview.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guard for issue #2036.
 *
 * `Node.destroy()`'s KDoc promises it destroys "the node and all its children", but the
 * implementation only freed the node's own Filament entity — imperatively-attached child
 * nodes were silently orphaned and leaked.
 *
 * The fix walks `childNodes` (post-order) before freeing the node's own entity, snapshots
 * the set to avoid `ConcurrentModificationException` while children detach themselves, and
 * guards against re-entrancy with an `isDestroyed` flag.
 *
 * [Node] itself requires a Filament `Engine` (native JNI) so it cannot be instantiated in a
 * pure-JVM test. This test pins the exact recursion / snapshot / re-entrancy semantics the
 * fix relies on, using a minimal tree node that mirrors `Node`'s destroy algorithm:
 *
 *  - a mutable `children` set (mirrors `Node.childNodes`),
 *  - each child's `destroy()` removes itself from its parent (mirrors `parent = null`),
 *  - `destroy()` snapshots children, recurses, then frees its own resource,
 *  - an `isDestroyed` flag makes a second `destroy()` a no-op.
 */
class NodeDestroyRecursionTest {

    /** Mirrors `Node`'s parent/childNodes graph + the [destroy] algorithm under test. */
    private class FakeNode(val id: String, val destroyLog: MutableList<String>) {
        var parent: FakeNode? = null
            set(value) {
                if (field != value) {
                    val old = field
                    field = value
                    old?.children?.remove(this)
                    value?.children?.add(this)
                }
            }
        val children = linkedSetOf<FakeNode>()
        var isDestroyed = false
            private set

        fun addChild(child: FakeNode) {
            child.parent = this
        }

        /** Mirrors the post-order, snapshot, re-entrancy-guarded `Node.destroy()`. */
        fun destroy() {
            if (isDestroyed) return
            isDestroyed = true
            // Snapshot: each child's destroy() mutates `children` via `parent = null`.
            children.toList().forEach { it.destroy() }
            parent = null
            destroyLog += id // stands in for engine.safeDestroyEntity(entity)
        }
    }

    @Test
    fun `destroying a parent destroys both imperatively-added children`() {
        val log = mutableListOf<String>()
        val parent = FakeNode("parent", log)
        val childA = FakeNode("childA", log)
        val childB = FakeNode("childB", log)
        parent.addChild(childA)
        parent.addChild(childB)

        parent.destroy()

        assertTrue("childA must be destroyed", childA.isDestroyed)
        assertTrue("childB must be destroyed", childB.isDestroyed)
        assertTrue("parent must be destroyed", parent.isDestroyed)
        assertEquals("all three entities freed exactly once", 3, log.size)
        assertEquals("each node freed once", log.toSet().size, log.size)
    }

    @Test
    fun `destroy recurses through a multi-level subtree`() {
        val log = mutableListOf<String>()
        val root = FakeNode("root", log)
        val mid = FakeNode("mid", log)
        val leaf = FakeNode("leaf", log)
        root.addChild(mid)
        mid.addChild(leaf)

        root.destroy()

        assertTrue(leaf.isDestroyed)
        assertTrue(mid.isDestroyed)
        assertTrue(root.isDestroyed)
        assertEquals(listOf("root", "mid", "leaf").sorted(), log.sorted())
    }

    @Test
    fun `children are destroyed before the parent — post-order`() {
        val log = mutableListOf<String>()
        val root = FakeNode("root", log)
        val mid = FakeNode("mid", log)
        val leaf = FakeNode("leaf", log)
        root.addChild(mid)
        mid.addChild(leaf)

        root.destroy()

        // Post-order: descendants freed before their ancestor — leaf, then mid, then root.
        assertEquals(listOf("leaf", "mid", "root"), log)
    }

    @Test
    fun `re-entrant destroy is a no-op — entity freed exactly once`() {
        val log = mutableListOf<String>()
        val parent = FakeNode("parent", log)
        val child = FakeNode("child", log)
        parent.addChild(child)

        parent.destroy()
        parent.destroy() // second call must do nothing
        child.destroy() // already destroyed via the parent — also a no-op

        assertEquals("no double-free", 2, log.size)
        assertEquals(listOf("child", "parent"), log)
    }

    @Test
    fun `iterating children while they detach does not corrupt the traversal`() {
        // Each child's destroy() removes it from parent.children mid-iteration.
        // Without the toList() snapshot this would be a ConcurrentModificationException.
        val log = mutableListOf<String>()
        val parent = FakeNode("parent", log)
        repeat(8) { i -> parent.addChild(FakeNode("child$i", log)) }

        parent.destroy()

        assertEquals("all 8 children + parent freed", 9, log.size)
        assertTrue("parent.children drained as children detached", parent.children.isEmpty())
    }
}
