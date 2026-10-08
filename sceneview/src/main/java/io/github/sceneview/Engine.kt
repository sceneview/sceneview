package io.github.sceneview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.Fence
import com.google.android.filament.IndexBuffer
import com.google.android.filament.IndirectLight
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.Scene
import com.google.android.filament.Skybox
import com.google.android.filament.Stream
import com.google.android.filament.Texture
import com.google.android.filament.VertexBuffer
import com.google.android.filament.View
import com.google.android.filament.gltfio.AssetLoader
import io.github.sceneview.environment.Environment
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.model.Model

fun Engine.createModelLoader(context: Context) = ModelLoader(this, context)
fun Engine.createMaterialLoader(context: Context) = MaterialLoader(this, context)
fun Engine.createEnvironmentLoader(context: Context) = EnvironmentLoader(this, context)

fun Engine.createCamera() = createCamera(entityManager.create())

fun AssetLoader.safeDestroyModel(model: Model) {
    runCatching { model.releaseSourceData() }
    runCatching { destroyAsset(model) }
}

fun Engine.safeDestroy() = runCatching {
    destroy()
    Log.d("Sceneview", "Engine destroyed")
}

/**
 * How long [whenBackendIdle] waits on its fence before it gives the thread back: one frame at
 * 60 Hz. A backend with nothing queued reaches the fence well within it, so the usual teardown
 * stays synchronous, exactly as it was before the wait became a poll.
 */
internal const val BACKEND_IDLE_FIRST_WAIT_NANOS = 16_000_000L

/** The shortest delay between two checks of a teardown deferred by [whenBackendIdle]: one frame. */
internal const val BACKEND_IDLE_POLL_MS = 16L

/** The longest delay between two checks, reached after 8 s of waiting. */
internal const val BACKEND_IDLE_POLL_MAX_MS = 500L

/**
 * A deferred teardown that is still waiting gets one warning in the log once it has waited this
 * long. It keeps waiting: a backend that is this late still drains.
 */
internal const val BACKEND_IDLE_WARN_MS = 10_000L

/**
 * How long a deferred teardown keeps checking before it gives up, with one error in the log, and
 * leaves the engine alive. Nine times the slowest drain measured, 13.8 s on an emulator under
 * load: what is still busy after that is stuck, and destroying the engine would block the thread
 * for good.
 */
internal const val BACKEND_IDLE_BUDGET_MS = 120_000L

/**
 * How long a surface detach waits for the backend to finish with the surface, in nanoseconds.
 * Long enough for the frames in flight, far below the 5 s after which Android reports an ANR.
 */
internal const val SURFACE_DETACH_WAIT_NANOS = 1_000_000_000L

/**
 * How long a surface resize waits for the frames already queued, in nanoseconds. Half the detach
 * bound: the surface stays, so a frame that misses it only reaches the surface at its former size.
 */
internal const val SURFACE_RESIZE_WAIT_NANOS = 500_000_000L

/**
 * The delay before the next check of a teardown deferred by [whenBackendIdle] that has already
 * waited [waitedMs]: a sixteenth of it, between [BACKEND_IDLE_POLL_MS] and
 * [BACKEND_IDLE_POLL_MAX_MS]. Every frame for the first quarter of a second, then less and less
 * often — the teardown runs about 6 % later than it could have at worst, and a backend that takes
 * the whole [BACKEND_IDLE_BUDGET_MS] costs under 300 checks.
 */
internal fun backendIdlePollDelayMs(waitedMs: Long): Long =
    (waitedMs / 16L).coerceIn(BACKEND_IDLE_POLL_MS, BACKEND_IDLE_POLL_MAX_MS)

/**
 * Runs [onIdle] on the calling thread once this engine's backend has executed everything already
 * queued, **without blocking the calling thread on that drain**.
 *
 * `Engine.destroy()` joins the driver thread after it has run every pending command, and
 * `Engine.destroyRenderer()` waits for the same backlog first. Called from the main thread while
 * the backend is behind — a view closed right after it appeared, a driver thread parked on a
 * surface that only the main thread can release — that wait is an ANR. Instead a fence is queued
 * behind the backlog and checked from the calling thread's [Looper], every
 * [backendIdlePollDelayMs], so the destroy calls only run once they have nothing left to wait for.
 *
 * [onIdle] receives `deferred = false` when it ran before this function returned: the backend was
 * idle, the engine was already destroyed, or — the old blocking behaviour — the thread has no
 * [Looper] or the fence could not be created. The engine can be destroyed by someone else while
 * the fence is pending: check [Engine.isValid] in [onIdle] before touching it.
 *
 * A backend that is late is waited for: one warning after [BACKEND_IDLE_WARN_MS], and [onIdle]
 * still runs when the backend gets there. The time counted is the sum of the delays asked for, so
 * a process that was frozen or a thread that was stalled is not held against the backend. Only
 * when [BACKEND_IDLE_BUDGET_MS] is spent is [onIdle] **not called at all**: the engine and what
 * hangs on it are leaked, with an error in the log, rather than blocking the calling thread on a
 * backend that will not drain. Until then [onIdle], and everything it references, stays reachable
 * from the [Looper]'s queue — keep the view and its context out of it.
 */
