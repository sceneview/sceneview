package io.github.sceneview.snapshot

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [awaitFence], the wait [ModelSnapshot] paces every render step on: it suspends instead of
 * blocking, gives the main thread a turn before its first check, and never leaks the fence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AwaitFenceTest {

    private class FakeFence(val reachedAfterChecks: Int) {
        var checks = 0
        var destroyed = 0
    }

    private suspend fun await(fence: FakeFence?, timeoutMs: Long = 1_000) = awaitFence(
        createFence = { fence },
        isReached = { it.checks++; it.checks >= it.reachedAfterChecks },
        destroyFence = { it.destroyed++ },
        pollMs = 16,
        timeoutMs = timeoutMs,
    )

    @Test
    fun a_fence_reached_after_a_few_polls_returns_true_and_is_destroyed_once() = runTest {
        val fence = FakeFence(reachedAfterChecks = 3)

        assertTrue(await(fence))

        assertEquals(3, fence.checks)
        assertEquals(1, fence.destroyed)
        assertEquals("one poll per check, never a busy wait", 48L, currentTime)
    }

    @Test
    fun the_first_check_comes_one_poll_later_so_the_caller_yields_its_turn() = runTest {
        val fence = FakeFence(reachedAfterChecks = 1)

        val wait = async { await(fence) }
        runCurrent()
        assertEquals("not checked inline", 0, fence.checks)
        advanceTimeBy(17)

        assertTrue(wait.await())
        assertEquals(1, fence.checks)
    }

    @Test
    fun a_backend_that_never_catches_up_times_out_with_false_and_the_fence_destroyed() = runTest {
        val fence = FakeFence(reachedAfterChecks = Int.MAX_VALUE)

        assertFalse(await(fence, timeoutMs = 500))

        assertEquals(1, fence.destroyed)
        assertEquals(500L, currentTime)
    }

    @Test
    fun a_cancelled_wait_still_destroys_the_fence() = runTest {
        val fence = FakeFence(reachedAfterChecks = Int.MAX_VALUE)

        val wait = async { await(fence) }
        advanceTimeBy(100)
        wait.cancel()
        runCurrent()

        assertTrue(wait.isCancelled)
        assertEquals(1, fence.destroyed)
    }

    @Test
    fun no_fence_to_create_means_nothing_to_wait_for() = runTest {
        assertTrue(await(null))
        assertEquals(0L, currentTime)
    }
}
