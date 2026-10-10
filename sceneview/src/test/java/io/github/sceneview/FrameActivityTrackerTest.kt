package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap
import kotlin.random.Random

/**
 * [FrameActivityTracker] must give the answer the tree walk it replaces gave (#3724) — always, not
 * usually. Wrong towards idle freezes a scene that is still animating; wrong towards busy keeps a
 * still scene at full cadence for good.
 *
 * Every test here compares the tracker against the walk itself, `roots.any { it.isFrameActive }`,
 * computed by brute force on the same tree. The tracker under test is the production class; the
 * nodes are [FakeNode]s, because a real `Node` needs a Filament engine. `FakeNode`'s `parent` and
 * `childNodes` setters are `Node`'s, statement for statement, including where the tracker is told.
 * (`NodeFrameActivityWiringTest` runs the same comparison on real `Node`s.)
 */
class FrameActivityTrackerTest {

    /** What kind of node type a [FakeNode] stands for. */
    private enum class Kind {
        /** `Node`, `MeshNode`, … — base terms only, each reported by its setter. */
        Plain,

        /** `ModelNode`, `VideoNode`, … — a term nobody reports, added to `isSelfFrameActive`. */
        Polled,

        /** An app's node type overriding the public getter and calling `super`. */
        OverrideCallingSuper,

        /** An app's node type overriding the public getter without calling `super`. */
        OverrideIgnoringSuper,
    }

    private class FakeNode(val name: String, val kind: Kind = Kind.Plain) {
        var trackers: List<FrameActivityTracker<FakeNode>> = emptyList()

        /** Makes the next write to [parent] throw, as a JNI call on a dead entity would. */
        var failNextParentWrite = false

        var selfReads = 0
        var fullReads = 0

        var parent: FakeNode? = null
            set(value) {
                if (field != value) {
                    if (failNextParentWrite) {
                        failNextParentWrite = false
                        throw IllegalStateException("parent write failed on $name")
                    }
                    val oldParent = field
                    field = value
                    oldParent?.let { it.childNodes = it.childNodes - this }
                    value?.let { it.childNodes = it.childNodes + this }
                }
            }

        var childNodes = setOf<FakeNode>()
            set(value) {
                if (field != value) {
                    val removedNodes = field - value
                    val addedNodes = value - field
                    field = value
                    trackers.forEach { it.onChildrenChanged(this, removedNodes, addedNodes) }
                    removedNodes.forEach { child ->
                        if (child.parent == this) child.parent = null
                    }
                    addedNodes.forEach { child ->
                        if (child.parent != this) child.parent = this
                    }
                }
            }

        // ---- The three reported terms ----

        var onFrame: (() -> Unit)? = null
            set(value) {
                val changed = (field == null) != (value == null)
                field = value
                if (changed) frameActivityChanged()
            }

        var smoothTransform: Any? = null
            set(value) {
                val changed = (field == null) != (value == null)
                field = value
                if (changed) frameActivityChanged()
            }

        private val providers = mutableListOf<() -> Boolean>()

        fun addProvider(provider: () -> Boolean): () -> Unit {
            providers += provider
            frameActivityChanged()
            return {
                providers -= provider
                frameActivityChanged()
            }
        }

        // ---- The terms nobody reports ----

        /** A playing animation, a pending surface frame: flips with no call to anything. */
        var polledTerm = false

        /** Whatever an app's override reads. */
        var overrideTerm = false

        private fun frameActivityChanged() = trackers.forEach { it.update(this) }

        val isSelfFrameActive: Boolean
            get() {
                selfReads++
                return (kind == Kind.Polled && polledTerm) ||
                        smoothTransform != null ||
                        onFrame != null ||
                        providers.any { it() }
            }

        val mayBeSelfFrameActive: Boolean
            get() = kind == Kind.Polled ||
                    smoothTransform != null ||
                    onFrame != null ||
                    providers.isNotEmpty()

        private val baseIsFrameActive: Boolean
            get() = isSelfFrameActive || childNodes.any { it.isFrameActive }

