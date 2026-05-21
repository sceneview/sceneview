package io.github.sceneview

import com.google.android.filament.Engine
import com.google.android.filament.Stream
import com.google.android.filament.Texture
import java.util.WeakHashMap

/**
 * Per-[Engine] frame-deferred destroy queue for Filament GPU resources.
 *
 * Some Filament resources cannot be destroyed eagerly the moment a node releases them:
 * destroying a [Texture] before Filament has reclaimed the [com.google.android.filament.MaterialInstance]
 * it was bound to triggers a native `SIGABRT` (`Invalid texture still bound to MaterialInstance`).
 * MaterialInstance reclamation is coupled to the render loop, not to the `destroy()` call — so the
 * texture must outlive the MI by at least one rendered frame.
 *
 * This queue solves that without leaking: a resource is enqueued on the frame its MI is released,
 * then actually destroyed [GRACE_FRAMES] rendered frames later, by which point Filament has
 * guaranteed the MI is gone.
 *
 * ### Threading
 * **Every method must be called on the main thread.** Filament JNI calls (`destroyTexture`,
 * `destroyStream`) are not thread-safe and must run on the render thread — which on Android is the
 * main thread. [drain] is called once per frame from
 * [SceneRenderer.renderFrame][io.github.sceneview.SceneRenderer.renderFrame], which always runs on
 * the main thread via `withFrameNanos`. Enqueue happens from `Node.destroy()`, also main-thread.
 *
 * ### Lifecycle
 * Each [Engine] gets exactly one queue, looked up via [of]. When the [Engine] is torn down,
 * [drainAll] (called from [Engine.safeDestroy]) destroys every still-pending resource immediately
 * — Filament reclaims everything at engine teardown anyway, so the grace period no longer applies.
 *
 * After teardown the engine cannot be resurrected: a stale [of] call (e.g. a `Node.destroy()` that
 * runs after [Engine.safeDestroy]) returns an already-drained queue whose [enqueueTexture]/
 * [enqueueStream] destroy the resource immediately rather than queueing it onto a dead engine
 * (sceneview/sceneview#1630).
 *
 * The frame-counting and FIFO drain-ordering logic lives in [DeferredDestroyQueue] so it can be
 * unit-tested without a Filament [Engine]; this class is the thin Filament-typed binding.
 *
 * Surfaced from sceneview/sceneview#874.
 */
class EngineDestroyQueue private constructor(
    private val engine: Engine,
    /**
     * `true` if the owning [Engine] was already torn down when this queue was obtained from [of].
     * Such a queue is created already-drained so that any [enqueueTexture]/[enqueueStream]
     * destroys immediately instead of queueing onto a dead engine (sceneview/sceneview#1630).
     */
    alreadyDestroyed: Boolean = false,
) {

    private val queue = DeferredDestroyQueue(GRACE_FRAMES).apply {
        if (alreadyDestroyed) drainAll()
    }

    /** Number of resources currently waiting to be destroyed. Exposed for tests. */
    val size: Int get() = queue.size

    /**
     * Enqueues [texture] to be destroyed [GRACE_FRAMES] rendered frames from now.
     *
     * Call from the main thread, right after the bound `MaterialInstance` has been released.
     *
     * If the owning [Engine] has already been torn down (see [drainAll]) the texture is destroyed
     * immediately rather than queued — there is no render loop left to [drain] it.
     */
    fun enqueueTexture(texture: Texture) {
        queue.enqueue { engine.safeDestroyTexture(texture) }
    }

    /**
     * Enqueues [stream] to be destroyed [GRACE_FRAMES] rendered frames from now.
     *
     * Call from the main thread, right after the bound [Texture] has been enqueued/released.
     *
     * If the owning [Engine] has already been torn down (see [drainAll]) the stream is destroyed
     * immediately rather than queued — there is no render loop left to [drain] it.
     */
    fun enqueueStream(stream: Stream) {
        queue.enqueue { engine.safeDestroyStream(stream) }
    }

    /**
     * Advances the frame counter by one and destroys every resource whose grace period has
     * elapsed. Call exactly once per rendered frame, on the main thread.
     */
    fun drain() = queue.drain()

    /**
     * Destroys every still-pending resource immediately, ignoring the grace period, and marks the
     * owning [Engine] as torn down.
     *
     * Called from [Engine.safeDestroy] at engine teardown — the [Engine] reclaims all native
     * resources at that point, so deferring no longer serves any purpose and would leak the
     * Java-side handles. After this call any further [enqueueTexture]/[enqueueStream] destroys
     * immediately instead of queueing onto the dead engine.
     */
    fun drainAll() {
        queue.drainAll()
        // Drop the live entry: it is no longer needed, and a WeakHashMap whose value (this queue)
        // strongly references its key (the engine) would otherwise pin the dead engine forever.
        // The engine is recorded as destroyed so a later of() returns a destroyed queue, not a
        // fresh live one (sceneview/sceneview#1630).
        queues.remove(engine)
        destroyedEngines[engine] = Unit
    }

    companion object {
        /**
         * Number of rendered frames a resource is kept alive after being enqueued. One frame is
         * sufficient for Filament to reclaim the `MaterialInstance`; a small margin is kept for
         * safety against frame-pacing jitter.
         */
        const val GRACE_FRAMES = 3

        private val queues = WeakHashMap<Engine, EngineDestroyQueue>()

        /**
         * Marker set of engines whose queue has already been torn down via [drainAll]. Keyed
         * weakly so the record disappears once the dead [Engine] itself is garbage-collected; the
         * value is a placeholder and never references the key, so the engine stays collectable.
         *
         * A torn-down queue is *removed* from [queues] (to break the value-strongly-references-key
         * leak inherent to a `WeakHashMap` whose value holds its key). Without this marker a stale
         * [of] call after teardown — e.g. a `Node.destroy()` running after [Engine.safeDestroy],
         * an ordering that does happen — would `getOrPut` a *fresh, live* queue against the dead
         * engine, resurrecting it and queueing destroys that no render loop will ever [drain].
         */
        private val destroyedEngines = WeakHashMap<Engine, Unit>()

        /**
         * Returns the [EngineDestroyQueue] for [engine], creating it on first access.
         *
         * Main-thread only.
         *
         * Once an [Engine] has been torn down (its queue [drainAll]'d), this returns a queue that
         * is already marked destroyed rather than resurrecting a fresh, live one: any
         * [enqueueTexture]/[enqueueStream] on it then destroys the resource immediately instead of
         * queueing it onto the dead engine (sceneview/sceneview#1630). That transient queue is not
         * retained in [queues], so it cannot pin the dead engine.
         */
        @JvmStatic
        fun of(engine: Engine): EngineDestroyQueue =
            if (engine in destroyedEngines) {
                EngineDestroyQueue(engine, alreadyDestroyed = true)
            } else {
                queues.getOrPut(engine) { EngineDestroyQueue(engine) }
            }
    }
}

