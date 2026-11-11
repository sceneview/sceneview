package io.github.sceneview

/**
 * One wait budget for a scene disposal, including its mirrors.
 *
 * Only time spent inside waits consumes the budget; callbacks and other JNI calls do not.
 * A disposal without swap chains consumes nothing. A zero remaining budget must still
 * be passed to flushAndWait: it submits queued swap-chain destruction without another wait.
 */
internal class SurfaceDetachBudget(
    timeoutNanos: Long = SURFACE_DETACH_WAIT_NANOS,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var remaining = timeoutNanos.coerceAtLeast(0L)

    var lastWaitNanos: Long = 0L
        private set

    fun remainingNanos(): Long = remaining

    fun waitFor(flushAndWait: (timeoutNanos: Long) -> Boolean): Boolean {
        val start = nanoTime()
        try {
            return flushAndWait(remaining)
        } finally {
            lastWaitNanos = (nanoTime() - start).coerceAtLeast(0L)
            remaining = (remaining - lastWaitNanos).coerceAtLeast(0L)
        }
    }
}
