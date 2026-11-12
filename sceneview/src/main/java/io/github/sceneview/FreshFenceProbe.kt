package io.github.sceneview

/**
 * Maximum age of a reached fence on a shared engine: two polls plus one of slack for a late
 * Handler. This bounds main-thread queuing time, not backend work. A shader compilation queued
 * in these 48 ms still costs its full price when a native destructor waits for it.
 *
 * Keep this window fixed: widening it with the observed poll gap accepts the very backlog a long
 * main-thread pass just queued. Steady polls more than 48 ms apart cannot satisfy it; an engine
 * retiring after its producers stop instead accepts any reached fence, regardless of its age.
 */
internal const val FRESH_FENCE_MAX_AGE_MS = 3 * BACKEND_IDLE_POLL_MS

/** Diagnostic threshold only: elapsed time never makes a stale fence safe. */
internal const val FRESH_FENCE_WARN_AFTER_MS = 5_000L

/**
 * Polls the backend backlog on the engine's thread, without waiting for a fence.
 *
 * A dispose-time fence covers commands queued before it. `destroyRenderer` and `asyncCancelLoad`
 * wait for everything queued when they run, including a new screen's shaders on a shared engine.
 * Accepting an old fence there merely moves the blocking wait into a Handler callback. A reached
 * fence older than [maxFreshAgeMs] is therefore replaced. Freshness narrows that gap; it cannot
 * bound native destruction, or command submission itself, which can block on a full stream.
 *
 * Once the owner marks the engine RETIRING, its producers must have stopped. [isBusy] can then
 * accept a plain reached fence: main-thread cadence must not retain the engine and EGL context.
 * The owner invalidates the fence when another teardown is registered or runs, so subsequent
 * releases cover that teardown's commands too. Other conditions, such as decoders, still apply.
 *
 * After [warnAfterMs], counted from the first poll rather than construction (a load may delay
 * polling), [onStillBusy] runs once. This never authorizes destruction. The owner backs off,
 * keeping occasional short confirmation polls for shared engines to observe a fresh fence.
 * Missing fences remain busy; an errored fence ([isReached] returns null) is replaced.
 *
 * Filament-free for JVM tests. The owner must release the probe before destroying its engine,
 * or abandon it without calling [release] after a raw engine destruction.
 */
internal class FreshFenceProbe<F : Any>(
    private val createFence: () -> F?,
    private val isReached: (F) -> Boolean?,
    private val destroyFence: (F) -> Unit,
    private val now: () -> Long,
    private val maxFreshAgeMs: Long = FRESH_FENCE_MAX_AGE_MS,
    private val warnAfterMs: Long = FRESH_FENCE_WARN_AFTER_MS,
    private val onStillBusy: () -> Unit = {},
) {
    private var fence: F? = null
    private var armedAt = 0L
    private var firstPollAt = NOT_POLLED
    var hasWarned = false
        private set

    /**
     * `true` while the backend has not recently caught up with what is queued on it.
     * Failure to create a fence is not evidence that the backend is idle. Fence waits use a zero
     * timeout, but submitting commands can still block in Filament.
     */
    fun isBusy(): Boolean = isBusy(requireFresh = true)

    fun isBusy(requireFresh: Boolean): Boolean {
        val at = now()
        if (firstPollAt == NOT_POLLED) firstPollAt = at
        if (!hasWarned && at - firstPollAt >= warnAfterMs) {
            hasWarned = true
            onStillBusy()
        }
        val current = fence ?: arm(at) ?: return true
        when (isReached(current)) {
            false -> return true
            true -> if (!requireFresh || at - armedAt <= maxFreshAgeMs) return false
            null -> Unit // ERROR is not a pending fence: retry with a new one.
        }
        // A stale or errored fence cannot establish readiness. Measure again from here.
        release()
        arm(at)
        return true
    }

    /** Destroys the fence still held, if any. Call once the probe is no longer polled. */
    fun release() {
        fence?.let { runCatching { destroyFence(it) } }
        fence = null
    }

    /** Ends a wait episode without touching JNI (the engine may already be gone). */
    fun resetWait() {
        firstPollAt = NOT_POLLED
        hasWarned = false
    }

    private fun arm(at: Long): F? = createFence()?.also {
        fence = it
        armedAt = at
    }

    private companion object {
        const val NOT_POLLED = Long.MIN_VALUE
    }
}
