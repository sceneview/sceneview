package io.github.sceneview.node

import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Which node shows each model instance (#5272). Two live nodes on one instance share its entities:
 * both write their transform to the same root, and the model is drawn where neither expects it (a
 * "ghost"). One instance per node is the rule; this only reports a breach.
 *
 * Weak on both sides: it never keeps an instance or a node alive. Main thread only, like the nodes.
 */
internal class ModelInstanceOwners<I : Any, N : Any>(private val isLive: (N) -> Boolean) {
    private val owners = WeakHashMap<I, MutableList<WeakReference<N>>>()

    /** Records that [node] shows [instance]. */
    fun claim(instance: I, node: N) {
        val list = owners.getOrPut(instance) { ArrayList(1) }
        list.removeAll { it.get().let { held -> held == null || held === node || !isLive(held) } }
        list += WeakReference(node)
    }

    /**
     * The other live nodes that show [instance] besides [node]. Asked once the frame that created
     * [node] is over: a node that replaces another (a recomposition that re-creates the wrapper)
     * is created before the old one is destroyed, and must not count it.
     */
    fun othersThan(instance: I, node: N): List<N> =
        owners[instance].orEmpty().mapNotNull { it.get() }.filter { it !== node && isLive(it) }
}
