package io.github.sceneview

import io.github.sceneview.utils.releaseSwapChains
import org.junit.Assert.assertEquals
import org.junit.Test

class SurfaceDetachBudgetTest {
    private val second = SURFACE_DETACH_WAIT_NANOS

    @Test
    fun mirrorAndWindowWaitsShareTheSameDeadline() {
        var now = 0L
        val budget = SurfaceDetachBudget(nanoTime = { now })
        val calls = mutableListOf<String>()
        releaseSwapChains(
            listOf("mirror"),
            destroySwapChain = { calls += "destroy $it" },
            flushAndWait = {
                calls += "wait $it"
                now += second * 3 / 4
                false
            },
            waitBudget = budget,
        )
        // The recorder releases its surface after the mirror wait.
        calls += "host lost"
        now += second / 10
        releaseSwapChains(
            listOf("window"),
            destroySwapChain = { calls += "destroy $it" },
            flushAndWait = { calls += "wait $it"; true },
            waitBudget = budget,
        )
        assertEquals(
            listOf(
                "destroy mirror", "wait $second", "host lost",
                "destroy window", "wait ${second * 15 / 100}",
            ),
            calls,
        )
    }

    @Test
    fun anExpiredBudgetStillFlushesTheSecondDestructionWithZeroTimeout() {
        var now = 0L
        val budget = SurfaceDetachBudget(nanoTime = { now })
        val timeouts = mutableListOf<Long>()
        repeat(2) {
            releaseSwapChains(
                listOf(it),
                destroySwapChain = {},
                flushAndWait = { timeout ->
                    timeouts += timeout
                    now += second + 1
                    false
                },
                waitBudget = budget,
            )
        }
        assertEquals(listOf(second, 0L), timeouts)
    }

    @Test
    fun mirrorsWithNoSwapChainLeaveTheWholeBudgetForTheWindow() {
        var now = 0L
        val budget = SurfaceDetachBudget(nanoTime = { now })
        releaseSwapChains(
            emptyList<Int>(),
            destroySwapChain = { error("No chain") },
            flushAndWait = { error("No wait") },
            waitBudget = budget,
        )
        now += 10 * second
        assertEquals(second, budget.remainingNanos())
    }

    @Test
    fun separateDisposalsGetSeparateBudgets() {
        var now = 0L
        val first = SurfaceDetachBudget(nanoTime = { now })
        assertEquals(second, first.remainingNanos())
        now += second
        assertEquals(0L, first.remainingNanos())
        assertEquals(second, SurfaceDetachBudget(nanoTime = { now }).remainingNanos())
    }
}
