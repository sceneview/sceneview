package io.github.sceneview.node

import com.google.android.filament.Engine
import io.github.sceneview.FRAME_STEP_CALLBACKS
import io.github.sceneview.FRAME_STEP_OVERRIDE
import io.github.sceneview.FRAME_STEP_SMOOTH
import io.github.sceneview.SceneFrameDispatch
import io.github.sceneview.math.Transform
import io.github.sceneview.overridesOnFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * [SceneFrameDispatch] against the loop line it replaced, `roots.forEach { it.onFrame(t) }`
 * (#4451), on real [Node]s.
 *
 * The claim is "the same calls, in the same order, without visiting the nodes that have none".
 * So two identical worlds are built from one seed, taken through the same random sequence of
 * tree changes — add and remove a child, reparent, set and clear the callbacks, start and end a
 * glide, destroy — and ticked after every change: one by the recursive walk, one by the dispatch.
 * Every call a frame makes is logged, and the two logs must be equal.
 *
 * Same Filament-free engine as `NodeFrameActivityWiringTest`: Robolectric turns the native
 * methods into ones that return zero, which is enough for the node graph. No transform value is
 * asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.google.android.filament"])
class SceneFrameDispatchTest {

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

    /** An app's node type overriding `onFrame` the documented way: work around `super`. */
    private open class OverridingNode(engine: Engine, entity: Int) : Node(engine, entity) {
        var log: ((String) -> Unit)? = null
        override fun onFrame(frameTimeNanos: Long) {
            log?.invoke("enter")
            super.onFrame(frameTimeNanos)
            log?.invoke("leave")
        }
    }

    /** An app's node type whose override never calls `super`: its children are its business. */
    private class OverridingAloneNode(engine: Engine, entity: Int) : Node(engine, entity) {
        var log: ((String) -> Unit)? = null
        override fun onFrame(frameTimeNanos: Long) {
            log?.invoke("alone")
        }
    }

    /** Declares no override of its own, but inherits one. */
    private class InheritsAnOverride(engine: Engine, entity: Int) : OverridingNode(engine, entity)

    /** A node type that adds things, and leaves `onFrame` alone. */
    private class InheritingNode(engine: Engine, entity: Int) : Node(engine, entity)

    private fun plain() = Node(engine, nextEntity++)

    private fun walk(roots: List<Node>, frameTimeNanos: Long) =
        roots.forEach { it.onFrame(frameTimeNanos) }

    @Test
    fun `the library's node types that override onFrame are recognised, and only those`() {
        // These two are ticked in full on every frame: `ModelNode` advances its animator and
        // pops renderables there, `SplatNode` re-sorts. Everything under them is ticked by
        // their own `super.onFrame`.
        assertTrue(overridesOnFrame(ModelNode::class.java))
        assertTrue(overridesOnFrame(SplatNode::class.java))
        // The rest inherit `Node.onFrame`. If one overrides it again it is still correct — it
        // becomes an every-frame node — and this list is where that cost shows up.
        val inheriting = listOf(
            Node::class.java, BillboardNode::class.java, TextNode::class.java,
            ImageNode::class.java, VideoNode::class.java, ViewNode::class.java,
            MeshNode::class.java, CubeNode::class.java, SphereNode::class.java,
            LightNode::class.java, CameraNode::class.java,
        )
        inheriting.forEach { type ->
            assertFalse("${type.simpleName} overrides onFrame", overridesOnFrame(type))
        }
    }

    @Test
    fun `an app's override of onFrame is recognised, whatever its shape`() {
        assertTrue(overridesOnFrame(OverridingNode::class.java))
        assertTrue(overridesOnFrame(OverridingAloneNode::class.java))
        assertTrue(overridesOnFrame(InheritsAnOverride::class.java))
        assertFalse(overridesOnFrame(InheritingNode::class.java))
        val anonymous = object : Node(engine, nextEntity++) {
            override fun onFrame(frameTimeNanos: Long) = Unit
        }
        assertTrue(overridesOnFrame(anonymous.javaClass))
        // Not a node at all, so the method cannot be found: tick it the old way.
        assertTrue(overridesOnFrame(String::class.java))
    }

    @Test
    fun `the plan is the walk's own order - glide entering, children, callbacks leaving`() {
        val root = plain()
        val first = plain().also { root.addChildNode(it) }
        val leaf = plain().also { first.addChildNode(it) }
        val second = plain().also { root.addChildNode(it) }
        val idle = plain().also { root.addChildNode(it) }
        val model = OverridingNode(engine, nextEntity++).also { second.addChildNode(it) }
        // Under an overriding node: reached by its `super.onFrame`, never by the plan.
        val underModel = plain().also { model.addChildNode(it); it.onFrame = {} }

        root.onFrame = {}
        root.smoothTransform = Transform()
        first.internalOnFrame = {}
        leaf.onFrame = {}
        leaf.smoothTransform = Transform()
        second.onFrame = {}

        val planned = SceneFrameDispatch().also { it.planOnly(listOf(root)) }.steps()
        val smooth = FRAME_STEP_SMOOTH
        val callbacks = FRAME_STEP_CALLBACKS
        assertEquals(
            listOf(
                smooth to root,
                smooth to leaf,
                callbacks to leaf,
                callbacks to first,
                FRAME_STEP_OVERRIDE to model,
                callbacks to second,
                callbacks to root,
            ),
            planned
        )
        assertTrue(planned.none { it.second === idle || it.second === underModel })
    }

    @Test
    fun `a frame costs the nodes that have work, not the nodes of the tree`() {
        val dispatch = SceneFrameDispatch()
        val root = plain()
        val busy = plain().also { root.addChildNode(it); it.onFrame = {} }
        val roots = listOf(root)
        dispatch.dispatch(roots, 0L)
        assertEquals(1, dispatch.stepCount)

        // A thousand nodes with nothing to do, three levels deep: not one more step.
        repeat(10) {
            val branch = plain().also { root.addChildNode(it) }
            repeat(10) {
                val twig = plain().also { branch.addChildNode(it) }
                repeat(10) { twig.addChildNode(plain()) }
            }
        }
        dispatch.dispatch(roots, 1L)
        assertEquals(1, dispatch.stepCount)

        busy.onFrame = null
        dispatch.dispatch(roots, 2L)
        assertEquals(0, dispatch.stepCount)
    }

    @Test
    fun `an idle tree is not walked again until something changes`() {
        val dispatch = SceneFrameDispatch()
        var calls = 0
        val root = plain()
        val node = plain().also { root.addChildNode(it); it.onFrame = { calls++ } }
        val roots = listOf(root)
        dispatch.dispatch(roots, 0L)
        val before = dispatch.buildCount
        repeat(50) { dispatch.dispatch(roots, it + 1L) }
        assertEquals(before, dispatch.buildCount)
        assertEquals(51, calls)

        // Each kind of write the plan depends on throws it away, once.
        val writes = listOf<() -> Unit>(
            { root.addChildNode(plain()) },
            { node.onFrame = null },
            { node.onFrame = { calls++ } },
            { node.internalOnFrame = {} },
            { node.internalOnFrame = null },
            { node.smoothTransform = Transform() },
            { node.parent = null },
            { root.destroy() },
        )
        writes.forEachIndexed { index, write ->
            val built = dispatch.buildCount
            write()
            dispatch.dispatch(roots, 100L + index)
            assertEquals("write $index", built + 1, dispatch.buildCount)
        }
        // A new root list is a new plan too, even with the same content.
        val built = dispatch.buildCount
        dispatch.dispatch(roots.toList(), 200L)
        assertEquals(built + 1, dispatch.buildCount)
    }

    @Test
    fun `a callback replaced by another callback is the one called, without a new walk`() {
        val dispatch = SceneFrameDispatch()
        val log = ArrayList<String>()
        val node = plain().also { it.onFrame = { log += "first" } }
        val roots = listOf(node)
        dispatch.dispatch(roots, 0L)
        val built = dispatch.buildCount
        node.onFrame = { log += "second" }
        dispatch.dispatch(roots, 1L)
        assertEquals(built, dispatch.buildCount)
        assertEquals(listOf("first", "second"), log)
    }

    @Test
    fun `an overriding node is called on every frame with nothing set on it`() {
        val dispatch = SceneFrameDispatch()
        val log = ArrayList<String>()
        val root = plain()
        val overriding = OverridingNode(engine, nextEntity++).also {
            it.log = { event -> log += event }
            it.parent = root
        }
        val alone = OverridingAloneNode(engine, nextEntity++).also {
            it.log = { event -> log += event }
            it.parent = overriding
        }
        // Under the node that never calls super: as before, it is not reached.
        plain().also { it.parent = alone; it.onFrame = { log += "unreachable" } }
        plain().also { it.parent = overriding; it.onFrame = { log += "child" } }
        val roots = listOf(root)
        repeat(3) { dispatch.dispatch(roots, it.toLong()) }
        val frame = listOf("enter", "alone", "child", "leave")
        assertEquals(frame + frame + frame, log)
        assertEquals(1, dispatch.stepCount)
    }

    @Test
    fun `a node detached or destroyed by an earlier callback is not ticked on that frame`() {
        val dispatch = SceneFrameDispatch()
        val log = ArrayList<String>()
        val root = plain()
        val killer = plain().also { it.parent = root }
        val detached = plain().also { it.parent = root; it.onFrame = { log += "detached" } }
        val destroyed = plain().also { it.parent = root; it.onFrame = { log += "destroyed" } }
        plain().also { it.parent = root; it.onFrame = { log += "survivor" } }
        var armed = false
        killer.onFrame = {
            log += "killer"
            if (armed) {
                armed = false
                detached.parent = null
                destroyed.destroy()
            }
        }
        val roots = listOf(root)
        dispatch.dispatch(roots, 0L)
        assertEquals(listOf("killer", "detached", "destroyed", "survivor"), log)

        log.clear()
        armed = true
        dispatch.dispatch(roots, 1L)
        assertEquals(listOf("killer", "survivor"), log)

        log.clear()
        dispatch.dispatch(roots, 2L)
        assertEquals(listOf("killer", "survivor"), log)
    }

    @Test
    fun `a root stays ticked for the frame even when a callback changes the tree`() {
        val dispatch = SceneFrameDispatch()
        val log = ArrayList<String>()
        val first = plain()
        val second = plain().also { it.onFrame = { log += "second" } }
        first.onFrame = {
            log += "first"
            first.addChildNode(plain())
        }
        dispatch.dispatch(listOf(first, second), 0L)
        assertEquals(listOf("first", "second"), log)
    }

    @Test
    fun `work gained during a frame starts on the next one`() {
        val dispatch = SceneFrameDispatch()
        val log = ArrayList<String>()
        val root = plain()
        val giver = plain().also { it.parent = root }
        val receiver = plain().also { it.parent = root }
        giver.onFrame = {
            log += "giver"
            if (receiver.onFrame == null) receiver.onFrame = { log += "receiver" }
        }
        val roots = listOf(root)
        dispatch.dispatch(roots, 0L)
        assertEquals(listOf("giver"), log)
        dispatch.dispatch(roots, 1L)
        assertEquals(listOf("giver", "giver", "receiver"), log)
    }

    @Test
    fun `a callback that clears itself, or the dispatch, mid-frame does not break the frame`() {
        val dispatch = SceneFrameDispatch()
        val log = ArrayList<String>()
        val once = plain()
        once.onFrame = {
            log += "once"
            once.onFrame = null
        }
        val clearing = plain().also {
            it.onFrame = {
                log += "clearing"
                dispatch.clear()
            }
        }
        val last = plain().also { it.onFrame = { log += "last" } }
        val roots = listOf(once, clearing, last)
        dispatch.dispatch(roots, 0L)
        assertEquals(listOf("once", "clearing", "last"), log)
        dispatch.dispatch(roots, 1L)
        assertEquals(listOf("once", "clearing", "last", "clearing", "last"), log)
    }

    @Test
    fun `a frame pumped from inside a frame falls back to the walk`() {
        val dispatch = SceneFrameDispatch()
        val log = ArrayList<String>()
        val inner = plain().also { it.onFrame = { log += "inner" } }
        var reenter = true
        val outer = plain().also {
            it.onFrame = {
                log += "outer"
                if (reenter) {
                    reenter = false
                    dispatch.dispatch(listOf(inner), 5L)
                }
            }
        }
        dispatch.dispatch(listOf(outer, inner), 0L)
        assertEquals(listOf("outer", "inner", "inner"), log)
    }

    @Test
    fun `clear lets go of every node`() {
        val dispatch = SceneFrameDispatch()
        val roots = List(40) { plain().also { node -> node.onFrame = {} } }
        dispatch.dispatch(roots, 0L)
        assertEquals(40, dispatch.stepCount)
        dispatch.clear()
        assertEquals(0, dispatch.stepCount)
        assertEquals(0, dispatch.retainedNodeCount)
    }

    @Test
    fun `a plan that shrank does not keep the nodes it dropped`() {
        val dispatch = SceneFrameDispatch()
        val nodes = List(40) { plain().also { node -> node.onFrame = {} } }
        dispatch.dispatch(nodes, 0L)
        dispatch.dispatch(nodes.take(3), 1L)
        assertEquals(3, dispatch.retainedNodeCount)
    }

    @Test
    fun `a glide that ended forgets when it was last ticked`() {
        // A node is ticked only while it glides, so the next glide may start seconds later:
        // it must open with a nominal step, not with the pause.
        val node = plain()
        node.smoothTransform = node.transform
        node.onFrame(1_000_000_000L)
        // The target was the node's own transform: reached on the first tick, glide over.
        assertNull(node.smoothTransform)
        assertNull(node.animationDelegate.lastTickNanos)
    }

    @Test
    fun `random sequences tick exactly what the recursive walk ticks, in the same order`() {
        for (seed in longArrayOf(4451, 7, 42)) {
            val walked = World(seed)
            val dispatched = World(seed)
            val dispatch = SceneFrameDispatch()
            var events = 0
            repeat(1_500) { step ->
                val op = walked.step()
                assertEquals(op, dispatched.step())
                val frameTimeNanos = (step + 1) * 16_000_000L
                walk(walked.roots, frameTimeNanos)
                dispatch.dispatch(dispatched.roots, frameTimeNanos)
                assertEquals("seed $seed, step $step, op $op", walked.log, dispatched.log)
                assertEquals("seed $seed, step $step, op $op", walked.glides(), dispatched.glides())
                events += walked.log.size
                walked.log.clear()
                dispatched.log.clear()
            }
            // Guards the test itself: a run in which nothing ever ticked proves nothing.
            assertTrue("seed $seed logged $events events", events > 1_000)
        }
    }

    /**
     * One pool of real nodes, driven by a seeded sequence of operations. Two worlds built from
     * the same seed are the same tree, change for change, and their nodes share indices — which
     * is what the logs are written in.
     */
    private inner class World(seed: Long) {
        private val random = Random(seed)
        val log = ArrayList<String>()
        var roots = emptyList<Node>()
        private val pool = MutableList(24) { newNode(it) }

        private fun newNode(index: Int): Node = when (random.nextInt(6)) {
            0 -> OverridingNode(engine, nextEntity++)
                .also { it.log = { event -> log += "$index:$event" } }
            1 -> OverridingAloneNode(engine, nextEntity++)
                .also { it.log = { event -> log += "$index:$event" } }
            else -> plain()
        }

        /** Which nodes are still gliding: the one thing a smooth-transform tick leaves behind. */
        fun glides(): List<Int> = pool.indices.filter { pool[it].smoothTransform != null }

        /** Runs one random operation and returns its number, for the failure message. */
        fun step(): Int {
            val index = random.nextInt(pool.size)
            val node = pool[index]
            val other = pool[random.nextInt(pool.size)]
            val op = random.nextInt(13)
            when (op) {
                in 0..5 -> mutateTree(op, node, other)
                6 -> roots = pool.shuffled(random).take(random.nextInt(5))
                7, 8 -> node.onFrame =
                    if (node.onFrame == null) ({ _ -> log += "$index:onFrame" }) else null
                9 -> node.internalOnFrame =
                    if (node.internalOnFrame == null) ({ _ -> log += "$index:internal" }) else null
                // A glide to where the node already is ends on its first tick, which is the
                // write the walk and the plan must agree on; one to anywhere else keeps going.
                10 -> node.smoothTransform = when {
                    node.smoothTransform != null -> null
                    random.nextBoolean() -> node.transform
                    else -> Transform()
                }
                11 -> if (random.nextInt(4) == 0) {
                    // Destroyed — possibly while still on the root list, as a composable node
                    // is until the next recomposition — and replaced by a fresh node.
                    node.destroy()
                    pool[index] = newNode(index)
                }
                else -> Unit
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
    }
}