/**
 * Filament-free core of [EngineDestroyQueue]: a FIFO queue of deferred actions, each released a
 * fixed number of [drain] calls ("frames") after being enqueued.
 *
 * Extracted so the frame-counting and drain-ordering contract can be exercised by a pure-JVM unit
 * test without a real Filament `Engine`/`Texture`/`Stream`.
 *
 * Not thread-safe — see the threading note on [EngineDestroyQueue]. All calls are main-thread.
 *
 * @param graceFrames Number of [drain] calls a deferred action waits before it runs. Must be `>= 0`.
 */
class DeferredDestroyQueue(private val graceFrames: Int) {

    init {
        require(graceFrames >= 0) { "graceFrames must be >= 0, was $graceFrames" }
    }

    private class Entry(val runAtFrame: Long, val action: () -> Unit)

    private val pending = ArrayDeque<Entry>()
    private var frame = 0L

    /**
     * Set once [drainAll] has run. Past that point there is no render loop left to advance [drain],
     * so a deferred action would never run — [enqueue] runs it immediately instead. This is what
     * stops a `Node.destroy()` arriving after engine teardown from queueing onto a dead engine
     * (sceneview/sceneview#1630).
     */
    private var drainedAll = false

    /** Number of actions currently waiting to run. */
    val size: Int get() = pending.size

    /**
     * Enqueues [action] to run [graceFrames] [drain] calls from now.
     *
     * If [drainAll] has already run, [action] is executed immediately instead of being queued —
     * there is no render loop left to [drain] it.
     */
    fun enqueue(action: () -> Unit) {
        if (drainedAll) {
            action()
        } else {
            pending.addLast(Entry(frame + graceFrames, action))
        }
    }

    /**
     * Advances the frame counter by one and runs every action whose grace period has elapsed.
     *
     * Actions are enqueued FIFO and all share the same [graceFrames] delay, so once the head item
     * is not yet due no later item can be either — the drain stops early.
     */
    fun drain() {
        frame++
        while (true) {
            val head = pending.firstOrNull() ?: break
            if (head.runAtFrame > frame) break
            pending.removeFirst().action()
        }
    }

    /**
     * Runs every still-pending action immediately, ignoring the grace period, in FIFO order, and
     * marks the queue drained: any later [enqueue] runs its action immediately rather than
     * deferring it.
     */
    fun drainAll() {
        drainedAll = true
        while (pending.isNotEmpty()) {
            pending.removeFirst().action()
        }
    }
}
