package io.github.sceneview.demo.demos.squat

/**
 * A running min/avg/max over a window of durations, reset each time it is read.
 *
 * Feeds the `SquatCoachPerf` log lines that prove the fix on a phone: render-frame gaps
 * on one side, pose-inference latency on the other. Synchronized because the render
 * thread writes frame gaps while MediaPipe's callback thread writes inference times.
 */
class PerfWindow {
    private var count = 0
    private var sum = 0.0
    private var max = 0.0

    @Synchronized
    fun add(valueMs: Double) {
        count += 1
        sum += valueMs
        if (valueMs > max) max = valueMs
    }

    /** Returns `(count, avgMs, maxMs)` and starts a new window. */
    @Synchronized
    fun drain(): Triple<Int, Double, Double> {
        val result = Triple(count, if (count == 0) 0.0 else sum / count, max)
        count = 0
        sum = 0.0
        max = 0.0
        return result
    }
}
