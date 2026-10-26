package io.github.sceneview

/**
 * How many polls a fence may stay unreached and still count as fresh once it is reached: with
 * [BACKEND_IDLE_POLL_MS] polls, the backend caught up within about three frames.
 */
internal const val FRESH_FENCE_POLLS = 2

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
 * So a reached fence only counts when it was reached within [freshPolls] polls of being created:
 * the backend then has at most a few frames of work queued behind it. A fence reached later is
 * replaced by a new one, and the probe keeps polling. After [giveUpMs] any reached fence counts,
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
    private val freshPolls: Int = FRESH_FENCE_POLLS,
    private val giveUpMs: Long = FRESH_FENCE_GIVE_UP_MS,
) {
    private var fence: F? = null
    private var unreachedPolls = 0
    private var startedAt = -1L

    /**
     * `true` while the backend has not recently caught up with what is queued on it. Never waits.
     * `false` when no fence can be created: the caller then releases at once, as it did before.
     */
    fun isBusy(): Boolean {
        val at = now()
        if (startedAt < 0) startedAt = at
        val current = fence ?: arm() ?: return false
        if (!isReached(current)) {
            unreachedPolls++
            return true
        }
        if (unreachedPolls <= freshPolls || at - startedAt >= giveUpMs) return false
        // Reached, but long after it was queued: what the backend took on since then is still
        // ahead of the release. Measure again from here.
        release()
        return arm() != null
    }

    /** Destroys the fence still held, if any. Call once the probe is no longer polled. */
    fun release() {
        fence?.let { runCatching { destroyFence(it) } }
        fence = null
    }

    private fun arm(): F? = createFence()?.also {
        fence = it
        unreachedPolls = 0
    }
}
