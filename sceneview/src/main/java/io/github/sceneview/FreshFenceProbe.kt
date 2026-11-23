package io.github.sceneview

/**
 * Maximum age of a reached fence on a shared engine: two polls plus one of slack for a late
 * Handler. This bounds main-thread queuing time, not backend work. A shader compilation queued
 * in these 48 ms still costs its full price when a native destructor waits for it.
 *
 * Keep this window fixed: widening it with the observed poll gap accepts the very backlog a long
 * main-thread pass just queued. Steady polls more than 48 ms apart cannot satisfy it unless a
 * replacement fence is already reached in the same poll. A retiring engine, after its producers
 * stop, instead accepts any reached fence for pending releases regardless of age.
 */
internal const val FRESH_FENCE_MAX_AGE_MS = 3 * BACKEND_IDLE_POLL_MS

/**
 * After this delay a poll may also confirm with a bounded wait. Elapsed time never makes a
 * stale fence safe: it only lets the probe ask, with a fence created in that poll, whether the
 * backend is idle right now.
 */
internal const val FRESH_FENCE_WARN_AFTER_MS = 5_000L

/**
 * Timeout of the one bounded fence check a poll makes after [FRESH_FENCE_WARN_AFTER_MS] (#4969).
 *
 * A fence created in the poll and reached within this budget covers everything queued so far, so
 * a release run right after it in the same message waits for nothing the main thread queued
 * since. That is what the 48 ms freshness window approximates, and what a main thread polling
 * less often than every 48 ms never satisfies: without it, a shared engine on a low-end device
 * kept its pending releases until Engine.destroy. A backend still busy (a new screen's shaders)
 * misses the budget, and the release stays pending.
 *
 * The bound is per wake-up, not wall-clock. Filament v1.77.3's `FEngine::waitFence` loops on
 * `wait_for(timeout)` while the fence is unsignalled, on a condition variable shared by every
 * fence: each earlier fence signalled meanwhile restarts the 4 ms. The worst case is about
 * 4 ms × (fences queued before this one + 1). SceneView keeps one probe fence per engine, so that
 * count is usually small. `Engine.kt` times each wait and logs once if one exceeds 16 ms.
 *
 * Cost and rate: one check per poll, only while a release is pending past the warning. The poll
 * then alternates a 1 s back-off and a short confirmation, so at most two checks, and at most two
 * releases, about every second. Each release invalidates the probe, so pending releases drain
 * one per poll: 20 renderers take about ten seconds on a 15 fps main thread.
 */
internal const val FRESH_FENCE_CONFIRM_BUDGET_MS = 4L

/** Elapsed waiting time never grants readiness; retirement after producers stop drops freshness. */
internal fun fenceAllowsTeardown(
    reached: Boolean?,
    ageMs: Long,
    maxFreshAgeMs: Long,
    requireFresh: Boolean = true,
): Boolean = reached == true && (!requireFresh || ageMs in 0..maxFreshAgeMs)

/**
 * Polls the backend backlog on the engine's thread. Fences are checked without waiting, except
 * the bounded [awaitReached] check described below.
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
 * polling), [onStillBusy] runs once. This never authorizes destruction behind a stale fence. The
 * owner backs off, keeping occasional short confirmation polls for shared engines to observe a
 * fresh fence. From then on a fence armed by the call itself (the first after an invalidation,
 * or the replacement of a stale one) is checked with [awaitReached], a wait bounded by the caller
 * ([FRESH_FENCE_CONFIRM_BUDGET_MS] per wake-up on an engine), instead of the zero-timeout
 * [isReached]: reached within it, the backend is idle right now, whatever the main thread's
 * cadence. Only a caller passing `mayWait` gets it: the owner does so from its scheduled poll,
 * never from a registration. Without [awaitReached] the probe never waits.
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
    private val awaitReached: ((F) -> Boolean?)? = null,
) {
    private var fence: F? = null
    private var armedAt = 0L
    private var firstPollAt = NOT_POLLED
    var hasWarned = false
        private set

    /**
     * `true` while the backend has not recently caught up with what is queued on it.
     * Failure to create a fence is not evidence that the backend is idle. Fence waits use a zero
     * timeout, or [awaitReached]'s bound after the warning; submitting commands can still block
     * in Filament.
     */
    fun isBusy(): Boolean = isBusy(requireFresh = true)

    /** [mayWait] allows the bounded [awaitReached] check, past the warning only. */
    fun isBusy(requireFresh: Boolean, mayWait: Boolean = false): Boolean {
        val at = now()
        if (firstPollAt == NOT_POLLED) firstPollAt = at
        if (!hasWarned && at - firstPollAt >= warnAfterMs) {
            hasWarned = true
            onStillBusy()
        }
        // A fence armed in this call covers everything queued right now, even on a slow looper.
        // Zero-timeout at first; after the warning, a wait bounded by awaitReached, so a main
        // thread too slow for the freshness window can still find the backend idle (#4969).
        val confirm = awaitReached?.takeIf { hasWarned && mayWait } ?: isReached
        val held = fence
        val current = held ?: arm(at) ?: return true
        val reached = if (held == null) confirm(current) else isReached(current)
        if (fenceAllowsTeardown(reached, now() - armedAt, maxFreshAgeMs, requireFresh)) return false
        if (reached == false) return true
        // A stale or errored fence cannot establish readiness. Measure again from here.
        release()
        val replacement = arm(now()) ?: return true
        return confirm(replacement) != true
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
