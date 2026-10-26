package io.github.sceneview

/**
 * How old a fence may be, at least, when it is found reached and still count as fresh: two
 * [BACKEND_IDLE_POLL_MS] polls plus one of slack for a late `Handler` — the backend caught up
 * within about three frames.
 *
 * Measured on the clock, not in polls: a main-thread pass that takes seconds (the next screen's
 * composition queuing its shaders) spaces two polls by seconds, and a fence armed before that
 * pass would otherwise still pass for fresh at the very next poll.
 */
internal const val FRESH_FENCE_MAX_AGE_MS = 3 * BACKEND_IDLE_POLL_MS

/**
 * The freshness window follows the polls actually observed: [FRESH_FENCE_POLL_GAPS] times the gap
 * since the previous poll, never under [FRESH_FENCE_MAX_AGE_MS]. A main thread janking at 15 fps
 * polls every 66 ms; a fixed 48 ms window would find every fence stale there, even on an idle
 * backend, and hold each teardown until [FRESH_FENCE_GIVE_UP_MS].
 *
 * Three gaps, like the three nominal polls of [FRESH_FENCE_MAX_AGE_MS]: a fence armed at one poll
 * is usually found reached at the next (one gap old), sometimes one poll later when the backend was
 * finishing a frame (two gaps), plus one gap of slack because consecutive gaps are uneven when
 * the main thread janks. Two gaps would reject that second case whenever the latest gap is the
 * shorter one, and the probe would re-arm for nothing.
 */
internal const val FRESH_FENCE_POLL_GAPS = 3

/**
 * Upper bound of the freshness window, however far apart the polls are: what the backend may have
 * queued behind a fence that old is what the release's own wait still covers, so the window stays
 * a few frames. A poll held back by a seconds-long main-thread pass is still rejected.
 */
internal const val FRESH_FENCE_MAX_AGE_CAP_MS = 250L

/**
 * After this long, [FreshFenceProbe] accepts any reached fence, fresh or not: a backend that never
 * catches up must not hold a teardown forever. That is the behaviour before the probe existed.
 */
internal const val FRESH_FENCE_GIVE_UP_MS = 5_000L

/** The freshness window for a poll [gapMs] after the previous one; see [FRESH_FENCE_POLL_GAPS]. */
internal fun freshFenceMaxAgeMs(gapMs: Long): Long =
    (FRESH_FENCE_POLL_GAPS * gapMs).coerceIn(FRESH_FENCE_MAX_AGE_MS, FRESH_FENCE_MAX_AGE_CAP_MS)

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
 * monotonic clock: within [maxFreshAgeMs] of the gap since the previous poll (three gaps, between
 * 48 and 250 ms — [freshFenceMaxAgeMs]). The backend then has at most a few frames of work queued
 * behind it. An older one is replaced by a new one, and the probe keeps polling — however far
 * apart the polls are, which the probe does not control (a janking main thread, a slow
 * main-thread pass, or a caller that skips polls while something else holds the teardown).
 *
 * [giveUpMs] runs from the probe's creation, not from its first poll: a caller that polls it only
 * once other teardowns are done still gets its fallback on time. After it any reached fence counts,
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
    private val maxFreshAgeMs: (gapMs: Long) -> Long = ::freshFenceMaxAgeMs,
    private val giveUpMs: Long = FRESH_FENCE_GIVE_UP_MS,
) {
    private var fence: F? = null
    private var armedAt = 0L
    private val startedAt = now()
    private var lastPollAt = startedAt

    /**
     * `true` while the backend has not recently caught up with what is queued on it. Never waits.
     * `false` when no fence can be created: the caller then releases at once, as it did before.
     */
    fun isBusy(): Boolean {
        val at = now()
        val gap = at - lastPollAt
        lastPollAt = at
        val current = fence ?: arm(at) ?: return false
        if (!isReached(current)) return true
        if (at - armedAt <= maxFreshAgeMs(gap) || at - startedAt >= giveUpMs) return false
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
