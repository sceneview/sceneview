package io.github.sceneview.environment

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos

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
    private val retiredResources = mutableListOf<T>()

    var value: T? = null
        private set

    fun beginRequest(): Long = ++requestId

    /** Returns true only when [resource] became the visible value for the latest request. */
    fun complete(request: Long, resource: T?): Boolean {
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
    }
}

/** Frames a replaced resource stays alive after its replacement was published. */
private const val RETIRE_AFTER_FRAMES = 2

/**
 * The Compose wiring around [RetainedResourceState]: remembers the last resource [load] produced
 * and keeps it while a later request loads.
 *
 * [load] runs again whenever [owner] or one of [keys] changes; a request still in flight is
 * cancelled, so the latest one wins. The value it replaces is released [RETIRE_AFTER_FRAMES]
 * frames after the swap, and everything still held — the visible value and any retired one — is
 * released exactly once when the composable leaves the composition or [owner] changes (#2458).
 *
 * [release] is captured once per [owner]: pass a function bound to it.
 */
@Composable
internal fun <T : Any> rememberRetainedResource(
    owner: Any,
    vararg keys: Any?,
    release: (T) -> Unit,
    load: suspend () -> T?,
): T? {
    val current = remember(owner) { mutableStateOf<T?>(null) }
    val state = remember(owner) { RetainedResourceState(release) { current.value = it } }
    LaunchedEffect(owner, *keys) {
        val request = state.beginRequest()
        state.complete(request, load())
    }
    LaunchedEffect(current.value) {
        // Keep the replaced resource alive until its consumer has observed the new value and
        // presented it. Two frame boundaries avoid destroying the old one in the apply phase
        // that schedules the swap.
        repeat(RETIRE_AFTER_FRAMES) { withFrameNanos { } }
        state.releaseRetired()
    }
    DisposableEffect(state) {
        // Compose applies disposal and launches effects on Main, which is also Filament's JNI
        // owner thread. clear() invalidates a result that finishes while this leaves composition.
        onDispose(state::clear)
    }
    return current.value
}
