package io.github.sceneview

import java.util.IdentityHashMap

/**
 * Answers "is any node of this scene frame-active?" without walking the scene's tree (#3724).
 *
 * The render loop used to ask `roots.any { it.isFrameActive }` once per tick, and that getter
 * recurses into every child: a second full traversal per frame, on the main thread, complete in
 * exactly the case the on-demand loop exists for — a scene where nothing is active.
 *
 * ### Why this is a set of nodes to ask, and not a counter of active nodes
 *
 * A counter (or a cached flag per node) is only exact if every term of `isFrameActive` reports its
 * own transitions, and several cannot: a playing `MediaPlayer`, a surface that just received a
 * buffer, a physics body that fell asleep, a background sort that finished, a camera that moved, a
 * public mutable `playingAnimations` map, and any `isFrameActive` override written outside the
 * library. A cache that misses one of those turning `true` parks a scene that is still animating —
 * a frozen picture.
 *
 * So nothing here caches an answer. The tracker keeps the set of nodes that *could* answer
 * `true` — the **candidates** — and [hasActiveNodes] asks each of them, every time. What it saves
 * is the nodes that cannot: a node with no activity term of its own is never visited, however many
 * of them the scene holds. The set can only be wrong by being too large, which costs a getter call
 * and never a frame.
 *
 * ### Which nodes are candidates
 *
 * - An **opaque** node — its class overrides the public getter ([Access.isOpaque]). Nothing can be
 *   assumed about what that override reads or whether it calls `super`, so the node is always
 *   asked, through that getter, and the tracker does not look underneath it: the getter answers
 *   for its own subtree exactly as it did before.
 * - A **transparent** node — its public getter is the base one, known to be "own terms, or any
 *   child". Those are tracked one by one and asked for their own terms only
 *   ([Access.isSelfFrameActive]), and only while [Access.mayBeSelfFrameActive] says a term exists.
 *
 * ### What keeps the set exact
 *
 * Membership mirrors the `childNodes` field, not the `parent` pointer: [onChildrenChanged] is
 * called from the one place that field is written, straight after the write and before any hook a
 * caller could throw from. A node is tracked while it is one of the scene's [setRoots] or a child
 * of a tracked transparent node — both are remembered separately, so a root re-parented under
 * another root and taken out again is still a root.
 *
 * Generic over the node type so the algorithm runs in plain JVM tests: `Node` needs a Filament
 * engine, the bookkeeping does not.
 */
internal class FrameActivityTracker<T : Any>(private val access: Access<T>) {

    /** Everything the tracker needs to know about a node. See [NodeFrameActivityAccess]. */
    interface Access<T : Any> {
        /** True when the node's class overrides the public `isFrameActive`. Fixed per class. */
        fun isOpaque(node: T): Boolean

        /** The public, recursive answer. Only ever asked of opaque nodes. */
        fun isFrameActive(node: T): Boolean

        /** The node's own terms, children excluded. Only ever asked of transparent nodes. */
        fun isSelfFrameActive(node: T): Boolean

        /**
         * False only when [isSelfFrameActive] is false **and** cannot turn true without the node
         * calling [update]. Must not run caller code: it is read while the tracker is locked.
         */
        fun mayBeSelfFrameActive(node: T): Boolean

        fun children(node: T): Collection<T>

        /** Registers / unregisters this tracker for the node's [update] and [onChildrenChanged]. */
        fun addTracker(node: T, tracker: FrameActivityTracker<T>)
        fun removeTracker(node: T, tracker: FrameActivityTracker<T>)
    }

    private val lock = Any()

    /** The scene's top-level nodes, as last given to [setRoots]. */
    private val roots = IdentityHashMap<T, Unit>()

    /**
     * Every tracked node, mapped to the tracked transparent parent it is currently a child of, or
     * to [NoParent] when it is tracked as a root only.
     */
    private val tracked = IdentityHashMap<T, Any>()

    /** The tracked nodes that are asked. Value: whether the node is opaque. */
    private val candidates = IdentityHashMap<T, Boolean>()

    /** [candidates] flattened for the per-tick read; `null` after any change to the set. */
    @Volatile
    private var snapshot: Snapshot? = null

    private class Snapshot(val opaque: Array<Any?>, val transparent: Array<Any?>)

    private object NoParent

