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

/**
 * Debug measurement of one slide: the longest presented-frame interval from the first frame
 * of a switch to its landing. A cold first draw of a material shows up here as one long frame.
 */
internal class MatterSwitchFrames {
    data class Slide(val material: FeelMaterial, val worstMillis: Long, val totalMillis: Long)
    private var last = 0L
    private var first = 0L
    private var worst = 0L
    private var measuring = false

    fun frame(nanos: Long, switching: Boolean, displayed: FeelMaterial): Slide? {
        if (!measuring) {
            if (!switching) return null
            // The interval before the request is idle time, not part of the slide.
            measuring = true
            first = nanos; last = nanos; worst = 0L
            return null
        }
        worst = maxOf(worst, nanos - last)
        last = nanos
        if (switching) return null
        measuring = false
        return Slide(displayed, worst / 1_000_000L, (nanos - first) / 1_000_000L)
    }
}

/**
 * Debug measurement of one hidden warm draw: the longest presented-frame interval from its
 * first draw until [TAIL_FRAMES] frames after its last one. The stall of a first draw lands one
 * to three frames after it, so the interval of the draw frame alone always reads 16 ms.
 */
internal class MatterWarmFrames {
    data class Warm(val material: FeelMaterial, val worstMillis: Long, val intervals: Int)
    private var material: FeelMaterial? = null
    private var last = 0L
    private var worst = 0L
    private var intervals = 0
    private var after = 0

    /** [warming]: the material drawn hidden on THIS frame, or null. */
    fun frame(nanos: Long, warming: FeelMaterial?): Warm? {
        val current = material
        if (current == null) {
            if (warming == null) return null
            material = warming
            last = nanos; worst = 0L; intervals = 0; after = 0
            return null
        }
        worst = maxOf(worst, nanos - last)
        last = nanos
        intervals++
        if (warming == current) return null
        if (++after < TAIL_FRAMES) return null
        material = null
        return Warm(current, worst / 1_000_000L, intervals)
    }

    companion object {
        /** Frames measured after the last warm draw; at most [MatterPrewarm.GAP_FRAMES]. */
        const val TAIL_FRAMES = 5
    }
}
