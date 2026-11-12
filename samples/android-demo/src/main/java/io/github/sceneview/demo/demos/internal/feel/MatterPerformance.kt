package io.github.sceneview.demo.demos.internal.feel

/** Policy changes only at phase boundaries, never once per frame. */
internal enum class MatterCadence { Active, Orbit, Parked }

/** Presented-callback intervals, not CPU execution duration or GPU timer queries. */
internal class MatterFrameTimes {
    data class Window(val averageMillis: Double, val fps: Double, val intervals: Int)
    private var last: Long? = null
    private var elapsed = 0L
    private var intervals = 0

    fun reset() { last = null; elapsed = 0L; intervals = 0 }

    fun frame(nanos: Long): Window? {
        val previous = last
        last = nanos
        if (previous == null || nanos <= previous) return null
        elapsed += nanos - previous
        intervals++
        if (elapsed < 1_000_000_000L) return null
        val result = Window(elapsed / 1_000_000.0 / intervals,
            intervals * 1_000_000_000.0 / elapsed, intervals)
        elapsed = 0L; intervals = 0
        return result
    }
}
