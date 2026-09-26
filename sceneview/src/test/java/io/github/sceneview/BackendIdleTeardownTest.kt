package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM pins for #3799: no surface-lifecycle wait may park the main thread long enough to ANR.
 *
 * [awaitBackendIdle] is the Filament-free core of `Engine.destroyWhenBackendIdle()`, which
 * `rememberEngine` uses instead of an inline `Engine.destroy()` — whose driver-thread join waits
 * out every queued shader compile of a scene disposed right after it appeared.
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
        val hasPending get() = pending.isNotEmpty()
    }

    @Test
    fun idleBackendTearsDownSynchronously() {
        val scheduler = RecordingScheduler()
        var idleAfter = -1
        awaitBackendIdle(
            isBackendBusy = { false },
            schedule = scheduler.schedule,
            onIdle = { idleAfter = it },
        )
        // The usual case keeps today's behaviour: destroyed before onDispose returns.
        assertEquals(0, idleAfter)
        assertTrue("an idle backend must not schedule a re-check", scheduler.delays.isEmpty())
    }

    @Test
    fun busyBackendIsPolledNotAwaited() {
        val scheduler = RecordingScheduler()
        var busyChecks = 3
        var idleAfter = -1
        awaitBackendIdle(
            isBackendBusy = { busyChecks-- > 0 },
            schedule = scheduler.schedule,
            onIdle = { idleAfter = it },
        )
        // Returned without tearing down: the caller's thread is free while the backend drains.
        assertEquals(-1, idleAfter)
        assertTrue(scheduler.hasPending)

        while (scheduler.hasPending) scheduler.runNext()

        assertEquals(3, idleAfter)
        assertEquals(List(3) { BACKEND_IDLE_POLL_MS }, scheduler.delays)
    }

    @Test
    fun teardownRunsExactlyOnce() {
        val scheduler = RecordingScheduler()
        var busyChecks = 2
        var teardowns = 0
        awaitBackendIdle(
            isBackendBusy = { busyChecks-- > 0 },
            schedule = scheduler.schedule,
            onIdle = { teardowns++ },
        )
        while (scheduler.hasPending) scheduler.runNext()
        assertEquals(1, teardowns)
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
