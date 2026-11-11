package io.github.sceneview

import io.github.sceneview.utils.releaseSwapChains
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SurfaceDetachBudgetTest {
    private val second = SURFACE_DETACH_WAIT_NANOS

    @Test
    fun mirrorAndWindowShareOnlyTimeSpentWaiting() {
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
        // A slow recorder finalisation must not consume the window's remaining wait.
        calls += "host lost"
        now += 10 * second
        releaseSwapChains(
            listOf("window"),
            destroySwapChain = { calls += "destroy $it"; now += second },
            flushAndWait = { calls += "wait $it"; true },
            waitBudget = budget,
        )
        assertEquals(
            listOf(
                "destroy mirror", "wait $second", "host lost",
                "destroy window", "wait ${second / 4}",
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
        first.waitFor { now += second; false }
        assertEquals(0L, first.remainingNanos())
        assertEquals(second, SurfaceDetachBudget(nanoTime = { now }).remainingNanos())
    }

    @Test
    fun aHealthyBackendOnlyConsumesItsActualWait() {
        var now = 0L
        val budget = SurfaceDetachBudget(nanoTime = { now })
        budget.waitFor { now += second / 10; true }
        now += 10 * second
        assertEquals(second * 9 / 10, budget.remainingNanos())
        assertEquals(second / 10, budget.lastWaitNanos)
    }

    @Test
    fun theReportedDurationIsTheLastWaitNotItsTimeoutOrTheTotal() {
        var now = 0L
        val budget = SurfaceDetachBudget(nanoTime = { now })
        budget.waitFor { now += second * 3 / 4; true }
        assertFalse(budget.waitFor { now += second / 4; false })
        assertEquals(second / 4, budget.lastWaitNanos)
        assertFalse(budget.waitFor { timeout ->
            assertEquals(0L, timeout)
            false
        })
        assertEquals(0L, budget.lastWaitNanos)
    }

    @Test
    fun aThrowingWaitStillConsumesItsElapsedTime() {
        var now = 0L
        val budget = SurfaceDetachBudget(nanoTime = { now })
        assertThrows(IllegalStateException::class.java) {
            budget.waitFor { now += second / 4; error("Backend failed") }
        }
        assertEquals(second * 3 / 4, budget.remainingNanos())
        assertEquals(second / 4, budget.lastWaitNanos)
    }
}
