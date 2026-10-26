package io.github.sceneview

/**
 * How old a fence may be when it is found reached and still count as fresh: two
 * [BACKEND_IDLE_POLL_MS] polls plus one of slack for a late `Handler` — the backend caught up
 * within about three frames.
 *
 * Measured on the clock, not in polls: a main-thread pass that takes seconds (the next screen's
 * composition queuing its shaders) spaces two polls by seconds, and a fence armed before that
 * pass would otherwise still pass for fresh at the very next poll.
 */
internal const val FRESH_FENCE_MAX_AGE_MS = 3 * BACKEND_IDLE_POLL_MS

/**
 * After this long, [FreshFenceProbe] accepts any reached fence, fresh or not: a backend that never
 * catches up must not hold a teardown forever. That is the behaviour before the probe existed.
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
 * So a reached fence only counts when it is found reached at most [maxFreshAgeMs] after it was
 * created, on [now]'s monotonic clock: the backend then has at most a few frames of work queued
 * behind it. An older one is replaced by a new one, and the probe keeps polling — however far
 * apart the polls are, which the probe does not control (a slow main-thread pass, or a caller
 * that skips polls while something else holds the teardown). After [giveUpMs] any reached fence counts,
 * so a backend that never catches up gets the previous behaviour, never a leak.
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
    private var startedAt = -1L

    /**
     * `true` while the backend has not recently caught up with what is queued on it. Never waits.
     * `false` when no fence can be created: the caller then releases at once, as it did before.
     */
    fun isBusy(): Boolean {
        val at = now()
        if (startedAt < 0) startedAt = at
        val current = fence ?: arm(at) ?: return false
        if (!isReached(current)) return true
        if (at - armedAt <= maxFreshAgeMs || at - startedAt >= giveUpMs) return false
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
}