internal fun Engine.whenBackendIdle(onIdle: (deferred: Boolean) -> Unit) {
    val engine = this
    val looper = Looper.myLooper()
    val fence = if (looper != null && engine.isValid) {
        runCatching { engine.createFence() }.getOrNull()
    } else {
        null
    }
    if (looper == null || fence == null) {
        onIdle(false)
        return
    }
    if (!isFenceBusy(fence, BACKEND_IDLE_FIRST_WAIT_NANOS)) {
        runCatching { engine.destroyFence(fence) }
        onIdle(false)
        return
    }
    val handler = Handler(looper)
    val startedAt = SystemClock.uptimeMillis()
    val poll = object : Runnable {
        var waitedMs = 0L
        var warned = false

        /** Asks for the next check. False when the looper is quitting and takes no more messages. */
        fun schedule(): Boolean {
            val delayMs = backendIdlePollDelayMs(waitedMs)
            waitedMs += delayMs
            return handler.postDelayed(this, delayMs)
        }

        override fun run() {
            when {
                // Destroyed while the fence was pending: its fences went with it, do not touch it.
                !engine.isValid -> onIdle(true)
                !isFenceBusy(fence) -> {
                    runCatching { engine.destroyFence(fence) }
                    onIdle(true)
                }
                // Stuck, not late: stop checking and leave the engine alive.
                waitedMs >= BACKEND_IDLE_BUDGET_MS -> Log.e(
                    "Sceneview",
                    "Filament backend still busy after " +
                            "${SystemClock.uptimeMillis() - startedAt} ms: giving up, " +
                            "the engine is left alive instead of blocking the thread"
                )
                else -> {
                    if (!warned && waitedMs >= BACKEND_IDLE_WARN_MS) {
                        warned = true
                        Log.w(
                            "Sceneview",
                            "Filament backend still busy after " +
                                    "${SystemClock.uptimeMillis() - startedAt} ms: still " +
                                    "waiting, the engine is destroyed once it is idle"
                        )
                    }
                    // A looper that is quitting takes no more messages: the teardown then runs
                    // now rather than never.
                    if (!schedule()) {
                        runCatching { engine.destroyFence(fence) }
                        onIdle(true)
                    }
                }
            }
        }
    }
    if (!poll.schedule()) {
        runCatching { engine.destroyFence(fence) }
        onIdle(false)
    }
}

/**
 * Whether the backend has not reached [fence] yet, after waiting for it at most [timeoutNanos] —
 * zero by default, which never blocks. A fence that cannot be read is reported as reached, so
 * that a teardown waiting on it runs instead of being lost.
 */
internal fun isFenceBusy(fence: Fence, timeoutNanos: Long = 0L): Boolean =
    runCatching { fence.wait(Fence.Mode.FLUSH, timeoutNanos) }.getOrNull() ==
            Fence.FenceStatus.TIMEOUT_EXPIRED

fun Engine.safeDestroyEntity(entity: Entity) = runCatching { destroyEntity(entity) }

fun Engine.destroyTransformable(@FilamentEntity entity: Entity) = transformManager.destroy(entity)
fun Engine.safeDestroyTransformable(@FilamentEntity entity: Entity) =
    runCatching { destroyTransformable(entity) }

fun Engine.safeDestroyCamera(camera: Camera) = runCatching { destroyCameraComponent(camera.entity) }

fun Engine.safeDestroyEnvironment(environment: Environment) {
    environment.indirectLight?.let { safeDestroyIndirectLight(it) }
    environment.skybox?.let { safeDestroySkybox(it) }
}

fun Engine.safeDestroyIndirectLight(indirectLight: IndirectLight) =
    runCatching { destroyIndirectLight(indirectLight) }

fun Engine.safeDestroySkybox(skybox: Skybox) = runCatching { destroySkybox(skybox) }

fun Engine.safeDestroyMaterial(material: Material) = runCatching { destroyMaterial(material) }
fun Engine.safeDestroyMaterialInstance(materialInstance: MaterialInstance) =
    runCatching { destroyMaterialInstance(materialInstance) }

fun Engine.safeDestroyTexture(texture: Texture) = runCatching { destroyTexture(texture) }

fun Engine.safeDestroyStream(stream: Stream) = runCatching { destroyStream(stream) }

fun Engine.destroyRenderable(@FilamentEntity entity: Entity) = renderableManager.destroy(entity)

fun Engine.safeDestroyRenderable(@FilamentEntity entity: Entity) =
    runCatching { destroyRenderable(entity) }

fun Engine.destroyGeometry(geometry: Geometry) {
    destroyVertexBuffer(geometry.vertexBuffer)
    destroyIndexBuffer(geometry.indexBuffer)
}

fun Engine.safeDestroyGeometry(geometry: Geometry) {
    safeDestroyVertexBuffer(geometry.vertexBuffer)
    safeDestroyIndexBuffer(geometry.indexBuffer)
}

fun Engine.safeDestroyVertexBuffer(vertexBuffer: VertexBuffer) =
    runCatching { destroyVertexBuffer(vertexBuffer) }

fun Engine.safeDestroyIndexBuffer(indexBuffer: IndexBuffer) =
    runCatching { destroyIndexBuffer(indexBuffer) }

fun Engine.safeDestroyMaterialLoader(materialLoader: MaterialLoader) =
    runCatching { materialLoader.destroy() }

fun Engine.safeDestroyModelLoader(modelLoader: ModelLoader) = runCatching { modelLoader.destroy() }
fun Engine.safeDestroyRenderer(renderer: Renderer) = runCatching { destroyRenderer(renderer) }
fun Engine.safeDestroyView(view: View) = runCatching { destroyView(view) }
fun Engine.safeDestroyScene(scene: Scene) = runCatching { destroyScene(scene) }