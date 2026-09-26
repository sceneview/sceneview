package io.github.sceneview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
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

typealias Entity = Int
typealias EntityInstance = Int
typealias FilamentEntity = com.google.android.filament.Entity
typealias FilamentEntityInstance = com.google.android.filament.EntityInstance

/**
 * The null [Entity] — Filament's "no entity" value, which [EntityManager.create] never returns.
 *
 * Used as the default of every SDK constructor that takes an optional `entity`, so a node can
 * tell "the caller handed me an entity to borrow" from "no entity was given, allocate my own".
 * That distinction is what makes it safe for [io.github.sceneview.node.Node.destroy] to return
 * the id to the [EntityManager]: only self-allocated ids are recycled, never a borrowed one
 * (a `gltfio` asset entity, say, whose real owner is the `AssetLoader`). See #2859.
 */
const val NULL_ENTITY: Entity = 0

fun Engine.createModelLoader(context: Context) = ModelLoader(this, context)
fun Engine.createMaterialLoader(context: Context) = MaterialLoader(this, context)
fun Engine.createEnvironmentLoader(context: Context) = EnvironmentLoader(this, context)

fun Engine.createCamera() = createCamera(entityManager.create())

/**
 * Blocks until all pending GPU frames have been rendered.
 * Call after resizing or destroying a surface to avoid pipeline races.
 */
fun Engine.drainFramePipeline() {
    createFence().apply {
        wait(Fence.Mode.FLUSH, Fence.WAIT_FOR_EVER)
        destroyFence(this)
    }
}

/**
 * [drainFramePipeline] with an upper bound, for waits that sit on an Android input-dispatch path.
 *
 * A Filament fence is signalled by a command queued behind everything already submitted
 * (`FFence`'s constructor queues it on the driver API), so the wait covers the whole backend
 * backlog, not just the frames in flight. On the OpenGL backend that backlog includes the
 * scene's shader programs, which are compiled and linked lazily on the `FEngine::loop` thread
 * the first time a draw needs them. Right after a scene appears that is seconds of work on an
 * emulator, and can be on a low-end device: an unbounded wait turns it into an ANR (#3799).
 *
 * @return `true` if the pipeline drained within [timeoutNanos], `false` if it did not — the
 * pending commands keep running on the backend either way; nothing is cancelled.
 */
internal fun Engine.drainFramePipeline(timeoutNanos: Long): Boolean {
    val fence = createFence()
    val status = fence.wait(Fence.Mode.FLUSH, timeoutNanos)
    destroyFence(fence)
    return status == Fence.FenceStatus.CONDITION_SATISFIED
}

/** How often [destroyWhenBackendIdle] re-checks its fence: one frame at 60 Hz. */
internal const val BACKEND_IDLE_POLL_MS = 16L

/**
 * Destroys this engine once its backend has executed everything already queued, **without
 * blocking the calling thread on that drain**.
 *
 * [Engine.destroy] joins the driver thread after it has run every pending command
 * (`FEngine::shutdown`: "wait for all pending commands to be executed and the thread to exit").
 * When a scene is disposed right after it appeared, those pending commands include its lazy
 * shader compilation, and the join blocks the main thread for as long as that takes — an ANR on
 * an emulator (#3799). Instead, a fence is queued behind the backlog and polled with a zero
 * timeout from the calling thread's [Looper] every [BACKEND_IDLE_POLL_MS]; the engine is
 * destroyed, on that same thread (Filament requires it), once the fence has signalled. When the
 * backend is already idle — the usual case — the destroy happens before this function returns,
 * exactly as [safeDestroy] would.
 *
 * Falls back to an immediate [safeDestroy] when the calling thread has no [Looper] or the fence
 * cannot be created.
 *
 * @param onDestroyed runs right after the engine is destroyed — release whatever must outlive
 * it (its shared EGL context, say) here.
 */
internal fun Engine.destroyWhenBackendIdle(onDestroyed: () -> Unit = {}) {
    val startedAt = SystemClock.uptimeMillis()
    // Destroyed by someone else while the fence was pending: only the EGL side is left to release.
    whenBackendIdle(onEngineGone = onDestroyed) { deferredPolls ->
        safeDestroy()
        if (deferredPolls > 0) {
            Log.i(
                "Sceneview",
                "Engine destroyed after its backend drained " +
                    "(${SystemClock.uptimeMillis() - startedAt} ms, #3799)"
            )
        }
        onDestroyed()
    }
}

