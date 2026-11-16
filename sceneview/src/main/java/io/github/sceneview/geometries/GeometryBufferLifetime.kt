package io.github.sceneview.geometries

/**
 * Filament-free bookkeeping of who still draws from a [Geometry]'s buffers.
 *
 * A buffer pair of type [B] is released the moment no tracked renderable references it any more,
 * never on a later frame:
 * - a pair replaced by a topology change is [retire]d, then released by [rebind] right after every
 *   consumer has been re-pointed at the replacement;
 * - the live pair is released by [destroy] once the last consumer has [detach]ed — a node destroys
 *   its renderable before it detaches, so nothing draws from it at that point.
 *
 * Nothing here waits for a rendered frame, so buffers are freed just the same when no view is
 * rendering (a shared engine whose scene was disposed) and nothing is left queued against an
 * engine torn down with a raw `Engine.destroy()`.
 *
 * Extracted from [Geometry] so the contract can be pinned by a pure-JVM test: `VertexBuffer` and
 * `IndexBuffer` cannot be created without the native library. Main-thread only, like [Geometry].
 */
internal class GeometryBufferLifetime<B> {
    private val consumers = LinkedHashSet<Geometry.Consumer>()
    private val retired = ArrayList<B>()

    var isDestroyed = false
        private set

    val consumerCount: Int get() = consumers.size
    val retiredCount: Int get() = retired.size

    fun attach(consumer: Geometry.Consumer) {
        check(!isDestroyed) { "Geometry has been destroyed" }
        consumers += consumer
    }

    fun detach(consumer: Geometry.Consumer) {
        consumers -= consumer
    }

    /** Lets every consumer veto a primitive layout before any buffer is touched. */
    fun validate(primitiveCount: Int) = consumers.forEach { it.validate(primitiveCount) }

    /** Marks [buffers] as replaced: still referenced by consumers until the next [rebind]. */
    fun retire(buffers: B) {
        retired += buffers
    }

    /**
     * Re-points every consumer at the current buffers, then releases the retired ones.
     *
     * A consumer that throws leaves the retired buffers alive — a consumer after it in the set may
     * still draw from them — and the next [rebind] retries.
     */
    fun rebind(release: (B) -> Unit) {
        consumers.toList().forEach { it.rebind() }
        releaseRetired(release)
    }

    /**
     * Releases [current] and anything still retired, unless a consumer is still attached.
     *
     * @return `true` if the buffers were released by this call; `false` when a consumer still
     * references them or they were already released.
     */
    fun destroy(current: B, release: (B) -> Unit): Boolean {
        if (isDestroyed || consumers.isNotEmpty()) return false
        isDestroyed = true
        releaseRetired(release)
        release(current)
        return true
    }

    private fun releaseRetired(release: (B) -> Unit) {
        // Emptied first: a buffer must never be handed to `release` twice, even if it throws.
        val pending = retired.toList()
        retired.clear()
        pending.forEach(release)
    }
}
