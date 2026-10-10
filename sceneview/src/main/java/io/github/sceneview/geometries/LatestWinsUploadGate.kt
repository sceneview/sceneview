package io.github.sceneview.geometries

/**
 * Back-pressure for uploads whose consumer runs behind the producer: **at most one upload is in
 * flight**, and everything submitted meanwhile collapses into a single pending value.
 *
 * ## Why a geometry needs it (#4365)
 *
 * Filament's `VertexBuffer.setBufferAt` / `IndexBuffer.setBuffer` do not copy the buffer they are
 * handed. The JNI layer pins it — a `JniBufferCallback` holding **four JNI global references** per
 * call for a direct buffer (the buffer, plus the `NioUtils`, `Handler` and `Executor` classes its
 * helpers look up) — and only lets go once the backend thread has executed the upload command.
 * That command sits in Filament's command stream until the next `Renderer.endFrame` / `flush`.
 *
 * Nothing ties the rate a caller *produces* uploads at to the rate Filament *consumes* them. An
 * animated geometry is updated from Compose's frame clock; Filament consumes on presented frames.
 * Before the first frame — no swap chain yet, or a backend thread still compiling materials on a
 * loaded host — the first runs at 60 Hz and the second at zero, so every update adds its pins to
 * a pile that never drains. ART caps the global reference table at 51 200 entries: 17 tubes × 3
 * attribute streams × 4 references is ~200 references per Compose frame, and the Lines & Paths
 * demo aborted the process after about four seconds of that.
 *
 * #3715 halved the references per upload (direct buffers instead of heap ones). This bounds their
 * **number**: while an upload is in flight nothing else is handed to Filament, so the live
 * references per geometry are a small constant however long the backend stalls.
 *
 * ## Latest wins
 *
 * A vertex stream fully replaces the previous one, so an update that was superseded before
 * Filament ever saw it carries no information — uploading it would only cost a copy. The gate
 * therefore keeps one pending value and folds each new submission into it with [merge]. When the
 * in-flight upload is released, that one value goes out. The state a caller set last is always
 * the state that ends up on screen; the ones set between the upload in flight and that last one
 * are skipped.
 *
 * The cost is in *when*, not in *what*: the upload in flight is not recalled, so a frame drawn
 * while a newer value waits shows the older one, and the newer one a frame later — where two
 * ungated uploads issued before the same flush would both have landed in it.
 *
 * Free of any Filament or Android type so the policy is tested on the JVM.
 *
 * @param merge folds a newer submission into the pending one. Called with the pending value
 * first; for independent streams keep the newest of each.
 * @param canUpload asked just before a *pending* value goes out — by then the target may have
 * been destroyed. `false` drops the value and leaves the gate idle.
 * @param upload hands [T] to the consumer and calls `onReleased` exactly once when the consumer
 * is done with it. May call it synchronously.
 * @param onDeferredUpload called after a pending value was uploaded, i.e. for an upload that did
 * not happen inside the caller's own [submit]. This is where a render-on-demand view is woken:
 * nothing else tells it the picture changed.
 */
internal class LatestWinsUploadGate<T : Any>(
    private val merge: (pending: T, next: T) -> T,
    private val canUpload: (T) -> Boolean,
    private val upload: (value: T, onReleased: () -> Unit) -> Unit,
    private val onDeferredUpload: (T) -> Unit = {},
) {
    private var inFlight = false
    private var pending: T? = null

    /**
     * Identifies the upload currently in flight. A release that carries an older number belongs to
     * an upload the gate already gave up on (it threw part-way) and must not reopen it.
     */
    private var generation = 0L

    /** `true` when nothing is in flight and nothing is waiting. */
    val isIdle: Boolean
        @Synchronized get() = !inFlight && pending == null

    /** `true` while a submission is waiting for the in-flight upload to be released. */
    val hasPending: Boolean
        @Synchronized get() = pending != null

    /**
     * Uploads [value] now when nothing is in flight, otherwise folds it into the pending value.
     *
     * An exception thrown by the upload propagates to the caller and leaves the gate open.
     */
    @Synchronized
    fun submit(value: T) {
        if (inFlight) {
            pending = pending?.let { merge(it, value) } ?: value
        } else {
            start(value)
        }
    }

    /**
     * Forgets the pending value, if any. For a target that was replaced wholesale: what was
     * waiting described the old one.
     */
    @Synchronized
    fun discardPending() {
        pending = null
    }

    private fun start(value: T) {
        val id = ++generation
        inFlight = true
        try {
            upload(value) { onReleased(id) }
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // Part of the upload may already be with the consumer and will still report back;
            // retiring the generation makes those reports no-ops instead of reopening a gate
            // that a later upload holds.
            if (generation == id) {
                generation++
                inFlight = false
            }
            throw e
        }
    }

    @Synchronized
    private fun onReleased(id: Long) {
        if (id != generation || !inFlight) return
        inFlight = false
        val next = pending ?: return
        pending = null
        if (!canUpload(next)) return
        start(next)
        onDeferredUpload(next)
    }
}

/**
 * Calls [onAllReleased] once, after [release] was called [count] times.
 *
 * One upload is several Filament calls — a `setBufferAt` per attribute stream, plus the index
 * buffer — and each reports its own release. The gate reopens on the last one.
 */
internal class ReleaseCountdown(count: Int, private val onAllReleased: () -> Unit) {

    init {
        require(count > 0) { "An upload has at least one stream, got $count." }
    }

    private var remaining = count

    fun release() {
        val done = synchronized(this) {
            if (remaining <= 0) return
            --remaining == 0
        }
        if (done) onAllReleased()
    }
}
