package io.github.sceneview.demo.demos

import java.util.Locale
import java.util.concurrent.atomic.AtomicLongArray

/** Independent stage totals from fusion and preview; no shared active-stage state. */
internal class ScanDenseWork(private val clock: () -> Long = System::nanoTime) {
    private val loggedNanos = AtomicLongArray(7)

    fun <T> measure(stage: Int, block: () -> T): T {
        val started = clock()
        try { return block() } finally { loggedNanos.addAndGet(stage, (clock() - started).coerceAtLeast(0L)) }
    }

    fun line(): String = "ScanDenseWork " + listOf("fuse", "merge", "whole", "covered", "weak", "mesh", "atlas")
        .mapIndexed { i, name -> "$name=${"%.1f".format(Locale.ROOT, loggedNanos.getAndSet(i, 0L) / 1e6)}" }.joinToString(" ")

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

/** Logging resets one interval atomically while both workers continue to record. */
internal class ScanDenseTimings {
    private val totals = LongArray(3)
    private val maxima = LongArray(3)
    private val counts = LongArray(3)

    @Synchronized fun add(nanos: Long) = record(0, nanos)
    @Synchronized fun copy(nanos: Long) = record(1, nanos)
    @Synchronized fun build(nanos: Long) = record(2, nanos)

    private fun record(kind: Int, nanos: Long) {
        totals[kind] += nanos
        maxima[kind] = maxOf(maxima[kind], nanos)
        counts[kind]++
    }

    @Synchronized fun line(): String {
        val means = totals.indices.map { i -> "%.1f".format(Locale.ROOT, totals[i] / counts[i].coerceAtLeast(1) / 1e6) }
        val max = maxima.map { "%.1f".format(Locale.ROOT, it / 1e6) }
        val line = "add ${means[0]} ms (max ${max[0]}) mean; " +
            "snapshot copy mean ${means[1]} ms (max ${max[1]}); build duration mean ${means[2]} ms (max ${max[2]})"
        totals.fill(0); maxima.fill(0); counts.fill(0)
        return line
    }
}
