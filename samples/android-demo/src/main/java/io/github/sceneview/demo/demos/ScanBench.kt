package io.github.sceneview.demo.demos

import android.util.Log

/**
 * The live scan's benchmark line, read by `.claude/scripts/scan_replay_bench.py` from logcat:
 * `ScanBench: {"schema":2,...}`. One `cadence` event per one-second window of AR frames, and one
 * `surface_ready` event the first time the scan covers any floor. Debug builds only — the caller
 * gates it — so a store build never writes it.
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
    }
}
