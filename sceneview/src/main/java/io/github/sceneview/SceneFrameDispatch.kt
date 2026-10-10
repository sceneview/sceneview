package io.github.sceneview

import androidx.annotation.RestrictTo
import io.github.sceneview.node.Node
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands a frame to the nodes of one scene that have something to do with it, without walking the
 * scene's tree on every frame (#4451).
 *
 * The render loop used to call `roots.forEach { it.onFrame(frameTimeNanos) }`, and [Node.onFrame]
 * recurses into every child: one virtual call, one time-step computation and one child iteration
 * per node and per rendered frame, for a tree where most nodes have no per-frame work at all.
 *
 * ### A plan, replayed until the tree changes
 *
 * That walk is still done — once, by [dispatch], and its outcome is kept as a **plan**: the steps
 * the recursive walk would have run, in the order it would have run them, with every node that
 * has nothing to do left out. The plan is replayed on the following frames for as long as it is
 * known to be current, and rebuilt by a new walk when it is not.
 *
 * "Known to be current" is one number, [FrameDispatchEpoch]: every write that can change what a
 * walk would find — a `childNodes` field, an `onFrame` or `internalOnFrame` slot set or cleared, a
 * smooth transform started or finished — bumps it. A plan remembers the epoch it was built under
 * and is thrown away on any other value. Nothing here records which node belongs to which scene,
 * so there is no bookkeeping to get wrong: a change anywhere invalidates every plan, which costs
 * the walk the loop used to do on every frame, and never a missed tick.
 *
 * ### The three kinds of step, and why the order is the walk's own
 *
 * [Node.onFrame] does, for a node `n`: its smooth transform, then its children, then its
 * `internalOnFrame` hook and its `onFrame` callback. So a child reads a parent that has already
 * glided, and a parent's callback reads children that have already ticked. The plan keeps exactly
 * that: [FRAME_STEP_SMOOTH] for `n` where the walk enters it, [FRAME_STEP_CALLBACKS] for `n`
 * where the walk leaves it, and its children's steps in between, in `childNodes` order.
 *
 * ### Classes that override `onFrame`
 *
 * `Node.onFrame` is public and `open`. A class that overrides it — `ModelNode`, `SplatNode`, or
 * any node type of an app — may do anything there, and reaches its own children through
 * `super.onFrame`. Nothing can be assumed about it, so such a node is a single
 * [FRAME_STEP_OVERRIDE] step: its `onFrame` is called on every frame, in full, and the plan
 * does not look underneath it. The whole subtree below it is ticked by the unchanged recursive
 * [Node.onFrame], exactly as before. Whether a class overrides the method is read once per class,
 * by reflection ([overridesOnFrame]); any doubt answers "it does".
 *
 * ### A change made while a frame is being handed out
 *
 * A callback may attach, detach or destroy nodes, or set another node's callback. The steps left
 * in the plan then belong to a tree that has moved on. They are still run, so that no node which
 * was due a tick loses it, except for a node that has since been taken out of its parent (which
 * is also what destroying it does). That is a difference from the recursive walk for one case:
 * the walk iterated the list of children it had read on entering the parent, so a *sibling*
 * detached or destroyed by an earlier sibling's callback was still ticked on that frame, one last
 * time; it no longer is. A node that *gained* per-frame work during the frame — a
 * callback set on it, a glide started, or the node itself attached — gets its first tick on the
 * next one, when the plan is rebuilt. The recursive walk gave that first tick on the same frame
 * when the node happened to come later in the walk, and on the next one otherwise.
 *
 * Plumbing shared with `arsceneview`, not API: do not call it from an app. Main thread only, as
 * `Node.onFrame` is.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
class SceneFrameDispatch {
    private var planRoots: List<Node>? = null
    private var planEpoch = 0
    private var nodes = arrayOfNulls<Node>(0)
    private var kinds = ByteArray(0)
    private var size = 0

    /** Set while [dispatch] runs, so a re-entrant call falls back to the plain walk. */
    private var dispatching = false

    /**
     * Same effect as `roots.forEach { it.onFrame(frameTimeNanos) }`, at the cost of the nodes
     * that have per-frame work instead of the cost of the tree.
     *
     * [roots] is compared **by identity** with the list the plan was built from: the caller must
     * hand a new list instance whenever the set of roots changes, as both scene loops do (they
     * publish a fresh `toList()` snapshot per change). A list mutated in place is not noticed: a
     * root added to it gets no tick until some other write bumps [FrameDispatchEpoch]. Comparing
     * the contents on every frame would be the per-node cost this class exists to remove.
     */
    fun dispatch(roots: List<Node>, frameTimeNanos: Long) {
        if (dispatching) {
            // A callback that pumps the scene from inside a frame: nothing to optimise, and the
            // plan being replayed must not be rebuilt under its own iteration.
            roots.forEach { it.onFrame(frameTimeNanos) }
            return
        }
        val epoch = FrameDispatchEpoch.current
        if (planRoots !== roots || planEpoch != epoch) build(roots, epoch)
        dispatching = true
        try {
            replay(roots, epoch, frameTimeNanos)
        } finally {
            dispatching = false
        }
    }

    /** Lets go of every node. A node kept alive by its owner must not be kept by its old scene. */
    fun clear() {
        planRoots = null
        nodes = arrayOfNulls(0)
        kinds = ByteArray(0)
        size = 0
    }

