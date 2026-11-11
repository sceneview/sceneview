package io.github.sceneview.demo.demos.internal

/** One opening quarter-turn, clocked only after a model frame reaches the surface. */
internal class ScanResultTurntable {
    var running = true
        private set
    private var firstFrameNanos: Long? = null
    private var seconds = 0f

    /** Offset from the final (reset) view: -90 degrees at rest, zero exactly at the end. */
    val angle: Float get() = if (running) angleAt(seconds) else 0f

    fun advanceTo(frameTimeNanos: Long) {
        if (!running) return
        val first = firstFrameNanos ?: frameTimeNanos.also { firstFrameNanos = it }
        seconds = maxOf(seconds, ((frameTimeNanos - first) / 1e9).toFloat())
        if (seconds >= DURATION_S) stop()
    }

    /** Never restarts. The caller leaves the camera where the user's finger found it. */
    fun stop() {
        running = false
    }

    companion object {
        const val DURATION_S = 2.5f

        /** Smoothstep has zero angular velocity at both ends and never overshoots. */
        fun angleAt(seconds: Float): Float {
            val t = (seconds / DURATION_S).coerceIn(0f, 1f)
            return -90f * (1f - t * t * (3f - 2f * t))
        }
    }
}
