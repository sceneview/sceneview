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
 * caller could throw from. A node is tracked while it is one of the scene's [setRoots] or sits in
 * the `childNodes` of a tracked transparent node — both are remembered separately, so a root
 * re-parented under another root and taken out again is still a root.
 *
 * "A parent" is plural on purpose. A node has one `parent`, but it can sit in two nodes'
 * `childNodes` at once: for an instant on every move made by assigning `childNodes`, and for good
 * when a hook throws half-way through one — the write to the new parent's field has happened, the
 * removal from the old one never runs. The walk reaches that node through either, so every
 * tracked parent holding it is recorded and it is let go only when the last one drops it.
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
     * Every tracked node, mapped to the tracked transparent nodes whose `childNodes` hold it right
     * now: one in the normal case, none for a node tracked as a root only.
     */
    private val tracked = IdentityHashMap<T, ArrayList<T>>()

    /** The tracked nodes that are asked. Value: whether the node is opaque. */
    private val candidates = IdentityHashMap<T, Boolean>()

    /** [candidates] flattened for the per-tick read; `null` after any change to the set. */
    @Volatile
    private var snapshot: Snapshot? = null

    private class Snapshot(val opaque: Array<Any?>, val transparent: Array<Any?>)

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
                if (tracked[root]?.isEmpty() == true) untrack(root)
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
            removed.forEach { child -> release(child, from = parent) }
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
        val parents = tracked[node]
        if (parents != null) {
            // Already here as a root, or under another parent. Its subtree is tracked: only the
            // new reason to keep it is recorded.
            if (via != null && parents.none { it === via }) parents += via
            return
        }
        tracked[node] = ArrayList<T>(1).also { if (via != null) it += via }
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
        access.children(node).forEach { child -> release(child, from = node) }
    }

    /** [from] no longer holds [child]: forget that reason, and the child with it if it was the last. */
    private fun release(child: T, from: T) {
        val parents = tracked[child] ?: return
        val index = parents.indexOfFirst { it === from }
        if (index < 0) return
        parents.removeAt(index)
        if (parents.isEmpty() && child !in roots) untrack(child)
    }

    private fun buildSnapshot(): Snapshot {
        val opaque = ArrayList<Any?>()
        val transparent = ArrayList<Any?>(candidates.size)
        candidates.forEach { (node, isOpaque) -> (if (isOpaque) opaque else transparent) += node }
        return Snapshot(opaque.toTypedArray(), transparent.toTypedArray()).also { snapshot = it }
    }
}
