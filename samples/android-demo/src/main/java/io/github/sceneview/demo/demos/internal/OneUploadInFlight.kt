package io.github.sceneview.demo.demos.internal

/**
 * At most one upload in flight per debug layer (#4750), the policy behind `DebugLayerNode.upload`.
 *
 * Filament does not copy a buffer handed to `setBufferAt` / `setBuffer`: the JNI layer pins it
 * with four global references until the backend has run the upload, which happens at the next
 * presented frame. An AR screen syncs its layers on every `onSessionUpdated` — once a vsync, even
 * when ARCore hands the same camera image back — but presents only on a new image. With the camera
 * frozen (a recording that ended, a stalled feed) the layers kept uploading and nothing was ever
 * consumed: ART's 51 200-entry table overflowed and the app aborted (Pixel 4a, PR #4750).
 *
 * So: an upload [begin]s only when the previous one is fully released; one asked for meanwhile
 * marks the layer dirty, and the last stream released says whether the latest state is owed.
 * Free of Filament so it is tested on the JVM; the layer calls it on the main thread only.
 */
class OneUploadInFlight {
    private var outstanding = 0
    private var dirty = false

    /**
     * Numbers the upload in flight. A release that carries an older number belongs to an upload
     * that was [abort]ed part-way and must not count against the current one.
     */
    private var generation = 0

    /** `true` while Filament still holds a buffer of the last upload. */
    val isInFlight: Boolean get() = outstanding > 0

    /** `true` while an upload asked for during the one in flight is still owed. */
    val isDirty: Boolean get() = dirty

    /**
     * Asks to upload now. `true`: go ahead, then [begin]. `false`: one is in flight — the layer is
     * marked dirty and the latest state goes out once [release] says so.
     */
    fun request(): Boolean {
        if (outstanding > 0) {
            dirty = true
            return false
        }
        return true
    }

    /** An upload of [streams] buffers is handed to Filament; returns its number for [release]. */
    fun begin(streams: Int): Int {
        require(streams > 0) { "an upload hands Filament at least one buffer, got $streams" }
        outstanding = streams
        dirty = false
        return ++generation
    }

    /**
     * Filament let go of one buffer of upload [id]. `true` when it was the last one and an upload
     * was asked for meanwhile: the caller uploads its latest state now.
     */
    fun release(id: Int): Boolean {
        if (id != generation || outstanding == 0) return false
        if (--outstanding > 0) return false
        val owed = dirty
        dirty = false
        return owed
    }

    /**
     * The upload in flight threw part-way: the buffers already handed over still report back, and
     * are ignored; the next [request] goes ahead.
     */
    fun abort() {
        generation++
        outstanding = 0
    }

    /** The layer is gone: nothing is owed any more. */
    fun clear() {
        abort()
        dirty = false
    }
}
