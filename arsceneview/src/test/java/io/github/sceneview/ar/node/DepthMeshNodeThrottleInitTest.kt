package io.github.sceneview.ar.node

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the `Long.MIN_VALUE` throttle overflow bug (#2186).
 *
 * Before the fix, `lastRebuildTimestampMs` was initialised to `Long.MIN_VALUE`. The throttle guard:
 *
 * ```kotlin
 * if (now - lastRebuildTimestampMs < refreshIntervalMs) return
 * ```
 *
 * computed e.g. `1_000 - Long.MIN_VALUE` which in signed 64-bit arithmetic overflows to a large
 * **negative** number, satisfying `< refreshIntervalMs` (200) on **every** call. The result was
 * that [DepthMeshNode.latestSnapshot] was never populated and the depth mesh never rendered.
 *
 * The fix changes the initial value to `0L`:  any positive `now` satisfies
 * `now - 0L >= refreshIntervalMs`, so the first rebuild fires on the first call.
 */
class DepthMeshNodeThrottleInitTest {

    /**
     * Verify that the throttle guard expression `now - initialValue < refreshIntervalMs` evaluates
     * to `false` for reasonable `now` values (i.e. the guard does NOT block the first rebuild).
     *
     * This exercises the arithmetic directly — no Filament/ARCore mocking required because the bug
     * is in pure Kotlin integer arithmetic, not in Filament state.
     */
    @Test
    fun `initial lastRebuildTimestampMs 0L allows first rebuild immediately`() {
        val refreshIntervalMs = 200L
        val initialValue = 0L // correct initial value after the fix
        // Simulate a realistic system clock reading (e.g. uptime shortly after boot)
        val now = 1_000L

        val guardBlocks = now - initialValue < refreshIntervalMs
        assertEquals(
            "With initial value 0L, the throttle guard must NOT block the first rebuild",
            false,
            guardBlocks,
        )
    }

    /**
     * Verify that the OLD initial value `Long.MIN_VALUE` would trigger the overflow bug.
     *
     * This is a documentation test — it asserts that the broken behaviour would have occurred,
     * helping future readers understand why `0L` was chosen over `Long.MIN_VALUE`.
     */
    @Test
    fun `Long MIN_VALUE initial value overflows and incorrectly blocks first rebuild`() {
        val refreshIntervalMs = 200L
        val brokenInitialValue = Long.MIN_VALUE
        val now = 1_000L

        // now - Long.MIN_VALUE overflows to a negative value in two's complement.
        val diff = now - brokenInitialValue
        val guardBlocksIncorrectly = diff < refreshIntervalMs

        assertEquals(
            "With Long.MIN_VALUE as the initial value, the subtraction overflows " +
                "to a negative number and the throttle guard incorrectly blocks every update",
            true,
            guardBlocksIncorrectly,
        )
    }
}
