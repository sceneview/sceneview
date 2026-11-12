package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM pins for #3799 and #3885: defer releases while their readiness checks report busy,
 * and keep the engine alive until every dependent release has run. Native waits remain unbounded.
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
        val schedule: (Long, () -> Unit) -> Boolean = { delay, block ->
            delays += delay
            pending.addLast(block)
            true
        }
        fun runNext() = pending.removeFirst().invoke()
        fun runAll() {
            while (pending.isNotEmpty()) runNext()
        }
        val hasPending get() = pending.isNotEmpty()
        val pendingCount get() = pending.size
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
    fun pendingTeardownsShareOneScheduledPoll() {
        val teardowns = BackendIdleTeardowns()
        val scheduler = RecordingScheduler()
        var busy = true
        var runs = 0
        repeat(20) {
            teardowns.defer(
                isEngineAlive = { true },
                isBackendBusy = { busy },
                schedule = scheduler.schedule,
                teardown = { runs++ },
            )
        }
        assertEquals(listOf(BACKEND_IDLE_POLL_MS), scheduler.delays)
        scheduler.runNext()
        assertEquals(2, scheduler.delays.size)
        busy = false
        scheduler.runAll()
        assertEquals(20, runs)
        assertEquals(0, teardowns.size)
    }

    @Test
    fun repeatedWarningsKeepOneBoundedPollAndResetAfterEachWait() {
        var clock = 0L
        var reached = false
        var warnings = 0
        var fences = 0
        var runs = 0
        val probe = FreshFenceProbe(
            createFence = { ++fences },
            isReached = { reached },
            destroyFence = { fences-- },
            now = { clock },
            onStillBusy = { warnings++ },
        )
        // Reuse the registry to check its reset, independently of Engine's registry eviction.
        val teardowns = BackendIdleTeardowns(probe)
        val scheduler = RecordingScheduler()
        val schedule: (Long, () -> Unit) -> Boolean = { delay, block ->
            scheduler.schedule(delay) { clock += delay; block() }
        }

        repeat(3) { episode ->
            reached = false
            teardowns.defer(
                isEngineAlive = { true },
                schedule = schedule,
                teardown = { runs++ },
                runsLast = episode == 0,
            )
            assertFalse("a new wait gets its own diagnostic window", probe.hasWarned)
            assertEquals(BACKEND_IDLE_POLL_MS, scheduler.delays.last())
            while (!probe.hasWarned) scheduler.runNext()
            repeat(100) {
                assertEquals(1, scheduler.pendingCount)
                assertEquals(1, teardowns.size)
                assertEquals(1, fences)
                assertTrue(scheduler.delays.last() in BACKEND_IDLE_POLL_MS..BACKEND_IDLE_BACKOFF_MS)
                scheduler.runNext()
            }
            assertEquals("one warning for the whole wait", episode + 1, warnings)
            assertEquals(episode, runs)

            reached = true
            clock += 2_000
            scheduler.runNext()
            assertEquals("retirement does not leak into the next shared wait",
                if (episode == 0) episode + 1 else episode, runs)
            scheduler.runAll()
            assertEquals(episode + 1, runs)
            assertEquals(0, teardowns.size)
            assertEquals(0, fences)
            assertEquals(0, scheduler.pendingCount)
            assertFalse("completion resets the warning and timer", probe.hasWarned)
            clock += 60_000
        }
    }

    @Test
    fun explicitCleanupAlsoKeepsTheEngineAfterLateRegisteredChildren() {
        val teardowns = BackendIdleTeardowns()
        val scheduler = RecordingScheduler()
        val order = mutableListOf<String>()
        for (name in listOf("engine", "child")) {
            teardowns.defer(
                isEngineAlive = { true },
                isBackendBusy = { true },
                schedule = scheduler.schedule,
                teardown = { order += name },
                runsLast = name == "engine",
            )
        }
        teardowns.runPending()
        scheduler.runAll()
        assertEquals(listOf("child", "engine"), order)
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
    fun engineDestroyWaitsForAModelLoaderStillDecoding() {
        // #3981: a ModelLoader left mid-load releases once its decoders are done. The engine's own
        // destroy (runsLast) must wait for it rather than force it through runPending(), which
        // would join those decoders on the main thread — the very ANR the deferral avoids.
        val teardowns = BackendIdleTeardowns()
        val scheduler = RecordingScheduler()
        val order = mutableListOf<String>()
        var decoding = true
        teardowns.defer(
            isEngineAlive = { true },
            isBackendBusy = { decoding },
            schedule = scheduler.schedule,
            teardown = { order += "model-loader" },
        )
        teardowns.defer(
            isEngineAlive = { true },
            isBackendBusy = { false },
            schedule = scheduler.schedule,
            teardown = { order += "engine" },
            runsLast = true,
        )
        assertTrue("an idle backend alone must not destroy the engine early", order.isEmpty())

        scheduler.runNext()
        scheduler.runNext()
        assertTrue(order.isEmpty())

        decoding = false
        scheduler.runAll()
        assertEquals(listOf("model-loader", "engine"), order)
        assertEquals(0, teardowns.size)
    }

    @Test
    fun runsLastTeardownIsSynchronousWhenNothingElseIsPending() {
        val teardowns = BackendIdleTeardowns()
        val scheduler = RecordingScheduler()
        val outcome = Outcome()
        teardowns.defer(
            isEngineAlive = { true },
            isBackendBusy = { false },
            schedule = scheduler.schedule,
            teardown = outcome.teardown,
            runsLast = true,
        )
        assertEquals(listOf(false), outcome.runs)
        assertFalse(scheduler.hasPending)
    }

    /** `Handler.postDelayed` returns `false` once its looper is quitting: nothing would ever poll. */
    @Test
    fun aRefusedPollRetainsTheTeardownUntilExplicitEngineCleanup() {
        val teardowns = BackendIdleTeardowns()
        val refused = Outcome()
        teardowns.defer(
            isEngineAlive = { true },
            isBackendBusy = { true },
            schedule = { _, _ -> false },
            teardown = refused.teardown,
        )
        assertTrue(refused.runs.isEmpty())
        assertEquals(1, teardowns.size)
        teardowns.runPending()
        assertEquals(listOf(true), refused.runs)
        assertEquals(0, teardowns.size)

        // Refused at a later re-check, once the looper started quitting.
        var accepted = 1
        val pending = ArrayDeque<() -> Unit>()
        val later = Outcome()
        teardowns.defer(
            isEngineAlive = { true },
            isBackendBusy = { true },
            schedule = { _, block -> (accepted-- > 0).also { if (it) pending.addLast(block) } },
            teardown = later.teardown,
        )
        assertTrue(later.runs.isEmpty())
        pending.removeFirst().invoke()
        assertTrue(later.runs.isEmpty())
        assertEquals(1, teardowns.size)
        teardowns.runPending()
        assertEquals(listOf(true), later.runs)
        assertEquals(0, teardowns.size)
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