    /**
     * True when any root is frame-active — the same answer as `roots.any { it.isFrameActive }`.
     *
     * Costs one getter call per candidate and nothing per idle node. The getters run outside the
     * lock and over a snapshot, so one that mutates the tree while it is being asked is safe.
     */
    val hasActiveNodes: Boolean
        get() {
            val current = snapshot ?: synchronized(lock) { snapshot ?: buildSnapshot() }
            @Suppress("UNCHECKED_CAST")
            for (node in current.transparent) if (access.isSelfFrameActive(node as T)) return true
            @Suppress("UNCHECKED_CAST")
            for (node in current.opaque) if (access.isFrameActive(node as T)) return true
            return false
        }

    /** Number of nodes [hasActiveNodes] asks. Exposed for the cost tests. */
    val candidateCount: Int get() = synchronized(lock) { candidates.size }

    /** Number of nodes tracked. Exposed so tests can prove nothing is left behind. */
    val trackedCount: Int get() = synchronized(lock) { tracked.size }

    /**
     * Replaces the scene's top-level nodes. Diffs against the previous call itself, so a caller
     * that lost its own "previous list" (a restarted effect) cannot leave a stale root behind.
     */
    fun setRoots(nodes: Collection<T>) {
        synchronized(lock) {
            val next = IdentityHashMap<T, Unit>(nodes.size)
            nodes.forEach { next[it] = Unit }
            roots.keys.filter { it !in next }.forEach { root ->
                roots.remove(root)
                // Still a child of a tracked node: it stays, for that reason alone now.
                if (tracked[root] === NoParent) untrack(root)
            }
            nodes.forEach { root ->
                if (root !in roots) {
                    roots[root] = Unit
                    track(root, via = null)
                }
            }
        }
    }

    /**
     * [parent]'s `childNodes` field was just replaced. Call it right after the write: this is the
     * only thing that keeps a subtree in or out of the set.
     */
    fun onChildrenChanged(parent: T, removed: Collection<T>, added: Collection<T>) {
        synchronized(lock) {
            if (parent !in tracked || access.isOpaque(parent)) return
            removed.forEach { child ->
                // A child moved straight from one parent to another is already recorded under the
                // new one by the time the old one reports the removal — leave that record alone.
                if (tracked[child] === parent) {
                    tracked[child] = NoParent
                    if (child !in roots) untrack(child)
                }
            }
            added.forEach { child -> track(child, via = parent) }
        }
    }

    /** One of [node]'s own terms was set or cleared: re-read whether it has to be asked. */
    fun update(node: T) {
        synchronized(lock) {
            if (node !in tracked || access.isOpaque(node)) return
            if (access.mayBeSelfFrameActive(node)) {
                if (candidates.put(node, false) == null) snapshot = null
            } else {
                if (candidates.remove(node) != null) snapshot = null
            }
        }
    }

    private fun track(node: T, via: T?) {
        if (node in tracked) {
            // Already here as a root, or under another parent a moment ago. Its subtree is tracked.
            if (via != null) tracked[node] = via
            return
        }
        tracked[node] = via ?: NoParent
        access.addTracker(node, this)
        if (access.isOpaque(node)) {
            candidates[node] = true
            snapshot = null
            return
        }
        if (access.mayBeSelfFrameActive(node)) {
            candidates[node] = false
            snapshot = null
        }
        // A subtree attached with active descendants already inside: classify each from what it
        // is now, not from a transition nobody was listening for.
        access.children(node).forEach { child -> track(child, via = node) }
    }

    private fun untrack(node: T) {
        if (tracked.remove(node) == null) return
        access.removeTracker(node, this)
        if (candidates.remove(node) != null) snapshot = null
        if (access.isOpaque(node)) return
        access.children(node).forEach { child ->
            if (tracked[child] === node) {
                tracked[child] = NoParent
                if (child !in roots) untrack(child)
            }
        }
    }

    private fun buildSnapshot(): Snapshot {
        val opaque = ArrayList<Any?>()
        val transparent = ArrayList<Any?>(candidates.size)
        candidates.forEach { (node, isOpaque) -> (if (isOpaque) opaque else transparent) += node }
        return Snapshot(opaque.toTypedArray(), transparent.toTypedArray()).also { snapshot = it }
    }
}
