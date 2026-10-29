package io.github.sceneview.demo.demos.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The work of one replay's object picker, in a scope of its own under [parent]'s (#4696): the view
 * that draws a pick comes and goes — the palette, the replay, the session or the orbit changes —
 * while a tap's region is still being grown off the main thread. [cancel] ends everything this
 * picker started, and an answer that comes back after it is never shown: the nodes it would be
 * uploaded into have had their Filament resources destroyed.
 *
 * [parent] is the composition's scope, on the main thread: [show] runs there, so Filament is only
 * ever called from it.
 */
class ScanTaps(parent: CoroutineScope) {
    private val job = SupervisorJob(parent.coroutineContext[Job])

    /** Where this picker's own coroutines run; cancelled by [cancel]. */
    val scope: CoroutineScope = CoroutineScope(parent.coroutineContext + job)

    private var taps = 0

    /** `false` once [cancel] has been called. */
    val isActive: Boolean get() = job.isActive

    /**
     * A tap: [work] finds its answer — however long it takes, even ignoring cancellation, as a
     * CPU-bound pick does — and [show] is handed it, unless a later tap or [cancel] came first.
     */
    fun <T> tap(work: suspend () -> T, show: (T) -> Unit): Job {
        val tap = ++taps
        return scope.launch {
            val answer = work()
            // A later tap answers for this one; a destroyed picker answers nothing.
            if (tap != taps || !job.isActive) return@launch
            show(answer)
        }
    }

    /** A tap answered at once, on the main thread: the one still under way is no longer shown. */
    fun answered() {
        taps++
    }

    /** Ends this picker: what is under way is cancelled, and what finishes anyway is dropped. */
    fun cancel() {
        taps++
        job.cancel()
    }
}
