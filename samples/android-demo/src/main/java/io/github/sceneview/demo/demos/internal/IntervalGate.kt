package io.github.sceneview.demo.demos.internal

/**
 * Lets a sample through at most once per [intervalNanos] of frame time — and always the first
 * one it sees. The recorder samples ARCore's feature points and planes through two of these.
 *
 * "Nothing yet" is `null`, never a `Long.MIN_VALUE` sentinel: `now - Long.MIN_VALUE` overflows
 * to a negative number, so with that sentinel the first sample was never due, the gate never
 * opened and a real-device scan recorded no point and no plane at all (#4095).
 *
 * A clock that went back (a session restarted into the same trace) opens the gate again rather
 * than holding it shut until the new clock catches up with the old one.
 */
class IntervalGate(private val intervalNanos: Long) {
    private var last: Long? = null

    /** Whether a sample at [nanos] is due. Does not consume it: [mark] does. */
    fun isDue(nanos: Long): Boolean = isDue(nanos, last, intervalNanos)

    /** Records that a sample was taken at [nanos]. */
    fun mark(nanos: Long) {
        last = nanos
    }

    /** Forgets the last sample: the next one is due whatever its time. */
    fun reset() {
        last = null
    }

    companion object {
        /** `true` for the first sample ([last] `null`), then once [interval] has elapsed since [last]. */
        fun isDue(now: Long, last: Long?, interval: Long): Boolean =
            last == null || now < last || now - last >= interval
    }
}
