package io.github.sceneview.node

import com.google.android.filament.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `Node.destroy()` on a wide tree must stay linear.
 *
 * Field ANR (an app on SceneView 4.65.0, Galaxy A25, API 36): the main thread stuck in
 * `Node.destroy → setParent → setChildNodes → onTransformChanged → onWorldTransformChanged`
 * while a model left the composition. Each child's destroy detaches it from its parent, and the
 * parent — itself being destroyed — re-walked every subtree it still held after each detach:
 * O(children² × subtree) world-transform invalidations for one destroy.
 *
 * Real [Node]s over a Filament without JNI (Robolectric); the count is the number of
 * [Node.onWorldTransformChanged] calls, the work the ANR stack was doing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class NodeDestroyCostTest {

    private val engine: Engine = FilamentWithoutJni.engine()
    private var nextEntity = 1
    private var invalidations = 0

    private inner class CountingNode : Node(engine, nextEntity++) {
        override fun onWorldTransformChanged() {
            invalidations++
            super.onWorldTransformChanged()
        }
    }

    /** [width] children under [root], each holding [depth] grandchildren. */
    private fun wideTree(root: Node, width: Int, depth: Int): List<Node> {
        val all = mutableListOf<Node>()
        repeat(width) {
            val child = CountingNode().apply { parent = root }
            all += child
            repeat(depth) { all += CountingNode().apply { parent = child } }
        }
        return all
    }

    @Test
    fun `destroying a wide tree invalidates no subtree it is tearing down`() {
        val root = CountingNode()
        val nodes = wideTree(root, width = 40, depth = 10) + root
        invalidations = 0

        root.destroy()

        assertTrue(nodes.all { it.isDestroyed })
        assertTrue(nodes.all { it.childNodes.isEmpty() })
        // Was 10,820 for these 441 nodes: every detach re-walked the siblings still attached.
        assertTrue("$invalidations world-transform invalidations for ${nodes.size} nodes", invalidations <= nodes.size)
    }

    @Test
    fun `a live parent still hears that a destroyed subtree left it`() {
        val parent = CountingNode()
        val kept = CountingNode().apply { this.parent = parent }
        val leaving = CountingNode()
        wideTree(leaving, width = 3, depth = 2)
        leaving.parent = parent
        invalidations = 0

        leaving.destroy()

        assertEquals(setOf(kept), parent.childNodes)
        assertFalse(parent.isDestroyed)
        // The live parent's own change still runs: itself and the child it keeps (was 23).
        assertEquals(2, invalidations)
    }
}
