package io.github.sceneview

/**
 * How old a fence may be when it is found reached and still count as fresh: two
 * [BACKEND_IDLE_POLL_MS] polls plus one of slack for a late `Handler`.
 *
 * This bounds main-thread time, not backend work: a fresh fence was reached at most this long after
 * it was created, so what can still be queued behind it is what the main thread queued in those
 * 48 ms. That is usually a frame or two, but a single expensive command — a shader compilation —
 * queued in that window still costs its full price when the release waits. The window narrows the
 * chance of a blocking release; it does not rule it out.
 *
 * Fixed, and measured on the clock rather than in polls. A window that widened with the observed
 * gap between polls let a fence armed before a long main-thread pass — the next screen composing
 * and queuing its shaders — pass for fresh at the poll right after it, whenever the pass was not
 * much longer than the usual gap. So when the main thread polls less often than every 48 ms (a
 * steady 15 fps jank), no fence is ever fresh and teardowns wait for [FRESH_FENCE_GIVE_UP_MS]:
 * a late release, rather than a release that blocks.
 */
internal const val FRESH_FENCE_MAX_AGE_MS = 3 * BACKEND_IDLE_POLL_MS

/**
 * After this long, counted from the probe's first poll, [FreshFenceProbe] accepts any reached
 * fence, fresh or not: a backend that never catches up must not hold a teardown forever. That is
 * the behaviour before the probe existed.
 */
internal const val FRESH_FENCE_GIVE_UP_MS = 5_000L

/**
 * Answers, poll after poll, "would a release that waits for everything queued on the backend
 * return at once right now?" for a teardown deferred by `Engine.whenBackendIdle`.
 *
 * A fence created when the teardown was deferred only says the backend executed what was queued
 * *before* it. `Engine.destroyRenderer` and gltfio's `asyncCancelLoad` wait for everything queued
 * *when they run* — on a shared engine, the next screen's shader compilations queued in between.
 * Releasing as soon as that first fence is reached moved the wait instead of removing it: the
 * release still blocked the main thread, now from a `Handler` callback.
 *
 * So a reached fence only counts when it is found reached soon after it was created, on [now]'s
 * monotonic clock: within [maxFreshAgeMs], 48 ms ([FRESH_FENCE_MAX_AGE_MS]). Behind it there is
 * then at most what the main thread queued in that time. An older one is replaced by a new one and
 * the probe keeps polling; how far apart the polls are is not up to the probe (a janking main
 * thread, a slow main-thread pass, or a caller that skips polls while something else holds the
 * teardown). Polls steadily more than 48 ms apart never find a fresh fence, and only [giveUpMs]
 * ends them.
 *
 * [giveUpMs] runs from the probe's first poll, not from its creation: a caller that only starts
 * polling once something else has settled (a model load, which may take seconds) still gets the
 * freshness check for that long, rather than a fallback already expired. After it any reached
 * fence counts, so a backend that never catches up gets the previous behaviour, never a leak.
 *
 * Filament-free so the ordering can be pinned in a JVM test; `Engine.whenBackendIdle` binds it to
 * Filament fences. Main thread only.
 */
internal class FreshFenceProbe<F : Any>(
    private val createFence: () -> F?,
    private val isReached: (F) -> Boolean,
    private val destroyFence: (F) -> Unit,
    private val now: () -> Long,
    private val maxFreshAgeMs: Long = FRESH_FENCE_MAX_AGE_MS,
    private val giveUpMs: Long = FRESH_FENCE_GIVE_UP_MS,
) {
    private var fence: F? = null
    private var armedAt = 0L
    private var firstPollAt = NOT_POLLED

    /**
     * `true` while the backend has not recently caught up with what is queued on it. Never waits.
     * `false` when no fence can be created: the caller then releases at once, as it did before.
     */
    fun isBusy(): Boolean {
        val at = now()
        if (firstPollAt == NOT_POLLED) firstPollAt = at
        val current = fence ?: arm(at) ?: return false
        if (!isReached(current)) return true
        if (at - armedAt <= maxFreshAgeMs || at - firstPollAt >= giveUpMs) return false
        // Reached, but found so long after it was queued that the backend may have taken on
        // more since: that is still ahead of the release. Measure again from here.
        release()
        return arm(at) != null
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
