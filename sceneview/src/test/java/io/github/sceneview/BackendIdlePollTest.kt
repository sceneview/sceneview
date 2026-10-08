package io.github.sceneview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schedule of a teardown deferred by [whenBackendIdle]: how often it checks the backend, when
 * it warns and when it gives up. Pure arithmetic, no engine needed.
 */
class BackendIdlePollTest {

    /** The delays asked for until the budget is spent, as [whenBackendIdle] adds them up. */
    private fun schedule(): List<Long> {
        val delays = mutableListOf<Long>()
        var waitedMs = 0L
        while (waitedMs < BACKEND_IDLE_BUDGET_MS) {
            val delayMs = backendIdlePollDelayMs(waitedMs)
            delays += delayMs
            waitedMs += delayMs
        }
        return delays
    }

    @Test
    fun `checks every frame for the first quarter of a second`() {
        assertEquals(BACKEND_IDLE_POLL_MS, backendIdlePollDelayMs(0L))
        assertEquals(BACKEND_IDLE_POLL_MS, backendIdlePollDelayMs(256L))
        assertTrue(backendIdlePollDelayMs(512L) > BACKEND_IDLE_POLL_MS)
    }

    @Test
    fun `never checks less often than twice a second`() {
        assertEquals(499L, backendIdlePollDelayMs(7_999L))
        assertEquals(BACKEND_IDLE_POLL_MAX_MS, backendIdlePollDelayMs(8_000L))
        assertEquals(BACKEND_IDLE_POLL_MAX_MS, backendIdlePollDelayMs(BACKEND_IDLE_BUDGET_MS))
        assertEquals(BACKEND_IDLE_POLL_MAX_MS, backendIdlePollDelayMs(Long.MAX_VALUE))
    }

    @Test
    fun `backs off without ever speeding up again`() {
        val delays = schedule()
        assertTrue(delays.zipWithNext().all { (previous, next) -> next >= previous })
        assertTrue(delays.all { it in BACKEND_IDLE_POLL_MS..BACKEND_IDLE_POLL_MAX_MS })
    }

    @Test
    fun `a check is never later than a sixteenth of the time already waited`() {
        var waitedMs = 0L
        for (delayMs in schedule()) {
            assertTrue(delayMs <= maxOf(BACKEND_IDLE_POLL_MS, waitedMs / 16L))
            waitedMs += delayMs
        }
    }

    @Test
    fun `the whole budget costs under 300 checks`() {
        val delays = schedule()
        assertEquals(298, delays.size)
        // The last delay can overshoot the budget, by less than one delay.
        assertTrue(delays.sum() >= BACKEND_IDLE_BUDGET_MS)
        assertTrue(delays.sum() < BACKEND_IDLE_BUDGET_MS + BACKEND_IDLE_POLL_MAX_MS)
    }

    @Test
    fun `the slowest drain measured is reached long before the budget`() {
        // 13.8 s: the 2.3.3 backend on a loaded emulator, which a 10 s give-up would have leaked.
        val slowestDrainMs = 13_800L
        var waitedMs = 0L
        var checks = 0
        for (delayMs in schedule()) {
            if (waitedMs >= slowestDrainMs) break
            waitedMs += delayMs
            checks++
        }
        assertEquals(85, checks)
        assertTrue(waitedMs < BACKEND_IDLE_BUDGET_MS / 8)
    }

    @Test
    fun `the warning comes once, well before the give-up`() {
        assertTrue(BACKEND_IDLE_WARN_MS < BACKEND_IDLE_BUDGET_MS / 10 + 1)
        // Checks at which the warning condition first holds: exactly one transition.
        var waitedMs = 0L
        var transitions = 0
        var wasPast = false
        for (delayMs in schedule()) {
            waitedMs += delayMs
            val isPast = waitedMs >= BACKEND_IDLE_WARN_MS
            if (isPast && !wasPast) transitions++
            wasPast = isPast
        }
        assertEquals(1, transitions)
    }

    @Test
    fun `the surface callbacks stay far below the ANR threshold`() {
        val anrNanos = 5_000_000_000L
        assertTrue(SURFACE_DETACH_WAIT_NANOS <= anrNanos / 5)
        assertTrue(SURFACE_RESIZE_WAIT_NANOS <= SURFACE_DETACH_WAIT_NANOS)
        assertTrue(BACKEND_IDLE_FIRST_WAIT_NANOS <= 16_000_000L)
    }
}
