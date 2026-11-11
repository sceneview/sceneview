package io.github.sceneview

/** Maximum age of a reached fence accepted by the heuristic below. */
internal const val FRESH_FENCE_MAX_AGE_MS = 3 * BACKEND_IDLE_POLL_MS

/** Diagnostic threshold only: elapsed time never makes a stale fence safe. */
internal const val FRESH_FENCE_WARN_AFTER_MS = 5_000L

/**
 * Polls for a recently reached fence on the main thread.
 *
 * A reached fence covers commands before it, not commands queued since. Even a fence younger
 * than [maxFreshAgeMs] cannot bound a native destructor's wait. This is a backlog heuristic,
 * not a guarantee that destruction is nonblocking.
 *
 * Polls consistently more than [maxFreshAgeMs] apart keep deferring indefinitely. After
 * [warnAfterMs], measured from the first poll, [onStillBusy] runs once without relaxing the
 * freshness requirement. Missing fences also keep the teardown pending.
 *
 * Filament-free for JVM tests. The owner must release the probe before destroying its engine,
 * or abandon it without calling [release] after a raw engine destruction.
 */
internal class FreshFenceProbe<F : Any>(
    private val createFence: () -> F?,
    private val isReached: (F) -> Boolean,
    private val destroyFence: (F) -> Unit,
    private val now: () -> Long,
    private val maxFreshAgeMs: Long = FRESH_FENCE_MAX_AGE_MS,
    private val warnAfterMs: Long = FRESH_FENCE_WARN_AFTER_MS,
    private val onStillBusy: () -> Unit = {},
) {
    private var fence: F? = null
    private var armedAt = 0L
    private var firstPollAt = NOT_POLLED
    private var warned = false

    /**
     * `true` while the backend has not recently caught up with what is queued on it. Never waits.
     * Failure to create a fence is not evidence that the backend is idle.
     */
    fun isBusy(): Boolean {
        val at = now()
        if (firstPollAt == NOT_POLLED) firstPollAt = at
        if (!warned && at - firstPollAt >= warnAfterMs) {
            warned = true
            onStillBusy()
        }
        val current = fence ?: arm(at) ?: return true
        if (!isReached(current)) return true
        if (at - armedAt <= maxFreshAgeMs) return false
        // Reached, but found so long after it was queued that the backend may have taken on
        // more since: that is still ahead of the release. Measure again from here.
        release()
        arm(at)
        return true
    }

    /** Destroys the fence still held, if any. Call once the probe is no longer polled. */
    fun release() {
        fence?.let { runCatching { destroyFence(it) } }
        fence = null
    }

    private fun arm(at: Long): F? = createFence()?.also {
        fence = it
        armedAt = at
    }

    private companion object {
        const val NOT_POLLED = Long.MIN_VALUE
    }
}
