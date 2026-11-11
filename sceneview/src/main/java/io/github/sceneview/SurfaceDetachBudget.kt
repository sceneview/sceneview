package io.github.sceneview

/**
 * One wait budget for a scene disposal, including its mirrors.
 *
 * Starts at the first wait so a disposal without swap chains does not consume a budget.
 * Later waits include elapsed callback and JNI time. A zero remaining budget must still
 * be passed to flushAndWait: it submits queued swap-chain destruction without another wait.
 */
internal class SurfaceDetachBudget(
    private val timeoutNanos: Long = SURFACE_DETACH_WAIT_NANOS,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var startedAt: Long? = null

    fun remainingNanos(): Long {
        val now = nanoTime()
        val start = startedAt ?: now.also { startedAt = it }
        val elapsed = (now - start).coerceAtLeast(0L)
        return (timeoutNanos - elapsed).coerceAtLeast(0L)
    }
}
