package io.github.sceneview.demo.demos

import java.util.concurrent.atomic.AtomicLong

/**
 * Capture totals, retained after the dense map and TSDF are released. Busy counts due 100 ms
 * slots consumed while fusion is busy. Stale counts rejected sensor timestamps; unavailable counts missing depth or colour
 * images and unreadable copies. Accepted counts copied depth
 * frames handed to fusion, including a worker that has not finished yet.
 */
internal class ScanFrameCounters {
    private val accepted = AtomicLong()
    private val busy = AtomicLong()
    private val stale = AtomicLong()
    private val unavailable = AtomicLong()
    private val dropped = AtomicLong()

    fun acceptDepth() { accepted.incrementAndGet() }
    fun busyDepth() { busy.incrementAndGet() }
    fun staleDepth() { stale.incrementAndGet() }
    fun unavailableDepth() { unavailable.incrementAndGet() }
    fun dropTsdf() { dropped.incrementAndGet() }

    fun snapshot() = ScanFrameCounts(accepted.get(), busy.get(), stale.get(), dropped.get(), unavailable.get())
}

/** A read of running totals; logging it never resets the capture's counters. */
internal data class ScanFrameCounts(
    val depthAccepted: Long = 0,
    val depthBusy: Long = 0,
    val depthStale: Long = 0,
    val tsdfDropped: Long = 0,
    val depthUnavailable: Long = 0,
) {
    fun logLine() = "depthAccepted=$depthAccepted depthBusy=$depthBusy depthStale=$depthStale " +
        "tsdfDropped=$tsdfDropped depthUnavailable=$depthUnavailable"
}

/** Schema 2 capture losses, independently readable by the replay bench. */
internal fun depthLine(counts: ScanFrameCounts): String =
    """{"schema":2,"event":"depth","depth_accepted":${counts.depthAccepted},""" +
        """"depth_busy":${counts.depthBusy},"depth_stale":${counts.depthStale},""" +
        """"depth_unavailable":${counts.depthUnavailable},"tsdf_dropped":${counts.tsdfDropped}}"""
