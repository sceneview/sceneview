package io.github.sceneview

import com.google.android.filament.Engine
import com.google.android.filament.Stream
import com.google.android.filament.Texture
import java.util.WeakHashMap

/**
 * Per-[Engine] frame-deferred destroy queue for Filament GPU resources.
 *
 * A [Texture] must not be destroyed while a live [com.google.android.filament.MaterialInstance]
 * still has it bound. `Renderer.beginFrame` — of any renderer on the engine — commits every surface
 * material instance the engine still lists, and aborts natively on the first one that samples a
 * dead texture (`Invalid texture still bound to MaterialInstance`).
 *
 * Destroying the instance first is enough when it happens: `Engine.destroyMaterialInstance` takes
 * the instance off that list in the call, not on a later frame (read in Filament 1.72.1). What
 * this queue covers is a texture released while an instance that samples it is still listed and
 * only goes away a little later — typically because that instance belongs to something destroyed
 * later in the same teardown: Compose disposes a scene's content before the `MaterialLoader` that
 * owns the instances the content used.
 *
 * So a resource is enqueued when its owner releases it, and actually destroyed [GRACE_FRAMES]
 * engine frames later. That is a margin, not a guarantee: an instance that is neither destroyed
 * nor bound to another texture by then still aborts (sceneview/sceneview#4285 was that case, and
 * the fix was to destroy the instance).
 *
 * ### What an engine frame is
 * One pass of the renderers sharing the [Engine], not one `drain` call. Each
 * [SceneRenderer][io.github.sceneview.SceneRenderer] reports its own frames, and the counter
 * advances when a renderer comes back for its next one — so two `SceneView`s on one engine still
 * give a resource [GRACE_FRAMES] frames rather than half of them (sceneview/sceneview#4359). See
 * [DeferredDestroyQueue.drainFrame] for the exact rule and its bounds.
 *
 * ### Threading
 * **Every method must be called on the main thread.** Filament JNI calls (`destroyTexture`,
 * `destroyStream`) are not thread-safe and must run on the render thread — which on Android is the
 * main thread. The queue is driven from
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
     * Enqueues [texture] to be destroyed [GRACE_FRAMES] engine frames from now.
     *
     * Call from the main thread when the texture's owner releases it. Any `MaterialInstance` it
     * is still bound to must be destroyed, or bound to another texture, before the grace period
     * is over.
     *
     * If the owning [Engine] has already been torn down (see [drainAll]) the texture is destroyed
     * immediately rather than queued — there is no render loop left to [drain] it.
     */
    fun enqueueTexture(texture: Texture) {
        queue.enqueue { engine.safeDestroyTexture(texture) }
    }

    /**
     * Enqueues [stream] to be destroyed [GRACE_FRAMES] engine frames from now.
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
     * Enqueues an arbitrary non-Filament [action] to run [GRACE_FRAMES] engine frames from now,
     * sharing the same FIFO ordering as [enqueueTexture]/[enqueueStream].
     *
     * For resources that only need to outlive the same render-loop grace period as a Filament
     * [Texture]/[Stream] — e.g. releasing a [android.view.Surface]/[android.graphics.SurfaceTexture]
     * only after the [Stream] reading from it has actually been destroyed (sceneview/sceneview#3734)
     * — without themselves being Filament-native resources.
     *
     * `internal`: this is a same-module wiring detail, not public API.
     */
    internal fun enqueueAction(action: () -> Unit) {
        queue.enqueue(action)
    }

    /**
     * Advances the frame counter by one, unconditionally, and destroys every resource whose grace
     * period has elapsed. Main thread only.
     *
     * Nothing to call when rendering through `SceneView` or `ARSceneView`: their
     * [SceneRenderer][io.github.sceneview.SceneRenderer] drives the queue. This is for code that
     * owns the engine's frames itself, and must then be called exactly once per engine frame —
     * every call counts as one.
     */
    fun drain() = queue.drain()

    /**
     * Reports one frame of the renderer identified by [source], and drains once per engine frame
     * however many renderers share the [Engine] — see [DeferredDestroyQueue.drainFrame].
     *
     * `internal`: this is how [SceneRenderer][io.github.sceneview.SceneRenderer] drives the queue,
     * not public API.
     */
    internal fun drainFrame(source: Any) = queue.drainFrame(source)

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
         * Number of engine frames a resource is kept alive after being enqueued: the margin the
         * material instance that sampled it has to be destroyed or rebound. Counted in frames of
         * the engine, whatever the number of renderers sharing it.
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
 * fixed number of frames after being enqueued.
 *
 * Extracted so the frame-counting and drain-ordering contract can be exercised by a pure-JVM unit
 * test without a real Filament `Engine`/`Texture`/`Stream`.
 *
 * Not thread-safe — see the threading note on [EngineDestroyQueue]. All calls are main-thread.
 *
 * @param graceFrames Number of frames a deferred action waits before it runs. Must be `>= 0`.
 */
class DeferredDestroyQueue(private val graceFrames: Int) {

    init {
        require(graceFrames >= 0) { "graceFrames must be >= 0, was $graceFrames" }
    }

    private class Entry(val runAtFrame: Long, val action: () -> Unit)

    private val pending = ArrayDeque<Entry>()
    private var frame = 0L

    /**
     * The sources that have reported a frame since the counter last advanced, compared by
     * identity. Emptied on every advance, so it holds at most one entry per renderer that reported
     * during the current frame — see [drainFrame].
     */
    private val frameSources = ArrayList<Any>(2)

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
     * Enqueues [action] to run after [graceFrames] frames.
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
     * Reports one frame of the renderer identified by [source], and advances the counter once per
     * frame of the *engine* however many renderers share this queue (sceneview/sceneview#4359).
     *
     * The rule: the counter advances when a source that already reported since the last advance
     * comes back — its second report can only be its next frame — and that source opens the new
     * frame. A source reporting for the first time since the last advance is rendering the frame
     * already counted, so it joins it without advancing. The very first report advances.
     *
     * What follows, and what `DeferredDestroyQueueTest` pins:
     * - a single source advances on every call, exactly like [drain];
     * - sources that each report at most once per display frame never advance the counter twice
     *   in one, in whatever order they report and whichever of them stop or start reporting;
     * - the counter never falls more than one frame behind any single source. With a renderer
     *   rendering every display frame that makes it one advance per display frame, and a queue
     *   with a renderer still rendering cannot stall.
     *
     * It deliberately does not read the frame timestamp. Two views on the main thread do receive
     * the same Choreographer time, but `SceneRenderer.renderFrame` is public and takes whatever
     * time its caller has: deduplicating on equal timestamps would freeze the queue under a
     * constant one and deduplicate nothing under two clocks.
     *
     * @param source Compared by identity and held until the counter next advances — pass a bare
     * token, not an object that would be a leak to keep.
     */
    internal fun drainFrame(source: Any) {
        if (frameSources.isNotEmpty() && frameSources.none { it === source }) {
            frameSources.add(source)
            return
        }
        frameSources.clear()
        frameSources.add(source)
        drain()
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