    /** Number of steps a frame costs right now. Exposed for the cost tests. */
    internal val stepCount: Int get() = size

    /** The current plan, one `kind:node` pair per step. Exposed so tests can check the order. */
    internal fun steps(): List<Pair<Byte, Node>> = (0 until size).map { kinds[it] to nodes[it]!! }

    /** How many times the tree was walked. Exposed so tests can check an idle tree is not. */
    internal var buildCount = 0
        private set

    /** How many nodes the plan's storage references, used or not. Exposed for the leak tests. */
    internal val retainedNodeCount: Int get() = nodes.count { it != null }

    /** Builds the plan for [roots] without ticking anything. Exposed for the order test. */
    internal fun planOnly(roots: List<Node>) = build(roots, FrameDispatchEpoch.current)

    private fun replay(roots: List<Node>, epoch: Int, frameTimeNanos: Long) {
        // Locals: a re-entrant `clear()` swaps the fields, not what this frame reads.
        val nodes = nodes
        val kinds = kinds
        val size = size
        var stale = false
        for (index in 0 until size) {
            val node = nodes[index]
            // Once the tree has changed under this frame, a node that left it is not ticked.
            // The walk would not have reached it either when its parent was entered after the
            // change; it did still tick a sibling removed while their parent was being iterated.
            if (node == null || (stale && !isStillInTree(node, roots))) continue
            when (kinds[index]) {
                FRAME_STEP_OVERRIDE -> node.onFrame(frameTimeNanos)
                FRAME_STEP_SMOOTH -> node.advanceSmoothTransform(frameTimeNanos)
                else -> node.invokeFrameCallbacks(frameTimeNanos)
            }
            if (!stale && FrameDispatchEpoch.current != epoch) stale = true
        }
    }

    /**
     * A planned node is a root or hangs under a planned parent. Without a parent it is either
     * still a root — and a root stays one for the whole frame — or it was detached since.
     * Only asked on a frame whose tree changed mid-way, so the scan of [roots] is not a cost.
     */
    private fun isStillInTree(node: Node, roots: List<Node>): Boolean =
        node.parent != null || roots.any { it === node }

    private fun build(roots: List<Node>, epoch: Int) {
        size = 0
        roots.forEach { plan(it) }
        // Drop what a larger, older plan left behind, so a removed node is not held by the tail.
        nodes.fill(null, size, nodes.size)
        planRoots = roots
        planEpoch = epoch
        buildCount++
    }

    private fun plan(node: Node) {
        if (node.overridesOnFrame) {
            add(FRAME_STEP_OVERRIDE, node)
            return
        }
        if (node.hasSmoothTransform) add(FRAME_STEP_SMOOTH, node)
        node.childNodes.forEach { plan(it) }
        if (node.hasFrameCallbacks) add(FRAME_STEP_CALLBACKS, node)
    }

    private fun add(kind: Byte, node: Node) {
        if (size == nodes.size) {
            val capacity = maxOf(INITIAL_CAPACITY, size * 2)
            nodes = nodes.copyOf(capacity)
            kinds = kinds.copyOf(capacity)
        }
        nodes[size] = node
        kinds[size] = kind
        size++
    }
}

private const val INITIAL_CAPACITY = 16

/** The node's class overrides `onFrame`: call it, in full; it ticks its own subtree. */
internal const val FRAME_STEP_OVERRIDE: Byte = 0

/** The node has a smooth-transform target: advance it. Where the walk enters the node. */
internal const val FRAME_STEP_SMOOTH: Byte = 1

/** The node has an `internalOnFrame` hook or an `onFrame` callback. Where the walk leaves it. */
internal const val FRAME_STEP_CALLBACKS: Byte = 2

/**
 * Bumped by every write that can change what a walk of a node tree finds to do on a frame: see
 * [SceneFrameDispatch]. One counter for the process rather than one per scene — a node does not
 * know which scenes hold it, and a spurious rebuild costs one walk.
 */
internal object FrameDispatchEpoch {
    private val epoch = AtomicInteger()

    val current: Int get() = epoch.get()

    fun bump() {
        epoch.incrementAndGet()
    }
}

private val onFrameOverrides = ConcurrentHashMap<Class<*>, Boolean>()

/** [overridesOnFrame], remembered per class: the reflection runs once for each node type. */
internal fun overridesOnFrameCached(type: Class<*>): Boolean =
    onFrameOverrides[type] ?: overridesOnFrame(type).also { onFrameOverrides[type] = it }

/**
 * True when [type] — a [Node] subclass — overrides the public `onFrame(Long)` instead of
 * inheriting [Node]'s.
 *
 * The method is public and `open`, so an app's node type may override it with anything, and an
 * override is the one thing the plan cannot see into: it is called every frame, in full. Only the
 * class can tell the two apart, hence the reflection — once per class, never per node or per
 * frame.
 *
 * Any doubt answers `true`. A shrinker that renamed the method makes the lookup fail, and failing
 * means "tick the node the old way", which is slower and never wrong.
 */
internal fun overridesOnFrame(type: Class<*>): Boolean = try {
    type.getMethod(ON_FRAME_METHOD, Long::class.javaPrimitiveType).declaringClass != Node::class.java
} catch (_: ReflectiveOperationException) {
    true
} catch (_: LinkageError) {
    true
} catch (_: SecurityException) {
    true
}

private const val ON_FRAME_METHOD = "onFrame"
