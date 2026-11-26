package io.github.sceneview.loaders

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

/**
 * The model loads running in this process, so that a load which runs out of Java heap can wait
 * for the ones already abandoned before it tries again (#5271).
 *
 * A cancelled load stops at its next suspension point. A step that does not suspend (a JSON
 * parse, a GLB rewrite on `Dispatchers.Default`) still runs to its end first, and holds the whole
 * file meanwhile. On a rotation that is the old screen's load, next to the new screen's own copy:
 * on a 16 MiB heap, two copies of a 5.8 MB model do not fit.
 */
internal object ModelLoadsInFlight {
    private class Entry(val job: Job?) {
        val done = CompletableDeferred<Unit>()
    }

    private val entries: MutableSet<Entry> = Collections.synchronizedSet(LinkedHashSet())

    /** Runs one load, registered as in flight until [block] returns or throws. */
    suspend fun <T> track(block: suspend () -> T): T {
        val entry = Entry(currentCoroutineContext()[Job])
        entries += entry
        try {
            return block()
        } finally {
            entries -= entry
            entry.done.complete(Unit)
        }
    }

    /** How many loads are cancelled but still running (their file still held). */
    val abandonedCount: Int get() = synchronized(entries) { entries.count { it.job?.isCancelled == true } }

    /**
     * Waits up to [timeoutMillis] for every load that was cancelled but is still running to end.
     * `true` when none is left.
     */
    suspend fun awaitAbandoned(timeoutMillis: Long): Boolean {
        val abandoned = synchronized(entries) { entries.filter { it.job?.isCancelled == true } }
        if (abandoned.isEmpty()) return true
        return withTimeoutOrNull(timeoutMillis) { abandoned.forEach { it.done.await() } } != null
    }
}