        /** The public getter: the brute-force walk, and what an opaque node is asked through. */
        val isFrameActive: Boolean
            get() {
                fullReads++
                return when (kind) {
                    Kind.Plain, Kind.Polled -> baseIsFrameActive
                    Kind.OverrideCallingSuper -> overrideTerm || baseIsFrameActive
                    Kind.OverrideIgnoringSuper -> overrideTerm
                }
            }

        /** `Node.destroy()`: children first, from a snapshot, then out of the parent. */
        fun destroy() {
            childNodes.toList().forEach { it.destroy() }
            runCatching { parent = null }
        }

        fun isSelfOrDescendantOf(other: FakeNode): Boolean {
            var node: FakeNode? = this
            while (node != null) {
                if (node === other) return true
                node = node.parent
            }
            return false
        }

        override fun toString() = name
    }

    private object FakeAccess : FrameActivityTracker.Access<FakeNode> {
        override fun isOpaque(node: FakeNode) =
            node.kind == Kind.OverrideCallingSuper || node.kind == Kind.OverrideIgnoringSuper

        override fun isFrameActive(node: FakeNode) = node.isFrameActive
        override fun isSelfFrameActive(node: FakeNode) = node.isSelfFrameActive
        override fun mayBeSelfFrameActive(node: FakeNode) = node.mayBeSelfFrameActive
        override fun children(node: FakeNode): Collection<FakeNode> = node.childNodes

        override fun addTracker(node: FakeNode, tracker: FrameActivityTracker<FakeNode>) {
            if (node.trackers.none { it === tracker }) node.trackers = node.trackers + tracker
        }

        override fun removeTracker(node: FakeNode, tracker: FrameActivityTracker<FakeNode>) {
            node.trackers = node.trackers.filter { it !== tracker }
        }
    }

    /** A scene: its root list, the tracker fed with it, and the walk the tracker replaces. */
    private class FakeScene {
        val tracker = FrameActivityTracker(FakeAccess)
        var roots = emptyList<FakeNode>()
            set(value) {
                field = value
                tracker.setRoots(value)
            }

        /** The code this change removed from the render loop. */
        fun walk() = roots.any { it.isFrameActive }

        /** Every node the walk can reach without going through an override. */
        fun reachable(): Set<FakeNode> {
            val seen = IdentityHashMap<FakeNode, Unit>()
            fun visit(node: FakeNode) {
                if (seen.put(node, Unit) != null) return
                if (!FakeAccess.isOpaque(node)) node.childNodes.forEach(::visit)
            }
            roots.forEach(::visit)
            return seen.keys
        }

        fun assertExact(context: String = "") {
            assertEquals("tracker disagrees with the tree walk $context", walk(), tracker.hasActiveNodes)
            val reachable = reachable()
            assertEquals("tracked set $context", reachable.size, tracker.trackedCount)
            assertEquals(
                "set of nodes asked $context",
                reachable.count { FakeAccess.isOpaque(it) || it.mayBeSelfFrameActive },
                tracker.candidateCount
            )
        }
    }

    // ---- Each way a node becomes or stops being active ----

    @Test
    fun `an empty scene and a scene of idle nodes are idle`() {
        val scene = FakeScene()
        assertFalse(scene.tracker.hasActiveNodes)
        scene.roots = listOf(FakeNode("a"), FakeNode("b").also { FakeNode("c").parent = it })
        assertFalse(scene.tracker.hasActiveNodes)
        scene.assertExact()
    }

    @Test
    fun `setting and clearing onFrame on a nested node`() {
        val scene = FakeScene()
        val root = FakeNode("root")
        val child = FakeNode("child").also { it.parent = root }
        val grandChild = FakeNode("grandChild").also { it.parent = child }
        scene.roots = listOf(root)

        grandChild.onFrame = {}
        assertTrue(scene.tracker.hasActiveNodes)
        grandChild.onFrame = {} // replaced, still set
        assertTrue(scene.tracker.hasActiveNodes)
        grandChild.onFrame = null
        assertFalse(scene.tracker.hasActiveNodes)
        scene.assertExact()
    }