/**
 * Destroys [renderer] once this engine's backend has executed everything already queued, **without
 * blocking the calling thread on that drain**.
 *
 * [Engine.destroyRenderer] is not a plain handle release: `FRenderer::terminate` first waits for
 * "all pending commands" to execute (`Fence::waitAndDestroy(engine.createFence())`, Filament
 * v1.72.1 `Renderer.cpp`). A scene disposed while its shader programs are still being linked — an
 * activity destroyed right after a demo opened — parks the main thread on that whole backlog, an
 * ANR on an emulator (#3799). Deferred until the backend is idle, that inner wait has nothing left
 * to drain. When the backend is already idle — the usual case — the renderer is destroyed before
 * this function returns, exactly as [safeDestroyRenderer] would.
 *
 * If the engine is destroyed first, `FEngine::shutdown` reclaims the renderer with everything else
 * it still owns, and nothing more is done here.
 */
internal fun Engine.destroyRendererWhenBackendIdle(renderer: Renderer) {
    val startedAt = SystemClock.uptimeMillis()
    whenBackendIdle(onEngineGone = {}) { deferredPolls ->
        safeDestroyRenderer(renderer)
        if (deferredPolls > 0) {
            Log.i(
                "Sceneview",
                "Renderer destroyed after its backend drained " +
                    "(${SystemClock.uptimeMillis() - startedAt} ms, #3799)"
            )
        }
    }
}

/**
 * Runs [onIdle] on the calling thread once this engine's backend has executed everything already
 * queued, polling a fence from the calling thread's [Looper] every [BACKEND_IDLE_POLL_MS] instead
 * of waiting on it. [onIdle] runs before this function returns when the backend is already idle,
 * and also — the old blocking behaviour — when the thread has no [Looper] or the fence cannot be
 * created. [onEngineGone] runs instead when the engine is destroyed while the fence is pending.
 */
private fun Engine.whenBackendIdle(
    onEngineGone: () -> Unit,
    onIdle: (deferredPolls: Int) -> Unit,
) {
    val looper = Looper.myLooper()
    val fence = if (looper != null) runCatching { createFence() }.getOrNull() else null
    if (looper == null || fence == null) {
        onIdle(0)
        return
    }
    val handler = Handler(looper)
    awaitBackendIdleWhileAlive(
        isEngineAlive = { isValid },
        isFenceBusy = {
            runCatching { fence.wait(Fence.Mode.FLUSH, 0L) }
                .getOrNull() == Fence.FenceStatus.TIMEOUT_EXPIRED
        },
        schedule = { delayMs, block -> handler.postDelayed(block, delayMs) },
        onIdle = { deferredPolls ->
            runCatching { destroyFence(fence) }
            onIdle(deferredPolls)
        },
        onEngineGone = onEngineGone,
    )
}

/**
 * [awaitBackendIdle] on a fence owned by an engine that may be destroyed while it is polled.
 *
 * `FEngine::shutdown` frees every fence the engine still owns, and the Java `Fence` keeps its
 * now-dangling native pointer: waiting on it after that is a native crash, not an exception. So
 * [isEngineAlive] is checked before every poll; once it is `false` the fence is never touched again
 * and [onEngineGone] runs instead of [onIdle].
 */
internal fun awaitBackendIdleWhileAlive(
    isEngineAlive: () -> Boolean,
    isFenceBusy: () -> Boolean,
    schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    onIdle: (deferredPolls: Int) -> Unit,
    onEngineGone: () -> Unit,
) = awaitBackendIdle(
    isBackendBusy = { isEngineAlive() && isFenceBusy() },
    schedule = schedule,
    onIdle = { deferredPolls -> if (isEngineAlive()) onIdle(deferredPolls) else onEngineGone() },
)

/**
 * The non-blocking poll behind [destroyWhenBackendIdle], kept free of Android and Filament types
 * so it can be tested on the JVM.
 *
 * Calls [onIdle] synchronously when [isBackendBusy] is already `false`; otherwise asks
 * [schedule] to re-check after [intervalMs], and so on until the backend is idle. [onIdle]
 * receives how many re-checks were scheduled (`0` = it ran synchronously). Never waits itself.
 */
