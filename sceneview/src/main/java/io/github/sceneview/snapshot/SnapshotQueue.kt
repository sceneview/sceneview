package io.github.sceneview.snapshot

import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Log tag of [ModelSnapshot] and its queue. */
internal const val SNAPSHOT_TAG = "ModelSnapshot"

/** The reason of a request that [ModelSnapshot.destroy] stopped or turned away. */
internal const val SNAPSHOT_DESTROYED = "the snapshot was destroyed"

/**
 * Whether a [ModelSnapshot] may still call Filament: not destroyed by its owner, not released,
 * and its engine still valid. Kept apart from Filament so the JVM tests can drive it.
 */
internal class SnapshotLifetime(private val engineValid: () -> Boolean) {

    /** [ModelSnapshot.destroy] was called: no new render starts, the current one stops. */
    var isDestroyed = false
        private set

    /** The Filament objects are gone (released by the snapshotter, or with the engine). */
    var isReleased = false
        private set

    /**
     * Whether the snapshotter's Filament objects — and a render's model — still exist to be
     * touched: not released yet, and the engine not destroyed (it takes them all with it). A
     * render's cleanup checks it before removing or destroying its model.
     */
    val ownsFilamentObjects: Boolean get() = !isReleased && engineValid()

    /** Checked after every suspension of a render: [destroy] or the engine's end can land there. */
    val isAlive: Boolean get() = !isDestroyed && ownsFilamentObjects

    /** Marks it destroyed; `false` when it already was. */
    fun destroy(): Boolean = !isDestroyed.also { isDestroyed = true }

    /** Marks it released; `false` when it already was. */
    fun release(): Boolean = !isReleased.also { isReleased = true }
}

/**
 * Runs [cleanup] after this error was thrown, without letting a second failure replace it: whatever
 * [cleanup] throws is added to this error's suppressed exceptions.
 */
internal inline fun Throwable.suppressing(cleanup: () -> Unit) {
    try {
        cleanup()
    } catch (failure: Throwable) {
        addSuppressed(failure)
    }
}

/**
 * Whether a render's image is complete, and so may be cached: every texture was decoded before the
 * capture ([texturesReady]), and the model's glTF could be read to rule out quantized positions
 * ([extensionsUsed] is not `null`).
 */
internal fun isComplete(texturesReady: Boolean, extensionsUsed: List<String>?): Boolean =
    texturesReady && extensionsUsed != null

/**
 * Whether [result] goes into the cache: only a complete image (textures all decoded, model fully
 * checked) of a snapshotter still alive once the render returned — never one destroyed meanwhile,
 * whose cache has just been emptied.
 */
internal fun shouldCache(result: SnapshotResult.Rendered, isAlive: Boolean): Boolean =
    result.complete && isAlive

/**
 * The request queue of a [ModelSnapshot], without Filament: one render at a time in the order
 * asked, the cache, and the difference between a caller giving up (its cancellation is rethrown,
 * the next request goes on) and [cancel] stopping everything (the request in progress and those
 * queued get [SNAPSHOT_DESTROYED]).
 *
 * @param cache the thumbnail cache, `null` when disabled.
 * @param isAlive whether renders may still run, checked before each and after it returns.
 * @param dispatcher where requests are serialised and renders run: the main thread in production.
 * @param render renders one key. Cancelled when its caller gives up or on [cancel]; the next
 * request starts only once it has returned.
 */
internal class SnapshotQueue(
    private val cache: LruCache<SnapshotKey, Bitmap>?,
    private val isAlive: () -> Boolean,
    private val dispatcher: CoroutineDispatcher,
    private val render: suspend (SnapshotKey) -> SnapshotResult,
) {
    private val lock = Mutex()

    /** Runs the renders, so [cancel] can stop one mid-load. */
    private val renderScope = CoroutineScope(SupervisorJob() + dispatcher)

    /** A request holds the queue: a render may still be using Filament. */
    val isBusy: Boolean get() = lock.isLocked

    /**
     * One request: from the cache, or queued and rendered. Logs a failure, and caches only what
     * [shouldCache] accepts.
     */
    suspend fun request(key: SnapshotKey): SnapshotResult {
        cache?.get(key)?.let { return SnapshotResult.Rendered(it, complete = true) }
        return withContext(dispatcher) {
            lock.withLock {
                // A request for the same image may have filled the cache while this one waited.
                cache?.get(key)?.let { return@withLock SnapshotResult.Rendered(it, complete = true) }
                if (!isAlive()) return@withLock SnapshotResult.Failed(SNAPSHOT_DESTROYED)
                val job = renderScope.async { render(key) }
                val result = try {
                    job.await()
                } catch (_: CancellationException) {
                    // The caller gave up: rethrown. Otherwise cancel() stopped the render.
                    currentCoroutineContext().ensureActive()
                    SnapshotResult.Failed(SNAPSHOT_DESTROYED)
                } catch (e: Exception) {
                    SnapshotResult.Failed("render failed: $e")
                } finally {
                    // The next request starts once this model is released, never alongside it.
                    withContext(NonCancellable) { job.cancelAndJoin() }
                }
                when (result) {
                    is SnapshotResult.Rendered ->
                        if (shouldCache(result, isAlive())) cache?.put(key, result.bitmap)
                    is SnapshotResult.Failed ->
                        Log.w(SNAPSHOT_TAG, "No thumbnail for ${key.fileLocation}: ${result.reason}")
                }
                result
            }
        }
    }

    /** Stops the render in progress; it and every later request get [SNAPSHOT_DESTROYED]. */
    fun cancel() {
        renderScope.cancel()
    }

    /**
     * [cancel]s, then hands [releaseWhenIdle] the condition its release waits on: the render in
     * progress, stopped, may still hold its model until its cleanup has run on the next main-thread
     * turn, and the requests queued behind it still have to be turned away. Releasing the Filament
     * objects before then would destroy what that render is still removing.
     */
    fun stop(releaseWhenIdle: (isBusy: () -> Boolean) -> Unit) {
        cancel()
        releaseWhenIdle { isBusy }
    }
}