    @Test
    fun `a smooth transform starting and finishing`() {
        val scene = FakeScene()
        val node = FakeNode("node")
        scene.roots = listOf(node)

        node.smoothTransform = "target"
        assertTrue(scene.tracker.hasActiveNodes)
        node.smoothTransform = "another target"
        assertTrue(scene.tracker.hasActiveNodes)
        node.smoothTransform = null
        assertFalse(scene.tracker.hasActiveNodes)
        scene.assertExact()
    }

    @Test
    fun `a provider whose answer changes with no notification`() {
        val scene = FakeScene()
        val node = FakeNode("physics")
        scene.roots = listOf(node)

        var asleep = false
        val remove = node.addProvider { !asleep }
        assertTrue("a body in flight", scene.tracker.hasActiveNodes)
        asleep = true
        assertFalse("settled — nobody told the tracker", scene.tracker.hasActiveNodes)
        asleep = false
        assertTrue("woken by a collision — nobody told the tracker", scene.tracker.hasActiveNodes)
        remove()
        assertFalse(scene.tracker.hasActiveNodes)
        scene.assertExact()
    }

    @Test
    fun `a polled term turning true long after the node was attached`() {
        // The case a cached flag gets wrong: `playingAnimations` filled from outside, a player
        // that starts on its own. The node was idle when attached and nothing reports the change.
        val scene = FakeScene()
        val model = FakeNode("model", Kind.Polled)
        scene.roots = listOf(FakeNode("root").also { model.parent = it })
        assertFalse(scene.tracker.hasActiveNodes)

        model.polledTerm = true
        assertTrue(scene.tracker.hasActiveNodes)
        model.polledTerm = false
        assertFalse(scene.tracker.hasActiveNodes)
    }

    @Test
    fun `an override that turns true long after the node was attached`() {
        for (kind in listOf(Kind.OverrideCallingSuper, Kind.OverrideIgnoringSuper)) {
            val scene = FakeScene()
            val custom = FakeNode("custom", kind)
            scene.roots = listOf(FakeNode("root").also { custom.parent = it })
            assertFalse(scene.tracker.hasActiveNodes)

            custom.overrideTerm = true
            assertTrue("$kind", scene.tracker.hasActiveNodes)
            custom.overrideTerm = false
            assertFalse("$kind", scene.tracker.hasActiveNodes)
        }
    }

    @Test
    fun `an override decides for its whole subtree, as it did under the walk`() {
        val active = FakeNode("active child").also { it.onFrame = {} }

        val callingSuper = FakeScene()
        callingSuper.roots = listOf(FakeNode("custom", Kind.OverrideCallingSuper))
        active.parent = callingSuper.roots.single()
        assertTrue("super sees the child", callingSuper.tracker.hasActiveNodes)
        callingSuper.assertExact()
        active.parent = null

        val ignoringSuper = FakeScene()
        ignoringSuper.roots = listOf(FakeNode("custom", Kind.OverrideIgnoringSuper))
        active.parent = ignoringSuper.roots.single()
        assertFalse(
            "the walk never reached this child either: the override hides it",
            ignoringSuper.tracker.hasActiveNodes
        )
        ignoringSuper.assertExact()
    }

    // ---- Each way a node enters or leaves the scene ----

    @Test
    fun `detaching a subtree whose root is idle but whose child is active`() {
        val scene = FakeScene()
        val root = FakeNode("root")
        val idleBranch = FakeNode("idle branch").also { it.parent = root }
        FakeNode("active leaf").also { it.parent = idleBranch; it.onFrame = {} }
        scene.roots = listOf(root)
        assertTrue(scene.tracker.hasActiveNodes)

        idleBranch.parent = null
        assertFalse("the active leaf left with its branch", scene.tracker.hasActiveNodes)
        scene.assertExact()
    }

    @Test
    fun `attaching a subtree that already holds an active descendant`() {
        val scene = FakeScene()
        val root = FakeNode("root")
        scene.roots = listOf(root)

        val branch = FakeNode("branch")
        val middle = FakeNode("middle").also { it.parent = branch }
        FakeNode("leaf").also { it.parent = middle; it.smoothTransform = "target" }
        assertFalse(scene.tracker.hasActiveNodes)

        branch.parent = root
        assertTrue("became active before anyone was listening", scene.tracker.hasActiveNodes)
        scene.assertExact()
    }

