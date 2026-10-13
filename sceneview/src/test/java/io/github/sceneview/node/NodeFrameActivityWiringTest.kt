package io.github.sceneview.node

import com.google.android.filament.Engine
import io.github.sceneview.SceneFrameActivity
import io.github.sceneview.math.Transform
import io.github.sceneview.overridesIsFrameActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Modifier
import kotlin.random.Random

/**
 * The real [Node] wiring behind render-on-demand's "is any node active?" (#3724): the hooks in the
 * `childNodes`, `onFrame` and smooth-transform setters, the provider list, `destroy()`, and the
 * reflection that tells an overriding node type from an inheriting one.
 *
 * `FrameActivityTrackerTest` proves the bookkeeping on fakes. This proves that `Node` drives it
 * correctly, by running real nodes through random sequences and comparing [SceneFrameActivity]
 * with the exact expression it replaced in the render loop: `roots.any { it.isFrameActive }`.
 *
 * A `Node` needs a Filament engine, and Filament is JNI. Robolectric is asked to instrument the
 * Filament package, which turns every native method into one that returns zero: enough for the
 * node graph, which is plain Kotlin, and for nothing that draws. No transform value is asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class NodeFrameActivityWiringTest {

    private lateinit var engine: Engine
    private var nextEntity = 1

    @Before
    fun createEngineWithoutJni() {
        val constructor = Engine::class.java.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            Engine.Config::class.java
        )
        constructor.isAccessible = true
        engine = constructor.newInstance(1L, null)
    }

    /** A term nobody reports, added the way the library's node types add theirs. */
    private class PolledNode(engine: Engine, entity: Int) : Node(engine, entity) {
        var term = false

        init {
            addFrameActivityProvider { term }
        }
    }

    /** An app's node type, overriding the public getter the documented way. */
    private class OverridingNode(engine: Engine, entity: Int) : Node(engine, entity) {
        var term = false
        override val isFrameActive: Boolean get() = term || super.isFrameActive
    }

    /** An app's node type whose override answers alone. */
    private class OverridingAloneNode(engine: Engine, entity: Int) : Node(engine, entity) {
        var term = false
        override val isFrameActive: Boolean get() = term
    }

    private fun plain() = Node(engine, nextEntity++)

    private fun walk(roots: List<Node>) = roots.any { it.isFrameActive }

    @Test
    fun `the library's own node types inherit the public getter`() {
        // Each of these used to override `isFrameActive`. If one does again, its nodes are asked
        // in full on every tick — correct, and exactly the cost #3724 removed.
        val libraryTypes = listOf(
            Node::class.java, ModelNode::class.java, BillboardNode::class.java,
            TextNode::class.java, ImageNode::class.java, VideoNode::class.java,
            ViewNode::class.java, SplatNode::class.java, MeshNode::class.java,
            CubeNode::class.java, LightNode::class.java, CameraNode::class.java,
        )
        libraryTypes.forEach { type ->
            assertFalse("${type.simpleName} overrides isFrameActive", overridesIsFrameActive(type))
        }
        assertFalse(overridesIsFrameActive(PolledNode::class.java))
    }

    @Test
    fun `the two halves of a node's own terms cannot be overridden apart`() {
        // `isSelfFrameActive` is the answer, `mayBeSelfFrameActive` decides who is asked. A node
        // type able to add a term to the first and not the second would park mid-animation, so
        // both are final and a term is added with `addFrameActivityProvider`, which feeds both.
        val halves = Node::class.java.declaredMethods.filter {
            it.name.startsWith("isSelfFrameActive") || it.name.startsWith("getMayBeSelfFrameActive")
        }
        assertEquals(halves.map { it.name }.toString(), 2, halves.size)
        halves.forEach { assertTrue("${it.name} is open", Modifier.isFinal(it.modifiers)) }
    }

    @Test
    fun `an app's override of the public getter is recognised`() {
        assertTrue(overridesIsFrameActive(OverridingNode::class.java))
        assertTrue(overridesIsFrameActive(OverridingAloneNode::class.java))
        val anonymous = object : Node(engine, nextEntity++) {
            override val isFrameActive: Boolean get() = false
        }
        assertTrue(overridesIsFrameActive(anonymous.javaClass))
        // Not a node at all, or a getter that cannot be found: ask the old way.
        assertTrue(overridesIsFrameActive(String::class.java))
    }

    @Test
    fun `each base term, set and cleared on a nested node`() {
        val activity = SceneFrameActivity()
        val root = plain()
        val child = plain().also { root.addChildNode(it) }
        val leaf = plain().also { it.parent = child }
        activity.setRoots(listOf(root))
        assertFalse(activity.hasActiveNode)

        leaf.onFrame = {}
        assertTrue(activity.hasActiveNode)
        leaf.onFrame = null
        assertFalse(activity.hasActiveNode)

        leaf.smoothTransform = Transform()
        assertTrue(activity.hasActiveNode)
        leaf.smoothTransform = null
        assertFalse(activity.hasActiveNode)

        var asleep = false
        val remove = leaf.addFrameActivityProvider { !asleep }
        assertTrue(activity.hasActiveNode)
        asleep = true
        assertFalse(activity.hasActiveNode)
        asleep = false
        assertTrue(activity.hasActiveNode)
        remove()
        assertFalse(activity.hasActiveNode)
        assertEquals(walk(listOf(root)), activity.hasActiveNode)
    }

    @Test
    fun `a subtree attached, detached and destroyed with an active node inside`() {
        val activity = SceneFrameActivity()
        val root = plain()
        activity.setRoots(listOf(root))

        val branch = plain()
        val leaf = plain().also { it.parent = branch; it.onFrame = {} }
        branch.parent = root
        assertTrue(activity.hasActiveNode)

        root.removeChildNode(branch)
        assertFalse(activity.hasActiveNode)
        assertTrue(leaf.frameActivityTrackers.isEmpty())

        root.childNodes = setOf(branch)
        assertTrue(activity.hasActiveNode)

        branch.destroy()
        assertFalse(activity.hasActiveNode)
        assertTrue(root.childNodes.isEmpty())
        assertTrue(leaf.frameActivityTrackers.isEmpty())
    }

    @Test
    fun `a child left under two parents by a throwing hook survives leaving one of them`() {
        val activity = SceneFrameActivity()
        val first = plain()
        val second = plain()
        activity.setRoots(listOf(first, second))
        val shared = plain().also { it.parent = first; it.onFrame = {} }
        val failing = plain()
        // What `SceneNodeManager` hangs on every node: a hook that runs app code and may throw.
        second.onChildAdded += { child -> check(child !== failing) { "onAddedToScene threw" } }

        // The field is written, `failing` is attached, its hook throws — and `shared`, next in
        // line, is never taken out of `first`. It now sits in both parents' `childNodes`.
        assertTrue(runCatching { second.childNodes = second.childNodes + failing + shared }.isFailure)
        assertTrue(shared in first.childNodes && shared in second.childNodes)
        assertEquals(walk(listOf(first, second)), activity.hasActiveNode)

        // Leaving the second parent must not park the scene: the first still holds the node.
        second.childNodes = second.childNodes - shared
        assertTrue(shared in first.childNodes)
        assertTrue(walk(listOf(first, second)))
        assertTrue(activity.hasActiveNode)

        first.childNodes = first.childNodes - shared
        assertFalse(activity.hasActiveNode)
        assertTrue(shared.frameActivityTrackers.isEmpty())
    }

    @Test
    fun `polled and overriding node types are seen the moment they turn active`() {
        val activity = SceneFrameActivity()
        val root = plain()
        val polled = PolledNode(engine, nextEntity++).also { it.parent = root }
        val overriding = OverridingNode(engine, nextEntity++).also { it.parent = root }
        val alone = OverridingAloneNode(engine, nextEntity++).also { it.parent = root }
        activity.setRoots(listOf(root))
        assertFalse(activity.hasActiveNode)

        polled.term = true
        assertTrue(activity.hasActiveNode)
        polled.term = false
        overriding.term = true
        assertTrue(activity.hasActiveNode)
        overriding.term = false
        alone.term = true
        assertTrue(activity.hasActiveNode)
        alone.term = false
        assertFalse(activity.hasActiveNode)

        // An override that calls super still answers for the children underneath it.
        plain().also { it.parent = overriding; it.onFrame = {} }
        assertTrue(activity.hasActiveNode)
    }

    @Test
    fun `clear lets go of every node`() {
        val activity = SceneFrameActivity()
        val root = plain()
        val nodes = List(10) { plain().also { node -> node.parent = root; node.onFrame = {} } }
        activity.setRoots(listOf(root))
        assertTrue(nodes.all { it.frameActivityTrackers.size == 1 })

        activity.clear()
        assertFalse(activity.hasActiveNode)
        assertTrue((nodes + root).all { it.frameActivityTrackers.isEmpty() })
    }

    @Test
    fun `random sequences on real nodes match the walk the render loop used to do`() {
        for (seed in longArrayOf(3724, 7, 42)) {
            val run = RandomScenes(seed)
            repeat(1_500) { step ->
                val op = run.step()
                run.scenes.forEachIndexed { index, scene ->
                    assertEquals(
                        "seed $seed, step $step, op $op, scene $index",
                        walk(run.roots[index]),
                        scene.hasActiveNode
                    )
                }
            }
            run.scenes.forEach { it.clear() }
            assertTrue(run.pool.all { it.frameActivityTrackers.isEmpty() })
        }
    }

    /** Two scenes sharing one pool of real nodes, driven by a seeded sequence of operations. */
    private inner class RandomScenes(seed: Long) {
        private val random = Random(seed)
        val scenes = listOf(SceneFrameActivity(), SceneFrameActivity())
        val roots = arrayOf(emptyList<Node>(), emptyList())
        val pool = MutableList(20) { newNode() }
        private val providerRemovers = ArrayList<() -> Unit>()
        private val providerAnswers = ArrayList<BooleanArray>()

        private fun newNode(): Node = when (random.nextInt(5)) {
            0 -> PolledNode(engine, nextEntity++)
            1 -> OverridingNode(engine, nextEntity++)
            2 -> OverridingAloneNode(engine, nextEntity++)
            else -> plain()
        }

        /** Runs one random operation and returns its number, for the failure message. */
        fun step(): Int {
            val node = pool[random.nextInt(pool.size)]
            val other = pool[random.nextInt(pool.size)]
            val op = random.nextInt(13)
            when (op) {
                in 0..5 -> mutateTree(op, node, other)
                6 -> replaceRoots()
                7 -> node.onFrame = if (node.onFrame == null) ({ _ -> }) else null
                8 -> node.smoothTransform = if (node.smoothTransform == null) Transform() else null
                9 -> toggleOwnTerm(node)
                10, 11 -> changeProvider(remove = op == 10)
                else -> destroy(node)
            }
            return op
        }

        private fun isSelfOrDescendant(node: Node, of: Node): Boolean {
            var current: Node? = node
            while (current != null) {
                if (current === of) return true
                current = current.parent
            }
            return false
        }

        private fun mutateTree(op: Int, node: Node, other: Node) {
            when (op) {
                0, 1 -> if (!isSelfOrDescendant(other, of = node)) node.parent = other
                2 -> node.parent = null
                3 -> node.childNodes = pool.shuffled(random).take(random.nextInt(4))
                    .filter { !isSelfOrDescendant(node, of = it) }.toSet()
                4 -> if (!isSelfOrDescendant(node, of = other)) node.addChildNode(other)
                else -> node.clearChildNodes()
            }
        }

        private fun replaceRoots() {
            val index = random.nextInt(scenes.size)
            roots[index] = pool.shuffled(random).take(random.nextInt(5))
            scenes[index].setRoots(roots[index])
        }

        /** A term nobody reports where the type has one, a new physics-style provider otherwise. */
        private fun toggleOwnTerm(node: Node) {
            when (node) {
                is PolledNode -> node.term = !node.term
                is OverridingNode -> node.term = !node.term
                is OverridingAloneNode -> node.term = !node.term
                else -> {
                    val answer = booleanArrayOf(random.nextBoolean())
                    providerAnswers += answer
                    providerRemovers += node.addFrameActivityProvider { answer[0] }
                }
            }
        }

        /** Removes a provider, or flips its answer behind the node's back. */
        private fun changeProvider(remove: Boolean) {
            if (providerAnswers.isEmpty()) return
            val index = random.nextInt(providerAnswers.size)
            if (remove) {
                providerRemovers.removeAt(index)()
                providerAnswers.removeAt(index)
            } else {
                providerAnswers[index][0] = !providerAnswers[index][0]
            }
        }

        /**
         * Destroyed — possibly while still on a scene's root list, as a composable node is until
         * the scene's collector catches up. `destroy()` takes the whole subtree with it, so none
         * of it is reused: fresh nodes replace them in the pool.
         */
        private fun destroy(node: Node) {
            val doomed = ArrayList<Node>()
            fun collect(current: Node) {
                doomed += current
                current.childNodes.forEach(::collect)
            }
            collect(node)
            node.destroy()
            doomed.forEach { dead ->
                val index = pool.indexOfFirst { it === dead }
                if (index >= 0) pool[index] = newNode()
            }
        }
    }
}
