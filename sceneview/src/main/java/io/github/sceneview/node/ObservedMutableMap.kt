package io.github.sceneview.node

/**
 * A read-and-write view of [storage] that calls [onEntryAdded] after every entry put into it.
 *
 * `ModelNode.playingAnimations` is a public mutable map, and a write to it used to be found by
 * the node on its next frame, because the node was ticked on every frame. A model at rest is not
 * ticked any more (#4451), so the write that starts an animation has to say so itself.
 *
 * Only additions are reported, and every addition is: `putAll`, `putIfAbsent`, `compute`, `merge`
 * and the rest are the [java.util.AbstractMap] and [java.util.Map] defaults, which all end in
 * [put], and the key, value and entry views of a map cannot add. Removals go unreported on
 * purpose — the node notices an empty map at the end of the frame it is already running.
 *
 * @param owner The object this view reports to, so a view handed back to its owner is kept as is.
 */
internal class ObservedMutableMap<K, V>(
    val owner: Any,
    private val storage: MutableMap<K, V>,
    private val onEntryAdded: () -> Unit
) : AbstractMutableMap<K, V>() {

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>> get() = storage.entries

    override val size: Int get() = storage.size

    override fun put(key: K, value: V): V? {
        val previous = storage.put(key, value)
        onEntryAdded()
        return previous
    }

    // The rest only skips AbstractMap's linear scans of `entries`.
    override fun get(key: K): V? = storage[key]
    override fun containsKey(key: K): Boolean = storage.containsKey(key)
    override fun remove(key: K): V? = storage.remove(key)
    override fun clear() = storage.clear()
    override fun isEmpty(): Boolean = storage.isEmpty()
}

/**
 * This map seen through an [ObservedMutableMap] reporting to [owner] — itself, when it already is
 * that view.
 */
internal fun <K, V> MutableMap<K, V>.observedBy(
    owner: Any,
    onEntryAdded: () -> Unit
): MutableMap<K, V> =
    (this as? ObservedMutableMap<K, V>)?.takeIf { it.owner === owner }
        ?: ObservedMutableMap(owner = owner, storage = this, onEntryAdded = onEntryAdded)