    @Test
    fun `a term set while detached is seen on attach, and one cleared while detached is not`() {
        val scene = FakeScene()
        val root = FakeNode("root")
        val node = FakeNode("node").also { it.parent = root; it.onFrame = {} }
        scene.roots = listOf(root)

        node.parent = null
        node.onFrame = null
        node.parent = root
        assertFalse(scene.tracker.hasActiveNodes)

        node.parent = null
        node.onFrame = {}
        node.parent = root
        assertTrue(scene.tracker.hasActiveNodes)
        scene.assertExact()
    }

    @Test
    fun `assigning childNodes directly moves a child between two parents`() {
        // `b.childNodes = setOf(x)` records x under b first, and only then removes it from a.
        val scene = FakeScene()
        val a = FakeNode("a")
        val b = FakeNode("b")
        val x = FakeNode("x").also { it.onFrame = {} }
        scene.roots = listOf(a, b)
        a.childNodes = setOf(x)
        assertTrue(scene.tracker.hasActiveNodes)

        b.childNodes = setOf(x)
        assertTrue("still in the scene, under b", scene.tracker.hasActiveNodes)
        scene.assertExact()

        b.childNodes = emptySet()
        assertFalse(scene.tracker.hasActiveNodes)
        scene.assertExact()
    }

    @Test
    fun `a root reparented under another root and taken out again is still a root`() {
        val scene = FakeScene()
        val first = FakeNode("first")
        val second = FakeNode("second").also { it.onFrame = {} }
        scene.roots = listOf(first, second)

        second.parent = first
        assertTrue(scene.tracker.hasActiveNodes)
        second.parent = null
        assertTrue("it never left the scene's root list", scene.tracker.hasActiveNodes)
        scene.assertExact()

        // And the other way round: taken off the root list while still a child of a root.
        second.parent = first
        scene.roots = listOf(first)
        assertTrue("still in the scene, as a child", scene.tracker.hasActiveNodes)
        scene.assertExact()
        second.parent = null
        assertFalse(scene.tracker.hasActiveNodes)
        scene.assertExact()
    }

    @Test
    fun `destroying a node takes its whole subtree out`() {
        val scene = FakeScene()
        val root = FakeNode("root")
        val branch = FakeNode("branch").also { it.parent = root }
        FakeNode("leaf").also { it.parent = branch; it.onFrame = {} }
        FakeNode("model", Kind.Polled).also { it.parent = branch; it.polledTerm = true }
        scene.roots = listOf(root)
        assertTrue(scene.tracker.hasActiveNodes)

        branch.destroy()
        assertFalse(scene.tracker.hasActiveNodes)
        scene.assertExact()
    }

    @Test
    fun `a node moved from one scene to another, in either order`() {
        val node = FakeNode("node").also { it.onFrame = {} }

        // Added to the new scene before the old one lets go: for a moment it is in both.
        val a = FakeScene()
        val b = FakeScene()
        a.roots = listOf(node)
        b.roots = listOf(node)
        assertTrue("still listed by the first scene", a.tracker.hasActiveNodes)
        assertTrue(b.tracker.hasActiveNodes)
        a.roots = emptyList()
        assertFalse(a.tracker.hasActiveNodes)
        assertTrue("the first scene letting go must not take it from the second", b.tracker.hasActiveNodes)
        a.assertExact()
        b.assertExact()

        // Removed first, added second.
        b.roots = emptyList()
        assertTrue(node.trackers.isEmpty())
        a.roots = listOf(node)
        assertTrue(a.tracker.hasActiveNodes)
        assertFalse(b.tracker.hasActiveNodes)

        // A term toggled while in two scenes reaches both.
        b.roots = listOf(node)
        node.onFrame = null
        assertFalse(a.tracker.hasActiveNodes)
        assertFalse(b.tracker.hasActiveNodes)
        a.assertExact()
        b.assertExact()
    }

    @Test
    fun `a restarted effect that forgot its previous list leaves no stale root`() {
        // The scene's collector keeps a local "previous nodes" list that restarts empty when its
        // effect restarts. The tracker diffs against what it holds, not against that list.
        val scene = FakeScene()
        val old = FakeNode("old").also { it.onFrame = {} }
        scene.roots = listOf(old)

        scene.roots = listOf(FakeNode("new")) // no explicit removal of `old`
        assertFalse("`old` must not pin the scene awake forever", scene.tracker.hasActiveNodes)
        assertTrue(old.trackers.isEmpty())
        scene.assertExact()
    }

