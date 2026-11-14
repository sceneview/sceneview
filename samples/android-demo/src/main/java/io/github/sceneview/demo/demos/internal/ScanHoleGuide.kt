package io.github.sceneview.demo.demos.internal

/**
 * The empty area the line names and the side said for it (the review of u25: four lines in 20 s).
 * The same area is kept while it is still empty, so the line does not hop between areas as the
 * phone moves; its side holds [HOLD_MS] at least, or until that area is filled, and
 * [ScanGuide.turnOf] keeps it across a sway.
 */
class ScanHoleGuide {
    /** The centre of the area named, `null` with none: the live model draws it brighter. */
    var target: Pair<Float, Float>? = null
        private set
    private var turn: ScanTurn? = null
    private var saidAt = Long.MIN_VALUE

    /** The side to say for the phone at [phone] among [holes], [nowMs] on a monotonic clock. */
    fun update(holes: List<Pair<Float, Float>>, phone: DebugPose, nowMs: Long): ScanTurn? {
        fun gap(a: Pair<Float, Float>, x: Float, z: Float) = (a.first - x) * (a.first - x) + (a.second - z) * (a.second - z)
        val before = target
        val kept = before?.let { t -> holes.minByOrNull { gap(it, t.first, t.second) }?.takeIf { gap(it, t.first, t.second) <= KEEP_M2 } }
        val next = kept ?: holes.minByOrNull { gap(it, phone.x, phone.z) }
        target = next
        if (next == null) {
            turn = null
            return null
        }
        val seen = ScanGuide.turnOf(ScanGuide.heading(phone, next.first, next.second).degrees, turn)
        // A new area is said at once; the same one keeps its side for the hold.
        if (kept == null || turn == null || (seen != turn && nowMs - saidAt >= HOLD_MS)) {
            if (seen != turn || kept == null) saidAt = nowMs
            turn = seen
        }
        return turn
    }

    internal companion object {
        /** An area whose centre moved less than ~1.2 m as it filled is the same area. */
        const val KEEP_M2 = 1.44f

        /** The least a side is said for the same area. */
        const val HOLD_MS = 4_500L
    }
}