internal fun awaitBackendIdle(
    isBackendBusy: () -> Boolean,
    schedule: (delayMs: Long, block: () -> Unit) -> Unit,
    onIdle: (deferredPolls: Int) -> Unit,
    intervalMs: Long = BACKEND_IDLE_POLL_MS,
    deferredPolls: Int = 0,
) {
    if (!isBackendBusy()) {
        onIdle(deferredPolls)
        return
    }
    schedule(intervalMs) {
        awaitBackendIdle(isBackendBusy, schedule, onIdle, intervalMs, deferredPolls + 1)
    }
}

/**
 * Frees [model]'s native resources: releases whatever source glTF data is still held, then
 * destroys the `gltfio` asset itself.
 *
 * **Callers must guarantee this runs at most once per [model].** `runCatching` only catches JVM
 * exceptions — it cannot turn a JNI call into a null/already-freed native pointer into anything
 * softer than a `SIGSEGV`, which kills the whole process rather than throwing (#3523). That makes
 * "safe" here a promise about single-ownership bookkeeping upstream, not about this function's own
 * try/catch: [io.github.sceneview.loaders.ModelLoader.destroyModel] is the only caller, and it
 * enforces the at-most-once contract by atomically claiming [model] out of its live-asset registry
 * before ever reaching this call — a second claim attempt (a cancelled coroutine racing `clear()`,
 * say) finds the model already gone and never gets here.
 */
fun AssetLoader.safeDestroyModel(model: Model) {
    runCatching { model.releaseSourceData() }
    runCatching { destroyAsset(model) }
}

fun Engine.safeDestroy() = runCatching {
    // Drain the frame-deferred destroy queue first: once the Engine is gone the queued
    // textures/streams can no longer be destroyed individually (sceneview/sceneview#874).
    // The Engine reclaims everything below anyway, so the grace period no longer applies.
    runCatching { EngineDestroyQueue.of(this).drainAll() }
    destroy()
    Log.d("Sceneview", "Engine destroyed")
}

/**
 * Destroys every Filament component attached to [entity].
 *
 * `FEngine::destroy(Entity)` tears down the renderable, light, transform and camera components
 * in one call. Three of those four live in packed component stores that reindex on removal, so
 * this must bump all three generation counters below; the camera component has no cached-handle
 * counterpart in this codebase, hence no fourth counter. `SplatNode.destroy()` is the caller that
 * relies on it: its batch entities carry a transform component (`SplatNode.kt`, `transformManager
 * .create(entity, transformInstance, …)`) which nothing else destroys (#3123).
 */
fun Engine.safeDestroyEntity(entity: Entity) = runCatching {
    destroyEntity(entity)
    bumpTransformGeneration()
    bumpLightGeneration()
    bumpRenderableGeneration()
}

/**
 * Returns [entity]'s id to the [EntityManager] so Filament can hand it out again.
 *
 * [Engine.destroyEntity] destroys the entity's *components* but deliberately does **not**
 * release the id — only [EntityManager.destroy] does. Skipping it means every entity ever
 * created burns an id for the lifetime of the process (#2859).
 *
 * **Only call this on an entity you allocated yourself.** Recycling a borrowed id — one owned
 * by `gltfio`, or handed in by a caller — lets Filament reissue it while the real owner is
 * still using it. Destroy the components first: this call invalidates the id.
 */
fun Engine.safeRecycleEntity(@FilamentEntity entity: Entity) =
    runCatching { EntityManager.get().destroy(entity) }

// Every Filament component manager reachable from here — TransformManager, LightManager,
// RenderableManager — is a `SingleInstanceComponentManager`, a packed array that compacts on
// removal by swapping the LAST live entity into the freed slot. That silently reindexes the
// moved entity's `EntityInstance`, invalidating any handle a caller cached
// (`removeComponentsHelper`: `p[index] = std::move(p[last])`).
//
// SceneView caches such handles on the hot read path to skip a per-frame JNI thunk:
// `Node.transformInstance` / `Node.parentInstance` (#2269/#2404), `LightNode.lightInstance`
// (#2285), `RenderableNode.renderableInstance` and `ARCameraStream.renderableInstance` (#2287).
// One per-Engine generation counter per manager lets each cache detect "a component of my kind
// was destroyed on this Engine since I last read" in O(1), without tracking every live Node.
// The read path stays a single Int compare, so the caches keep their point (#2977/#2991/#3123).
//
// getOrPut below is check-then-act, not atomic — WeakHashMap has no internal synchronization.
// Safe under this codebase's existing main-thread-only assumption for anything touching Filament
// JNI (see e.g. the @MainThread annotations on ModelLoader's Filament-touching functions,
// ModelLoader.kt's createInstance and others).
private val transformGenerationByEngine =
    java.util.WeakHashMap<Engine, java.util.concurrent.atomic.AtomicInteger>()
