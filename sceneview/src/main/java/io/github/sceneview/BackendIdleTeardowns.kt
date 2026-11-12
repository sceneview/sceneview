package io.github.sceneview

/** Polling backs off after the diagnostic, without authorizing a blocking release. */
internal const val BACKEND_IDLE_BACKOFF_MS = 1_000L

/**
 * One engine's deferred releases, sharing one scheduled poll and one backend probe.
 *
 * A shared engine requires a fresh fence because another screen can keep submitting work.
 * Registering its own destruction ([defer] with `runsLast`) marks it RETIRING: producers must
 * have stopped, so any reached fence suffices, even on a slow looper. Other readiness conditions
 * (in particular model decoders) still apply, and the engine always runs last.
 *
 * A release can enqueue more commands. Its fence is invalidated before it runs, and the next
 * backend check waits for the next poll; a later release must cover those commands too.
 * [runPending] is the explicit, potentially blocking `Engine.safeDestroy()` path, never a timeout
 * fallback. Each release runs once, and a dead engine's probe is never touched.
 *
 * Filament-free for JVM tests. Confined to the engine's calling thread.
 */
internal class BackendIdleTeardowns(
    private val probe: FreshFenceProbe<*>? = null,
    private val onEmpty: (BackendIdleTeardowns) -> Unit = {},
) {
    private inner class Teardown(
        val runsLast: Boolean,
        val isEngineAlive: () -> Boolean,
        val isBusy: () -> Boolean,
        val onEngineGone: () -> Unit,
        val action: (Boolean) -> Unit,
    ) {
        var isDone = false
            private set

        fun run(deferred: Boolean = true) {
            if (isDone) return
            drop()
            if (isEngineAlive()) {
                invalidateProbe()
                action(deferred)
            } else {
                onEngineGone()
            }
        }

        fun drop() {
            isDone = true
            pending.remove(this)
        }
    }

    private val pending = ArrayDeque<Teardown>()
    private var retiring = false
    private var pollScheduled = false
    private var polling = false
    private var backendBusy: Boolean? = null
    private var shortConfirmation = false

    val size: Int get() = pending.size

    private fun finishWait() {
        // Reset only when the whole queue drains: re-arming a stale fence or registering
        // another resource during this wait must not restart diagnostics or fast polling.
        retiring = false
        backendBusy = null
        shortConfirmation = false
        probe?.resetWait()
        onEmpty(this)
    }

    /** Shared by fence-dependent releases and stalled model loads, at most once per poll. */
    fun isBackendBusy(): Boolean = backendBusy ?: checkNotNull(probe)
        .isBusy(requireFresh = !retiring).also { backendBusy = it }

    private fun invalidateProbe() {
        probe?.release()
        // The action may queue work: do not reuse readiness or flush again in this poll.
        backendBusy = true
    }

    /**
     * Runs a ready [teardown] synchronously, otherwise retains it for the engine's single poll.
     * `runsLast` retires the engine and waits for all other entries, including non-fence gates.
     * A refused [schedule] retains the entries for an explicit [runPending] or a later deferral.
     */
    fun defer(
        isEngineAlive: () -> Boolean,
        isBackendBusy: () -> Boolean = this::isBackendBusy,
        schedule: (delayMs: Long, block: () -> Unit) -> Boolean,
        teardown: (deferred: Boolean) -> Unit,
        onEngineGone: () -> Unit = {},
        intervalMs: Long = BACKEND_IDLE_POLL_MS,
        runsLast: Boolean = false,
    ) {
        if (!isEngineAlive()) {
            onEngineGone()
            if (pending.isEmpty()) finishWait()
            return
        }
        if (runsLast) retiring = true
        // Include commands queued before this registration, especially the last disposal pass.
        invalidateProbe()
        if (!polling) backendBusy = null
        val entry = Teardown(runsLast, isEngineAlive, isBackendBusy, onEngineGone, teardown)
        pending.addLast(entry)

        fun othersPending(entry: Teardown) = entry.runsLast && pending.any { !it.runsLast }

        fun schedulePoll() {
            if (pending.isEmpty()) {
                finishWait()
                return
            }
            if (pollScheduled || polling) return
            // A shared engine needs a short confirmation after a slow poll re-arms its fence;
            // using only 1 s polls would itself make freshness impossible, even on a fast looper.
            val delay = if (probe?.hasWarned == true) {
                val shortPoll = !retiring && shortConfirmation
                shortConfirmation = !shortPoll
                if (shortPoll) intervalMs else BACKEND_IDLE_BACKOFF_MS
            } else intervalMs
            pollScheduled = schedule(delay) {
                pollScheduled = false
                polling = true
                backendBusy = null
                try {
                    // Children before the engine even if a child registered after retirement.
                    for (next in pending.toList().sortedBy { it.runsLast }) {
                        if (next.isDone) continue
                        if (!next.isEngineAlive()) {
                            next.drop()
                            next.onEngineGone()
                        } else if (!next.isBusy() && !othersPending(next)) {
                            next.run()
                        }
                    }
                } finally {
                    polling = false
                    schedulePoll()
                }
            }
        }
        if (!polling && !entry.isBusy() && !othersPending(entry)) entry.run(deferred = false)
        schedulePoll()
    }

    /** Explicit cleanup, bypassing readiness. May block; never used as a polling deadline. */
    fun runPending() {
        while (pending.isNotEmpty()) {
            (pending.firstOrNull { !it.runsLast } ?: pending.first()).run()
        }
        finishWait()
    }
}
