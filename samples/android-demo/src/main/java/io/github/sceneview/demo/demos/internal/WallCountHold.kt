package io.github.sceneview.demo.demos.internal

/**
 * The wall count the capture's status card says (#4903): the live count ([PlaneWallSource]'s walls)
 * held steady. ARCore refits its planes all the time — a fifth plane comes and goes, a refit of the
 * floor's planes drops most walls for a few seconds until they are recognised again — and the card read
 * `4 of 4 walls`, `5 walls`, `1 of 4 walls`, `4 of 4 walls` from one second to the next on a real
 * walk. Here a count changes only once the live one has stayed on the same side of it for a
 * while: [RISE_MS] to go up, [DROP_MS] to go down, and then to the count that held throughout
 * (the lowest seen on the way up, the highest on the way down). A transient never shows.
 * Presentation only: what the review reports, the gauge and the room's completion read the live
 * walls. Times are in milliseconds, from any monotonic clock.
 */
class WallCountHold(initial: Int = 0) {
    /** The count shown. */
    var shown: Int = initial
        private set

    /** When the live count left [shown], `-1` while it is on it. */
    private var since = -1L

    /** The live count went above [shown] (`true`) or below it (`false`) at [since]. */
    private var rising = false

    /** The count that held since [since]: the lowest seen above [shown], or the highest below it. */
    private var held = 0

    /** [live], the count now at [nowMs]: the count to show. */
    fun update(live: Int, nowMs: Long): Int {
        if (live == shown) {
            since = -1L
            return shown
        }
        val up = live > shown
        if (since < 0 || up != rising) {
            since = nowMs
            rising = up
            held = live
        } else {
            held = if (up) minOf(held, live) else maxOf(held, live)
        }
        if (nowMs - since >= if (up) RISE_MS else DROP_MS) {
            shown = held
            since = -1L
        }
        return shown
    }

    /** A new capture: no wall yet, shown at once. */
    fun reset() {
        shown = 0
        since = -1L
    }

    companion object {
        /** How long a higher count holds before the card says it: a wall is seen for good. */
        const val RISE_MS = 2_000L

        /**
         * How long a lower count holds before the card says it. Longer than the walls took to be
         * recognised again after a refit (six seconds on the walk that showed `1 of 4 walls`): a
         * wall the room had is not taken away for a refit.
         */
        const val DROP_MS = 8_000L
    }
}