    @Test
    fun `clearing the roots lets go of every node`() {
        val scene = FakeScene()
        val root = FakeNode("root")
        val nodes = List(20) { index ->
            FakeNode("n$index", Kind.entries[index % Kind.entries.size]).also { it.parent = root }
        }
        scene.roots = listOf(root)

        scene.roots = emptyList()
        assertEquals(0, scene.tracker.trackedCount)
        assertEquals(0, scene.tracker.candidateCount)
        assertTrue((nodes + root).all { it.trackers.isEmpty() })
    }

    // ---- Things going wrong half-way ----

    @Test
    fun `a child hook that throws half-way through an attach does not lose the child`() {
        val scene = FakeScene()
        val root = FakeNode("root")
        scene.roots = listOf(root)
        val child = FakeNode("child").also { it.onFrame = {}; it.failNextParentWrite = true }

        // `Node.childNodes` writes the field, then sets each child's parent — which can throw.
        // The field is what the walk reads, so the child counts from the write onwards.
        assertTrue(runCatching { root.childNodes = setOf(child) }.isFailure)
        assertTrue(child in root.childNodes)
        scene.assertExact("after a failed attach")
        assertTrue(scene.tracker.hasActiveNodes)
    }

    @Test
    fun `a child hook that throws half-way through a detach is still consistent`() {
        val scene = FakeScene()
        val root = FakeNode("root")
        val child = FakeNode("child").also { it.parent = root; it.onFrame = {} }
        scene.roots = listOf(root)

        child.failNextParentWrite = true
        assertTrue(runCatching { root.childNodes = emptySet() }.isFailure)
        scene.assertExact("after a failed detach")
        assertFalse(scene.tracker.hasActiveNodes)
    }

    @Test
    fun `a getter that mutates the tree while it is being asked`() {
        val scene = FakeScene()
        val root = FakeNode("root")
        val mover = FakeNode("mover").also { it.parent = root }
        val others = List(8) { FakeNode("other$it", Kind.Polled).also { node -> node.parent = root } }
        scene.roots = listOf(root)

        // Re-entrant: evaluating this provider detaches half the candidates and adds new ones.
        var fired = false
        mover.addProvider {
            if (!fired) {
                fired = true
                others.take(4).forEach { it.parent = null }
                FakeNode("late", Kind.Polled).also { it.parent = root; it.polledTerm = true }
            }
            false
        }

        assertFalse(
            "asked over the set as it was when the question started; must not throw",
            scene.tracker.hasActiveNodes
        )
        scene.assertExact("after a re-entrant mutation")
        assertTrue(scene.tracker.hasActiveNodes)
    }

    // ---- Cost ----

    @Test
    fun `the cost does not grow with the number of idle nodes`() {
        fun readsFor(idleNodes: Int): Pair<Int, Int> {
            val scene = FakeScene()
            val root = FakeNode("root")
            val all = ArrayList<FakeNode>()
            // A tree, not a flat list: ten branches, the rest spread underneath.
            val branches = List(10) { FakeNode("branch$it").also { b -> b.parent = root } }
            repeat(idleNodes) { index ->
                all += FakeNode("idle$index").also { it.parent = branches[index % branches.size] }
            }
            // Three node types that always have to be asked, all idle right now.
            val polled = List(3) { FakeNode("model$it", Kind.Polled).also { m -> m.parent = branches[it] } }
            scene.roots = listOf(root)
            (all + branches + polled + root).forEach { it.selfReads = 0; it.fullReads = 0 }

            assertFalse(scene.tracker.hasActiveNodes)

            val nodes = all + branches + polled + root
            return nodes.sumOf { it.selfReads } to nodes.sumOf { it.fullReads }
        }

        val small = readsFor(idleNodes = 10)
        val large = readsFor(idleNodes = 10_000)
        assertEquals("only the three polled nodes are asked, and only for themselves", 3 to 0, small)
        assertEquals("ten thousand idle nodes later, the same three", small, large)

        // The walk this replaces, for scale: every node, every tick.
        val scene = FakeScene()
        val root = FakeNode("root")
        val idle = List(10_000) { FakeNode("idle$it").also { n -> n.parent = root } }
        scene.roots = listOf(root)
        assertFalse(scene.walk())
        assertEquals(10_001, (idle + root).sumOf { it.fullReads })
    }

