package io.github.sceneview.demo.demos

/** CPU timings exclude time suspended between slices. Never used to change snapshot contents. */
internal class ScanDenseWork {
    val nanos = LongArray(7)
    private val loggedNanos = java.util.concurrent.atomic.AtomicLongArray(7)
    private var active = MERGE
    private var started = 0L
    private var running = false

    fun clear() = nanos.fill(0)

    fun resume() { running = true; started = System.nanoTime() }
    fun pause() {
        if (running) record(active, System.nanoTime() - started)
        running = false
    }
    fun stage(stage: Int) { pause(); active = stage; resume() }

    fun <T> measure(stage: Int, block: () -> T): T {
        val started = System.nanoTime()
        try { return block() } finally { record(stage, System.nanoTime() - started) }
    }

    private fun record(stage: Int, elapsed: Long) {
        nanos[stage] += elapsed
        loggedNanos.addAndGet(stage, elapsed)
    }

    fun line(): String = "ScanDenseWork " + listOf("fuse", "merge", "whole", "covered", "weak", "mesh", "atlas")
        .mapIndexed { i, name -> "$name=${"%.1f".format(java.util.Locale.ROOT, loggedNanos.getAndSet(i, 0L) / 1e6)}" }.joinToString(" ")

    companion object {
        const val FUSE = 0
        const val MERGE = 1
        const val WHOLE = 2
        const val COVERED = 3
        const val WEAK = 4
        const val MESH = 5
        const val ATLAS = 6
    }
}

/** Logging reads and resets an interval atomically while the worker continues to record. */
internal class ScanDenseTimings {
    private val totals = LongArray(2)
    private val maxima = LongArray(2)
    private val counts = LongArray(2)

    @Synchronized fun add(nanos: Long) = record(0, nanos)
    @Synchronized fun deferred(nanos: Long) = record(1, nanos)

    private fun record(kind: Int, nanos: Long) {
        totals[kind] += nanos
        maxima[kind] = maxOf(maxima[kind], nanos)
        counts[kind]++
    }

    @Synchronized fun line(): String {
        val means = totals.indices.map { i ->
            "%.1f".format(java.util.Locale.ROOT, totals[i] / counts[i].coerceAtLeast(1) / 1e6)
        }
        val max = maxima.map { "%.1f".format(java.util.Locale.ROOT, it / 1e6) }
        // Keep the replay analyzer's existing `add N ms (max N)` fields readable.
        val line = "add ${means[0]} ms (max ${max[0]}) mean; " +
            "deferred snapshot mean ${means[1]} ms (max ${max[1]})"
        totals.fill(0); maxima.fill(0); counts.fill(0)
        return line
    }
}
