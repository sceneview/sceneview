package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM pins for #3799 and #3885: no teardown may park the main thread on the backend backlog
 * long enough to ANR, and the engine must outlive every teardown it defers.
 *
 * [BackendIdleTeardowns] is the Filament-free core of `Engine.whenBackendIdle()`, behind
 * `rememberEngine` (`Engine.destroy()` joins the driver thread after the whole backlog),
 * `rememberRenderer` and `rememberEnvironmentLoader` (`FRenderer::terminate` — reached from
 * `Engine.destroyRenderer` and from the IBL prefilter context's destructor — waits on it too).
 */
class BackendIdleTeardownTest {

    /** A scheduler that records requests instead of running them, like a posted Handler. */
    private class RecordingScheduler {
        val delays = mutableListOf<Long>()
        private val pending = ArrayDeque<() -> Unit>()
        val schedule: (Long, () -> Unit) -> Unit = { delay, block ->
            delays += delay
            pending.addLast(block)
        }
        fun runNext() = pending.removeFirst().invoke()
        fun runAll() {
            while (pending.isNotEmpty()) runNext()
        }
        val hasPending get() = pending.isNotEmpty()
    }

    /** One deferred teardown's observable outcome. */
    private class Outcome {
        val runs = mutableListOf<Boolean>()
        var gone = 0
        val teardown: (Boolean) -> Unit = { runs += it }
        val onEngineGone: () -> Unit = { gone++ }
    }

    @Test
    fun idleBackendTearsDownSynchronously() {
        val teardowns = BackendIdleTeardowns()
        val scheduler = RecordingScheduler()
        val outcome = Outcome()
        teardowns.defer(
            isEngineAlive = { true },
            isBackendBusy = { false },
            schedule = scheduler.schedule,
            teardown = outcome.teardown,
        )
        // The usual case keeps the old behaviour: destroyed before onDispose returns.
        assertEquals(listOf(false), outcome.runs)
        assertTrue("an idle backend must not schedule a re-check", scheduler.delays.isEmpty())
        assertEquals(0, teardowns.size)
    }

    @Test
    fun busyBackendIsPolledNotAwaited() {
        val teardowns = BackendIdleTeardowns()
        val scheduler = RecordingScheduler()
        val outcome = Outcome()
        var busyChecks = 4
        teardowns.defer(
            isEngineAlive = { true },
            isBackendBusy = { busyChecks-- > 0 },
            schedule = scheduler.schedule,
            teardown = outcome.teardown,
        )
        // Returned without tearing down: the caller's thread is free while the backend drains.
        assertTrue(outcome.runs.isEmpty())
        assertEquals(1, teardowns.size)

        scheduler.runAll()

        assertEquals("ran exactly once, flagged as deferred", listOf(true), outcome.runs)
        assertEquals(List(4) { BACKEND_IDLE_POLL_MS }, scheduler.delays)
        assertEquals(0, teardowns.size)
    }

    @Test
    fun engineTeardownRunsPendingTeardownsFirstAndOnlyOnce() {
        // #3885: rememberEnvironmentLoader's deferred prefilter release must run before the engine
        // is destroyed. Its poll can still be scheduled when the engine's own fence signals.
        val teardowns = BackendIdleTeardowns()
        val scheduler = RecordingScheduler()
        val order = mutableListOf<String>()
        var backendBusy = true
        var engineAlive = true
        teardowns.defer(
            isEngineAlive = { engineAlive },
            isBackendBusy = { backendBusy },
            schedule = scheduler.schedule,
            teardown = { order += "renderer" },
        )
        teardowns.defer(
            isEngineAlive = { engineAlive },
            isBackendBusy = { backendBusy },
            schedule = scheduler.schedule,
            teardown = { order += "ibl-prefilter" },
        )
        assertEquals(2, teardowns.size)

        // What Engine.safeDestroy() does: run what is pending, then destroy the engine.
        teardowns.runPending()
        order += "engine"
        engineAlive = false
        backendBusy = false

        scheduler.runAll()

        assertEquals(listOf("renderer", "ibl-prefilter", "engine"), order)
        assertEquals(0, teardowns.size)
    }

    @Test
    fun pollOfATeardownAlreadyRunNeverTouchesTheFenceAgain() {
        val teardowns = BackendIdleTeardowns()
        val scheduler = RecordingScheduler()
        val outcome = Outcome()
        var fencePolls = 0
        teardowns.defer(
            isEngineAlive = { true },
            isBackendBusy = { fencePolls++; true },
            schedule = scheduler.schedule,
            teardown = outcome.teardown,
            onEngineGone = outcome.onEngineGone,
        )
        teardowns.runPending()
        val pollsAtTeardown = fencePolls

        scheduler.runAll()

        assertEquals("its fence was destroyed with the teardown", pollsAtTeardown, fencePolls)
        assertEquals(listOf(true), outcome.runs)
        assertEquals(0, outcome.gone)
    }

    @Test
    fun engineDestroyedWithoutRunPendingNeverHasItsFencePolledAgain() {
        // A raw Engine.destroy() skips runPending; FEngine::shutdown then frees the fence the poll
        // holds. Waiting on it would dereference freed native memory — a SIGSEGV, not an exception.
        val teardowns = BackendIdleTeardowns()
        val scheduler = RecordingScheduler()
        val outcome = Outcome()
        var engineAlive = true
        var fencePolls = 0
        teardowns.defer(
            isEngineAlive = { engineAlive },
            isBackendBusy = { fencePolls++; true },
            schedule = scheduler.schedule,
            teardown = outcome.teardown,
            onEngineGone = outcome.onEngineGone,
        )
        assertEquals(1, fencePolls)

        engineAlive = false
        scheduler.runAll()

        assertEquals("the fence must not be polled once its engine is gone", 1, fencePolls)
        assertTrue("nothing is destroyed twice", outcome.runs.isEmpty())
        assertEquals(1, outcome.gone)
        assertEquals(0, teardowns.size)
    }

    @Test
    fun deadEngineIsNeverPolled() {
        val teardowns = BackendIdleTeardowns()
        val scheduler = RecordingScheduler()
        val outcome = Outcome()
        teardowns.defer(
            isEngineAlive = { false },
            isBackendBusy = { error("a dead engine's fence must not be touched") },
            schedule = scheduler.schedule,
            teardown = outcome.teardown,
            onEngineGone = outcome.onEngineGone,
        )
        assertEquals(1, outcome.gone)
        assertTrue(outcome.runs.isEmpty())
        assertFalse(scheduler.hasPending)
    }

    @Test
    fun surfaceWaitsStayFarBelowTheInputDispatchAnrThreshold() {
        // Android raises an ANR when an input event waits 5 s. A detach and a resize can land in
        // the same main-thread message (a sheet closing while a tab switches); together they must
        // leave most of that budget to the rest of the frame.
        val inputDispatchTimeoutNanos = 5_000_000_000L
        assertTrue(
            SURFACE_DETACH_WAIT_NANOS + SURFACE_RESIZE_WAIT_NANOS <= inputDispatchTimeoutNanos / 4
        )
        assertTrue(SURFACE_RESIZE_WAIT_NANOS in 1 until SURFACE_DETACH_WAIT_NANOS)
    }
}
