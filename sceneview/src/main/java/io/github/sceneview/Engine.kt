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

/** How often a teardown deferred by [whenBackendIdle] re-checks its fence: one frame at 60 Hz. */
internal const val BACKEND_IDLE_POLL_MS = 16L

/**
 * How long a teardown deferred by [whenBackendIdle] keeps polling before it gives up. A backend
 * that has not caught up after this long is stuck, not late: destroying the engine would then
 * block the thread for good, so the engine is left alive instead.
 */
internal const val BACKEND_IDLE_GIVE_UP_MS = 10_000L

/**
 * How long a surface detach waits for the backend to finish with the surface, in nanoseconds.
 * Long enough for the frames in flight, far below the 5 s after which Android reports an ANR.
 */
internal const val SURFACE_DETACH_WAIT_NANOS = 1_000_000_000L

/**
 * Runs [onIdle] on the calling thread once this engine's backend has executed everything already
 * queued, **without blocking the calling thread on that drain**.
 *
 * `Engine.destroy()` joins the driver thread after it has run every pending command, and
 * `Engine.destroyRenderer()` waits for the same backlog first. Called from the main thread while
 * the backend is behind — a view closed right after it appeared, a driver thread parked on a
 * surface that only the main thread can release — that wait is an ANR. Instead a fence is queued
 * behind the backlog and checked from the calling thread's [Looper] every [BACKEND_IDLE_POLL_MS],
 * so the destroy calls only run once they have nothing left to wait for.
 *
 * [onIdle] receives `deferred = false` when it ran before this function returned: the backend was
 * idle, the engine was already destroyed, or — the old blocking behaviour — the thread has no
 * [Looper] or the fence could not be created. The engine can be destroyed by someone else while
 * the fence is pending: check [Engine.isValid] in [onIdle] before touching it.
 *
 * [onIdle] is **not called at all** when the backend is still busy after
 * [BACKEND_IDLE_GIVE_UP_MS]: the engine and what hangs on it are leaked, with a warning in the
 * log, rather than blocking the calling thread on a backend that will not drain.
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
    val giveUpAt = SystemClock.uptimeMillis() + BACKEND_IDLE_GIVE_UP_MS
    val poll = object : Runnable {
        override fun run() {
            when {
                // Destroyed while the fence was pending: its fences went with it, do not touch it.
                !engine.isValid -> onIdle(true)
                // Stuck, not late: stop polling and leave the engine alive.
                isFenceBusy(fence) && SystemClock.uptimeMillis() >= giveUpAt -> Log.w(
                    "Sceneview",
                    "Filament backend still busy after $BACKEND_IDLE_GIVE_UP_MS ms: " +
                            "engine left alive instead of blocking the thread"
                )
                // Still busy: look again next frame. A looper that is quitting takes no more
                // messages; the teardown then runs now rather than never.
                isFenceBusy(fence) && handler.postDelayed(this, BACKEND_IDLE_POLL_MS) -> Unit
                else -> {
                    runCatching { engine.destroyFence(fence) }
                    onIdle(true)
                }
            }
        }
    }
    if (!handler.postDelayed(poll, BACKEND_IDLE_POLL_MS)) {
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