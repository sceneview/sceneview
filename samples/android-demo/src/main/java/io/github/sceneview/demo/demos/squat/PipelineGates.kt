package io.github.sceneview.demo.demos.squat

/**
 * The one-inference-at-a-time gate of [SquatPosePipeline], with a **ticket** per request.
 *
 * A plain busy flag had two failure modes once a request went missing (MediaPipe never
 * called back): the frames behind it were dropped until a timeout freed the flag, and when
 * the lost result *did* come back late, it cleared the flag of the newer request in flight
 * and fed the counter a pose read against that newer request's buffers. Here a held slot is
 * reclaimed by the next frame after [stuckAfterNs], and a result only frees the slot — and
 * only counts — when its ticket is still the one holding it.
 *
 * Pure Kotlin, thread-safe: the render thread acquires, MediaPipe's thread releases.
 */
class InferenceSlot(private val stuckAfterNs: Long) {

    private var holder = NO_TICKET
    private var heldSinceNs = 0L

    /** `true` while a request holds the slot (stuck or not). */
    val isHeld: Boolean
        @Synchronized get() = holder != NO_TICKET

    /**
     * Takes the slot for [ticket] when it is free, or when its holder has been out longer
     * than [stuckAfterNs] — that holder is then forgotten and its late result ignored.
     */
    @Synchronized
    fun tryAcquire(ticket: Long, nowNs: Long): Boolean {
        if (holder != NO_TICKET && nowNs - heldSinceNs <= stuckAfterNs) return false
        holder = ticket
        heldSinceNs = nowNs
        return true
    }

    /** Frees the slot if [ticket] still holds it. `false` = a stale result, to be dropped. */
    @Synchronized
    fun release(ticket: Long): Boolean {
        if (holder != ticket) return false
        holder = NO_TICKET
        return true
    }

    /** Frees the slot whoever holds it — for failures that do not say which request failed. */
    @Synchronized
    fun releaseAny() {
        holder = NO_TICKET
    }

    private companion object {
        const val NO_TICKET = Long.MIN_VALUE
    }
}

/**
 * Turns camera frame timestamps into the strictly increasing timestamps MediaPipe's
 * `LIVE_STREAM` mode requires, **across session restarts**.
 *
 * The pipeline outlives the AR session: flipping between the front and the back camera, or
 * a recorded dataset looping, starts a new session whose clock can begin below the last
 * frame seen. A bare `timestamp <= last → skip` guard then skipped every frame of the new
 * session and the coach froze until the old timestamp was passed again. When the clock goes
 * backwards, this rebases it so the next model timestamp follows straight on.
 */
class ModelTimestamps {

    private var lastCameraMs = 0L
    private var lastModelMs = 0L
    private var started = false

    /**
     * The model timestamp for a frame stamped [cameraMs], or `null` when it is the frame
     * already seen (ARCore redraws the same camera image between camera updates).
     */
    @Synchronized
    fun next(cameraMs: Long): Long? {
        if (!started) {
            started = true
            lastCameraMs = cameraMs
            lastModelMs = cameraMs
            return cameraMs
        }
        if (cameraMs == lastCameraMs) return null
        // Forward: keep the camera's spacing. Backwards: a new session — follow straight on.
        val step = if (cameraMs > lastCameraMs) cameraMs - lastCameraMs else 1L
        lastCameraMs = cameraMs
        lastModelMs += step
        return lastModelMs
    }
}