private val lightGenerationByEngine =
    java.util.WeakHashMap<Engine, java.util.concurrent.atomic.AtomicInteger>()
private val renderableGenerationByEngine =
    java.util.WeakHashMap<Engine, java.util.concurrent.atomic.AtomicInteger>()

internal fun Engine.transformGeneration(): Int =
    transformGenerationByEngine.getOrPut(this) { java.util.concurrent.atomic.AtomicInteger() }.get()

internal fun Engine.bumpTransformGeneration() {
    transformGenerationByEngine.getOrPut(this) { java.util.concurrent.atomic.AtomicInteger() }
        .incrementAndGet()
}

internal fun Engine.lightGeneration(): Int =
    lightGenerationByEngine.getOrPut(this) { java.util.concurrent.atomic.AtomicInteger() }.get()

internal fun Engine.bumpLightGeneration() {
    lightGenerationByEngine.getOrPut(this) { java.util.concurrent.atomic.AtomicInteger() }
        .incrementAndGet()
}

/**
 * Monotonic counter of renderable-component destructions on this [Engine].
 *
 * Read it before serving a cached [com.google.android.filament.RenderableManager] handle and
 * re-resolve the handle whenever the value changed since the snapshot — see
 * [io.github.sceneview.node.RenderableNode.renderableInstance].
 *
 * Public (unlike its transform and light siblings) only because `arsceneview`'s `ARCameraStream`
 * is a cross-module [io.github.sceneview.components.RenderableComponent] implementer that caches
 * the same handle. Bumping is deliberately **not** public: the counter is bumped by
 * [destroyRenderable], [safeDestroyEntity] and
 * [io.github.sceneview.loaders.ModelLoader.destroyModel] — the three call sites that can reindex
 * the array. `destroyModel` is easy to miss because it never touches `RenderableManager` from
 * Kotlin: `AssetLoader.destroyAsset` tears the asset's renderable components down natively, and a
 * glTF asset carries one on every mesh entity (#3129 review).
 */
fun Engine.renderableGeneration(): Int =
    renderableGenerationByEngine.getOrPut(this) { java.util.concurrent.atomic.AtomicInteger() }
        .get()

internal fun Engine.bumpRenderableGeneration() {
    renderableGenerationByEngine.getOrPut(this) { java.util.concurrent.atomic.AtomicInteger() }
        .incrementAndGet()
}

fun Engine.destroyTransformable(@FilamentEntity entity: Entity) {
    transformManager.destroy(entity)
    bumpTransformGeneration()
}
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

/**
 * Destroys [entity]'s renderable component and invalidates every cached `RenderableManager`
 * handle on this [Engine].
 *
 * `RenderableManager` compacts on removal exactly like `TransformManager`, so this reindexes one
 * other live entity's `RenderableInstance` (#3123).
 */
fun Engine.destroyRenderable(@FilamentEntity entity: Entity) {
    renderableManager.destroy(entity)
    bumpRenderableGeneration()
}

fun Engine.safeDestroyRenderable(@FilamentEntity entity: Entity) =
    runCatching { destroyRenderable(entity) }

/**
 * Destroys [entity]'s light component and invalidates every cached `LightManager` handle on this
 * [Engine].
 *
 * `LightManager` compacts on removal exactly like `TransformManager`, so this reindexes one other
 * live entity's `EntityInstance` — which `LightNode.lightInstance` caches (#2991).
 */
fun Engine.destroyLight(@FilamentEntity entity: Entity) {
    lightManager.destroy(entity)
    bumpLightGeneration()
}

fun Engine.safeDestroyLight(@FilamentEntity entity: Entity) =
    runCatching { destroyLight(entity) }

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