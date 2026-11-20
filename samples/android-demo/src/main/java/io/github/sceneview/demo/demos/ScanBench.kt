package io.github.sceneview.demo.demos

import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/**
 * The live scan's benchmark line, read by `.claude/scripts/scan_replay_bench.py` from logcat:
 * `ScanBench: {"schema":2,...}`. One `cadence` event per one-second window of AR frames, and one
 * `surface_ready` event the first time the scan covers any floor. Debug builds only — the caller
 * gates it — so a store build never writes it. Capture also emits a cumulative `depth` event
 * through [depthLine], including the totals retained after the live model is released.
 *
 * `outside_elements` is sent as `null`: the live scan does not yet count surfels outside the
 * closed walls' hull, and the bench reads `null` as "not measured", never as zero.
 */
internal class ScanBench(
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val sink: (String) -> Unit = { Log.i(TAG, it) },
) {
    private var windowStartMs = -1L
    private var frames = 0
    private var surfaceSent = false

    /** One AR frame. [tracking] is ARCore's tracking state name; [coveredSquareMetres] the floor covered so far. */
    fun frame(tracking: String, coveredSquareMetres: Float) {
        val now = clockMs()
        if (windowStartMs < 0) windowStartMs = now
        if (now - windowStartMs >= WINDOW_MS) {
            sink(
                """{"schema":2,"event":"cadence","fps":$frames,"window_start_epoch_ms":$windowStartMs,""" +
                    """"tracking_state":"$tracking","covered_m2":${area(coveredSquareMetres)}}""",
            )
            windowStartMs = now
            frames = 0
        }
        frames++
        if (!surfaceSent && coveredSquareMetres > 0f) {
            surfaceSent = true
            sink(
                """{"schema":2,"event":"surface_ready","surface_ready":true,""" +
                    """"covered_m2":${area(coveredSquareMetres)},"outside_elements":null}""",
            )
        }
    }

    private fun area(m2: Float) = if (m2.isFinite()) "%.2f".format(java.util.Locale.ROOT, m2) else "null"

    companion object {
        const val TAG = "ScanBench"
        const val WINDOW_MS = 1_000L

        /** Cumulative capture losses, independently readable by the replay bench. */
        fun depthLine(counts: ScanFrameCounts): String =
            """{"schema":2,"event":"depth","depthAccepted":${counts.depthAccepted},""" +
                """"depthBusy":${counts.depthBusy},"depthStale":${counts.depthStale},"tsdfDropped":${counts.tsdfDropped}}"""
    }
}

/**
 * Capture totals, retained after the dense map and TSDF are released. Busy counts attempts
 * after the existing cadence gate, not unique sensor timestamps. Stale includes duplicate or
 * unavailable depth, missing colour images and unreadable copies; accepted counts copied depth
 * frames handed to fusion, including a worker that has not finished yet.
 */
internal class ScanFrameCounters {
    private val accepted = AtomicLong()
    private val busy = AtomicLong()
    private val stale = AtomicLong()
    private val dropped = AtomicLong()

    fun acceptDepth() { accepted.incrementAndGet() }
    fun busyDepth() { busy.incrementAndGet() }
    fun staleDepth() { stale.incrementAndGet() }
    fun dropTsdf() { dropped.incrementAndGet() }

    fun snapshot() = ScanFrameCounts(accepted.get(), busy.get(), stale.get(), dropped.get())
}

/** A read of running totals; logging it never resets the capture's counters. */
internal data class ScanFrameCounts(
    val depthAccepted: Long = 0,
    val depthBusy: Long = 0,
    val depthStale: Long = 0,
    val tsdfDropped: Long = 0,
) {
    fun logLine() = "depthAccepted=$depthAccepted depthBusy=$depthBusy depthStale=$depthStale tsdfDropped=$tsdfDropped"
}
