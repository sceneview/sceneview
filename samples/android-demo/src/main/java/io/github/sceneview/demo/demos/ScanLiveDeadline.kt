package io.github.sceneview.demo.demos

/** Budgets use the accepted frame's slot, not the time at which projection happened to end. */
internal object ScanLiveDeadline {
    const val SLOT_NS = 100_000_000L
    const val TARGET_NS = 1_000_000_000L
    const val HARD_NS = 2_000_000_000L
    const val ADD_LIMIT_NS = 80_000_000L
    const val IDLE_SLICE_NS = 5_000_000L

    fun budget(ageNs: Long, elapsedNs: Long, adding: Boolean): Long {
        val elapsed = elapsedNs.coerceAtLeast(0L)
        if (!adding) {
            if (ageNs > HARD_NS) return Long.MAX_VALUE
            val beforeSlot = if (elapsed < SLOT_NS) (SLOT_NS - elapsed - IDLE_SLICE_NS).coerceAtLeast(0L) else IDLE_SLICE_NS
            return minOf(IDLE_SLICE_NS, beforeSlot)
        }
        val left = (ADD_LIMIT_NS - elapsed).coerceAtLeast(0L)
        // Half the remaining slot initially, all of it by the normal deadline. No young build
        // can consume the guard before the next depth slot, even when fusion itself is slow.
        val share = 0.5 + 0.5 * ageNs.coerceIn(0L, TARGET_NS).toDouble() / TARGET_NS
        return (left * share).toLong()
    }
}
