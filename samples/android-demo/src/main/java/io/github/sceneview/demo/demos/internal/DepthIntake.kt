package io.github.sceneview.demo.demos.internal

import java.util.concurrent.atomic.AtomicIntegerArray

/**
 * What became of the raw-depth frames a scan asked ARCore for, counted so a device log says why
 * the dense map stops growing (`adb logcat -s RerunScan`).
 *
 * A Pixel 9 scan held its dense count at 80 k, then at 145 k for ~45 s while it kept taking
 * photos, and grew again only after ARCore lost and found tracking. Nothing in the scan's own
 * state resets then — its gates, its last depth timestamp, its fusion — so what changed was what
 * ARCore gave it; these counts name which part.
 *
 * Thread-safe: the frame loop counts on the main thread, the fusion on a worker.
 */
class DepthIntake(intervalNanos: Long = LOG_INTERVAL_NS) {
    /** One outcome per depth frame asked for. */
    enum class Outcome(val label: String) {
        /** The previous fusion still runs: this depth is skipped, never queued. */
        Busy("busy"),

        /** ARCore had no raw depth to give. */
        NoDepth("none"),

        /** ARCore gave the depth already fused: its timestamp has not moved. */
        Stale("stale"),

        /** No camera image to colour it with. */
        NoImage("noImage"),

        /** No confidence image of its size: unfiltered raw depth is not kept. */
        Unreadable("unreadable"),

        /** Read, but no pixel was confident, near and face-on enough to keep. */
        NoSamples("empty"),

        /** Kept samples, all in voxels the map already had. */
        NothingNew("known"),

        /** Added voxels to the map. */
        Grew("grew"),
    }

    private val counts = AtomicIntegerArray(Outcome.entries.size)
    private val gate = IntervalGate(intervalNanos)

    /** Counts one depth frame's [outcome]. */
    fun count(outcome: Outcome) {
        counts.incrementAndGet(outcome.ordinal)
    }

    /** How many depth frames ended in [outcome] since the last [lineIfDue]. */
    operator fun get(outcome: Outcome): Int = counts.get(outcome.ordinal)

    /**
     * "depth frames: grew=3 known=1 stale=12 …" — the outcomes counted since the last line, with
     * the dense map's [total] — when a line is due at [nanos]; `null` otherwise. Starts over.
     */
    fun lineIfDue(nanos: Long, total: Int): String? {
        if (!gate.isDue(nanos)) return null
        gate.mark(nanos)
        val parts = Outcome.entries.mapNotNull { outcome ->
            val n = counts.getAndSet(outcome.ordinal, 0)
            if (n > 0) "${outcome.label}=$n" else null
        }
        if (parts.isEmpty()) return null
        return "depth frames: ${parts.joinToString(" ")} total=$total"
    }

    companion object {
        /** One line every two seconds: a stall of a few seconds shows over a line or two. */
        const val LOG_INTERVAL_NS = 2_000_000_000L
    }
}
