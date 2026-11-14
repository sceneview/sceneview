package io.github.sceneview.demo.demos.internal

/**
 * The empty area the line names and the side said for it (the review of u25: four lines in 20 s).
 * The same area is kept while it is still empty, so the line does not hop between areas as the
 * phone moves; its side holds [HOLD_MS] at least, or until that area is filled, and
 * [ScanGuide.turnOf] keeps it across a sway.
 *
 * An area is named only once it has stayed empty for [SETTLE_MS]: the gaps a sweep opens and
 * fills on its way are not worth a line (u30's first ten seconds named three, two of them gone
 * within two seconds).
 */
class ScanHoleGuide {
    /** The point aimed at in the area named, `null` with none. */
    var target: Pair<Float, Float>? = null
        private set

    /** Which area [target] is in: another number each time another area is named. */
    var gapId = 0
        private set

    private var turn: ScanTurn? = null
    private var saidAt = Long.MIN_VALUE

    /** The empty areas of the last call, each with when it was first seen. */
    private var seen = emptyList<Seen>()

    /** The side to say for the phone at [phone] among [holes], [nowMs] on a monotonic clock. */
    fun update(holes: List<Pair<Float, Float>>, phone: DebugPose, nowMs: Long): ScanTurn? {
        val known = seen
        seen = holes.map { hole ->
            val same = known.minByOrNull { gap(it.at, hole.first, hole.second) }
                ?.takeIf { gap(it.at, hole.first, hole.second) <= KEEP_M2 }
            Seen(hole, same?.since ?: nowMs)
        }
        val before = target
        val kept = before?.let { t -> holes.minByOrNull { gap(it, t.first, t.second) }?.takeIf { gap(it, t.first, t.second) <= KEEP_M2 } }
        val next = kept ?: seen.filter { nowMs - it.since >= SETTLE_MS }.minByOrNull { gap(it.at, phone.x, phone.z) }?.at
        target = next
        if (next == null) {
            turn = null
            return null
        }
        if (kept == null) gapId++
        val now = ScanGuide.turnOf(ScanGuide.heading(phone, next.first, next.second).degrees, turn)
        // Another area is said at once; the same one keeps its side for the hold.
        if (kept == null || turn == null || (now != turn && nowMs - saidAt >= HOLD_MS)) {
            if (now != turn || kept == null) saidAt = nowMs
            turn = now
        }
        return turn
    }

    /**
     * The point to hatch round: [target] while the line shown names its area ([named] is
     * [ScanLiveInstruction.namedHole]), none otherwise. The line and the hatching say the same
     * thing or nothing (Codex's review of u30: the hatching moved on while the line still said
     * the side of the area just filled).
     */
    fun marked(named: Int?): Pair<Float, Float>? = target.takeIf { named == gapId }

    private class Seen(val at: Pair<Float, Float>, val since: Long)

    internal companion object {
        /** An area whose centre moved less than ~1.2 m as it filled is the same area. */
        const val KEEP_M2 = 1.44f

        /** The least a side is said for the same area. */
        const val HOLD_MS = 4_500L

        /** How long an area stays empty before it is named. */
        const val SETTLE_MS = 3_000L

        private fun gap(a: Pair<Float, Float>, x: Float, z: Float) = (a.first - x) * (a.first - x) + (a.second - z) * (a.second - z)
    }
}