    @Test
    fun `a node stops being asked once its last reported term is cleared`() {
        val scene = FakeScene()
        val node = FakeNode("node")
        scene.roots = listOf(node)
        assertEquals(0, scene.tracker.candidateCount)

        node.onFrame = {}
        node.smoothTransform = "target"
        assertEquals(1, scene.tracker.candidateCount)
        node.onFrame = null
        assertEquals("the glide is still running", 1, scene.tracker.candidateCount)
        node.smoothTransform = null
        assertEquals(0, scene.tracker.candidateCount)
    }

    // ---- Everything at once ----

    @Test
    fun `random add, remove, reparent, destroy and toggle sequences match the tree walk`() {
        for (seed in SEEDS) {
            val random = Random(seed)
            val scenes = listOf(FakeScene(), FakeScene())
            var created = 0
            fun newNode() = FakeNode("s$seed-n${created++}", Kind.entries[random.nextInt(Kind.entries.size)])
            val pool = MutableList(POOL_SIZE) { newNode() }
            val providerRemovers = ArrayList<() -> Unit>()
            val providerAnswers = ArrayList<BooleanArray>()

            repeat(STEPS) { step ->
                val node = pool[random.nextInt(pool.size)]
                val other = pool[random.nextInt(pool.size)]
                val op = random.nextInt(14)
                when (op) {
                    // Reparent through the `parent` setter (old parent reports first).
                    0, 1 -> if (!other.isSelfOrDescendantOf(node)) node.parent = other
                    2 -> node.parent = null
                    // Reparent through `childNodes` (new parent reports first), several at once.
                    3 -> {
                        val children = pool.shuffled(random).take(random.nextInt(4))
                            .filter { !node.isSelfOrDescendantOf(it) }
                        node.childNodes = children.toSet()
                    }
                    // The scene's root list is replaced wholesale, as the collector does.
                    4 -> scenes[random.nextInt(scenes.size)].roots =
                        pool.shuffled(random).take(random.nextInt(5))
                    5 -> node.onFrame = if (node.onFrame == null) ({}) else null
                    6 -> node.smoothTransform = if (node.smoothTransform == null) "target" else null
                    7 -> node.polledTerm = !node.polledTerm
                    8 -> node.overrideTerm = !node.overrideTerm
                    9 -> {
                        val answer = booleanArrayOf(random.nextBoolean())
                        providerAnswers += answer
                        providerRemovers += node.addProvider { answer[0] }
                    }
                    10 -> if (providerRemovers.isNotEmpty()) {
                        val index = random.nextInt(providerRemovers.size)
                        providerRemovers.removeAt(index)()
                        providerAnswers.removeAt(index)
                    }
                    // A provider's answer flips with no notification (a body falls asleep).
                    11 -> if (providerAnswers.isNotEmpty()) {
                        val answer = providerAnswers[random.nextInt(providerAnswers.size)]
                        answer[0] = !answer[0]
                    }
                    // Destroyed: the subtree comes apart, and a fresh node takes its place.
                    12 -> {
                        node.destroy()
                        pool[pool.indexOf(node)] = newNode()
                    }
                    // Destroyed while still on a scene's root list.
                    else -> node.destroy()
                }
                scenes.forEachIndexed { index, scene ->
                    scene.assertExact("(seed $seed, step $step, op $op, scene $index)")
                }
            }

            scenes.forEach { it.roots = emptyList() }
            scenes.forEach { assertEquals(0, it.tracker.trackedCount) }
            assertTrue("seed $seed left a tracker on a node", pool.all { it.trackers.isEmpty() })
        }
    }

    private companion object {
        val SEEDS = longArrayOf(3724, 1, 2, 3, 0x5CE9E)
        const val POOL_SIZE = 24
        const val STEPS = 4_000
    }
}
