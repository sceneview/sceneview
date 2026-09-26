package io.github.sceneview

/**
 * Teardowns waiting for one engine's backend to go idle, tracked so that the engine's own teardown
 * can run the ones still pending first (#3799, #3885).
 *
 * Several Filament releases wait on the calling thread for every command already queued on the
 * backend: `Engine.destroy()` joins the driver thread, and `FRenderer::terminate` (reached from
 * `Engine.destroyRenderer` and from an `IBLPrefilterContext`'s destructor) waits on a fence. When a
 * scene is disposed right after it appeared, that queue still holds its shader links and prefilter
 * passes — seconds on an emulator, an ANR on the main thread. So those releases are [defer]red:
 * the backend is polled with a zero-timeout fence wait once per frame, and the release runs once it
 * is idle, when its inner wait has nothing left to drain.
 *
 * The engine must outlive everything it owns: an `IBLPrefilterContext` destroyed after its engine
 * reads freed memory. `Engine.safeDestroy()` therefore calls [runPending] right before
 * `Engine.destroy()`. Each teardown runs exactly once — from its own poll or from [runPending],
 * whichever comes first — and a poll whose teardown already ran stops without touching anything.
 *
 * Filament-free so it can be tested on the JVM; `Engine.whenBackendIdle()` is the Filament binding.
 * Main thread only, like every Filament call.
 */
internal class BackendIdleTeardowns {

    private inner class Teardown(private val action: () -> Unit) {
        var isDone = false
            private set

        fun run() {
            if (isDone) return
            drop()
            action()
        }

        fun drop() {
            isDone = true
            pending.remove(this)
        }
    }

    private val pending = ArrayDeque<Teardown>()

    /** Number of teardowns still waiting for the backend. */
    val size: Int get() = pending.size

    /**
     * Runs [teardown] now when the backend is already idle (`deferred = false`, the usual case).
     * Otherwise registers it and re-checks every [intervalMs] through [schedule]; it runs with
     * `deferred = true` once [isBackendBusy] turns `false` or [runPending] claims it. Never waits.
     *
     * If [isEngineAlive] turns `false` first — the engine destroyed without [runPending], by a raw
     * `Engine.destroy()` — [onEngineGone] runs instead and [isBackendBusy] is never called again:
     * the dead engine freed the fence it polls.
     */
    fun defer(
        isEngineAlive: () -> Boolean,
        isBackendBusy: () -> Boolean,
        schedule: (delayMs: Long, block: () -> Unit) -> Unit,
        teardown: (deferred: Boolean) -> Unit,
        onEngineGone: () -> Unit = {},
        intervalMs: Long = BACKEND_IDLE_POLL_MS,
    ) {
        if (!isEngineAlive()) {
            onEngineGone()
            return
        }
        if (!isBackendBusy()) {
            teardown(false)
            return
        }
        val entry = Teardown { teardown(true) }
        pending.addLast(entry)

        fun poll() {
            when {
                entry.isDone -> Unit
                !isEngineAlive() -> {
                    entry.drop()
                    onEngineGone()
                }
                isBackendBusy() -> schedule(intervalMs, ::poll)
                else -> entry.run()
            }
        }
        schedule(intervalMs, ::poll)
    }

    /**
     * Runs every teardown still waiting, oldest first. Called right before the engine is destroyed.
     * A teardown registered while this runs is run too.
     */
    fun runPending() {
        while (pending.isNotEmpty()) pending.first().run()
    }
}
