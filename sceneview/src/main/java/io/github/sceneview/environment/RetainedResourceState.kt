package io.github.sceneview.environment

/**
 * Main-thread-confined state for an asynchronously replaced resource.
 *
 * A request never clears [value]. Its result becomes visible only when [complete] receives a
 * non-null resource for the latest request. Results from superseded requests are released. A
 * successfully replaced value is retired until [releaseRetired] confirms that consumers have had
 * time to apply the new one.
 */
internal class RetainedResourceState<T>(
    private val release: (T) -> Unit,
    private val onValueChanged: (T?) -> Unit = {},
) {
    private var requestId = 0L
    private val completedRequests = mutableSetOf<Long>()
    private val retiredResources = mutableListOf<T>()

    var value: T? = null
        private set

    fun beginRequest(): Long = ++requestId

    /** Returns true only when [resource] became the visible value for the latest request. */
    fun complete(request: Long, resource: T?): Boolean {
        if (!completedRequests.add(request)) return false
        if (request != requestId) {
            resource?.let(release)
            return false
        }
        if (resource == null) return false
        if (resource === value) return true

        val previous = value
        value = resource
        onValueChanged(resource)
        previous?.let(retiredResources::add)
        return true
    }

    /** Releases resources replaced by successful requests. Safe to call repeatedly. */
    fun releaseRetired() {
        retiredResources.forEach(release)
        retiredResources.clear()
    }

    /** Invalidates every in-flight request and releases the currently visible resource once. */
    fun clear() {
        requestId++
        value?.let(release)
        value = null
        onValueChanged(null)
        releaseRetired()
        completedRequests.clear()
    }
}
