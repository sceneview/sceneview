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
import com.google.android.filament.gltfio.isReleased
import io.github.sceneview.environment.Environment
import io.github.sceneview.geometries.Geometry
import io.github.sceneview.loaders.EnvironmentLoader
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.material.materialRegistry
import io.github.sceneview.model.Model
import java.util.Collections
import java.util.WeakHashMap

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

/**
 * Whether this engine has not been destroyed yet.
 *
 * Filament 1.77's generated Java bindings dropped `Engine.isValid()`; a destroyed engine now
 * throws from `getNativeObject()` instead, which this reads without letting it escape.
 */
val Engine.isValid: Boolean
    get() = runCatching { nativeObject }.isSuccess

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
        // The one-argument overload waits forever: Filament 1.77 annotates the explicit
        // timeout `@IntRange(from = 0)`, so passing its own WAIT_FOR_EVER (-1) fails lint.
        wait(Fence.Mode.FLUSH)
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

/** How often a teardown deferred by [whenBackendIdle] re-checks its fence: one frame at 60 Hz. */
internal const val BACKEND_IDLE_POLL_MS = 16L

/**
 * Destroys this engine once its backend has executed everything already queued, polling the backlog first.
 * Native destruction itself still has no timeout.
 *
 * [Engine.destroy] joins the driver thread after it has run every pending command
 * (`FEngine::shutdown`: "wait for all pending commands to be executed and the thread to exit").
 * When a scene is disposed right after it appeared, those pending commands include its lazy
 * shader compilation, and the join blocks the main thread for as long as that takes — an ANR on
 * an emulator (#3799). Instead the engine is destroyed through [whenBackendIdle], on the calling
 * thread (Filament requires it). When the backend is already idle — the usual case — the destroy
 * happens after a fence has made the round trip to the backend. This marks the engine RETIRING:
 * its producers must have stopped, and all pending releases accept a reached fence regardless of
 * its age. Slow main-thread polling therefore cannot retain the engine and its EGL context.
 *
 * @param onDestroyed runs right after the engine is destroyed — release whatever must outlive
 * it (its shared EGL context, say) here.
 */
internal fun Engine.destroyWhenBackendIdle(onDestroyed: () -> Unit = {}) {
    val startedAt = SystemClock.uptimeMillis()
    // Destroyed by someone else while the fence was pending: only the EGL side is left to release.
    // Last: a model loader still waiting for its texture decoders must release before the engine,
    // and must not be forced to release early, on this thread, by safeDestroy() (#3981).
    whenBackendIdle(onEngineGone = onDestroyed, runsLast = true) { deferred ->
        safeDestroy()
        if (deferred) logDeferredTeardown("Engine", startedAt)
        onDestroyed()
    }
}

/**
 * Destroys [renderer] once this engine's backend has executed everything already queued, polling the backlog first.
 * Native destruction itself still has no timeout.
 *
 * [Engine.destroyRenderer] is not a plain handle release: `FRenderer::terminate` first waits for
 * "all pending commands" to execute (`Fence::waitAndDestroy(engine.createFence())`, Filament
 * v1.72.1 `Renderer.cpp`). A scene disposed while its shader programs are still being linked — an
 * activity destroyed right after a demo opened — parks the main thread on that whole backlog, an
 * ANR on an emulator (#3799). The inner wait covers what is queued when the destroy *runs*, not
 * when it was deferred: on a shared engine, the next screen's shader compilations queued in
 * between. So the destroy waits, through [whenBackendIdle]'s [FreshFenceProbe], until the backend
 * has recently caught up with its queue. That inner wait then only finds what the main thread
 * queued in the last 48 ms — main-thread time, not backend work: usually a frame or two, but a
 * shader compilation queued in that window is still waited for. This narrows the blocking case
 * without ruling it out.
 * When the backend is already idle — the usual case — the renderer is destroyed one poll later,
 * once that fence has made the round trip to the backend, exactly as [safeDestroyRenderer] would.
 * On a shared engine, polling less often than every 48 ms keeps the destroy pending. Once the
 * engine is retiring, a reached fence suffices. Five seconds triggers a warning and slower polls,
 * never permission to destroy behind a busy fence.
 *
 * `FRenderer::terminate` then joins the frame-info job thread, parked in a GPU fence wait unless
 * the engine was built without the GPU frame-complete metric — see [createEngine].
 */
internal fun Engine.destroyRendererWhenBackendIdle(renderer: Renderer) {
    val startedAt = SystemClock.uptimeMillis()
    whenBackendIdle { deferred ->
        safeDestroyRenderer(renderer)
        if (deferred) logDeferredTeardown("Renderer", startedAt)
    }
}

internal fun logDeferredTeardown(what: String, startedAt: Long) = Log.i(
    "Sceneview",
    "$what destroyed after its backend drained (${SystemClock.uptimeMillis() - startedAt} ms, #3799)"
)

/** Pending registries are removed when empty: their probe callbacks capture the engine. */
private val backendIdleTeardowns = Collections.synchronizedMap(
    WeakHashMap<Engine, BackendIdleTeardowns>()
)

private fun Engine.teardowns(): BackendIdleTeardowns = backendIdleTeardowns.getOrPut(this) {
    BackendIdleTeardowns(
        probe = freshFenceProbe(),
        onEmpty = { empty -> backendIdleTeardowns.remove(this, empty) },
    )
}

/** The same fence check used by deferred releases and stalled model loads. */
internal fun Engine.isBackendBusy(): Boolean = teardowns().isBackendBusy()

/**
 * Runs [onIdle] on the engine's calling thread after its backend fence is reached. Shared engines
 * also require freshness: subsequent screens may have queued more work. `runsLast` marks the
 * engine RETIRING (producers must have stopped), dropping freshness for all its pending releases
 * while preserving other conditions such as decoder completion. See [BackendIdleTeardowns].
 *
 * One poll and probe are shared per engine. After five seconds polling backs off, with occasional
 * short confirmation polls for freshness. There is no timeout-triggered destruction. Neither a
 * reached nor a fresh fence bounds native destruction or command submission time.
 *
 * [onIdle] receives `deferred = false` if it runs before this call returns. [onEngineGone] runs
 * instead after a raw engine destruction, without touching the fence. [safeDestroy] explicitly
 * runs pending releases before freeing the engine and may block.
 *
 * Main thread expected. For compatibility an off-main caller is logged and keeps its own looper;
 * with no looper it falls back to synchronous release, which may block. No thread handoff occurs.
 */
internal fun Engine.whenBackendIdle(
    onEngineGone: () -> Unit = {},
    runsLast: Boolean = false,
    onIdle: (deferred: Boolean) -> Unit,
) = whenIdle(
    isBusy = { isBackendBusy() },
    onEngineGone = onEngineGone,
    runsLast = runsLast,
    onIdle = onIdle,
)

/** Zero-timeout fence polling; FLUSH itself can still block on a full command stream. */
private fun Engine.freshFenceProbe(): FreshFenceProbe<Fence> = FreshFenceProbe(
    createFence = { runCatching { createFence() }.getOrNull() },
    isReached = { fence ->
        when (runCatching { fence.wait(Fence.Mode.FLUSH, 0L) }.getOrNull()) {
            Fence.FenceStatus.CONDITION_SATISFIED -> true
            Fence.FenceStatus.TIMEOUT_EXPIRED -> false
            else -> null // ERROR (or a failed wait) must re-arm, not poll a broken fence forever.
        }
    },
    destroyFence = { destroyFence(it) },
    now = SystemClock::uptimeMillis,
    onStillBusy = {
        Log.w("Sceneview", "Backend teardown still pending after 5 s; backing off polls (#4969)")
    },
)

/**
 * General form of [whenBackendIdle] for other readiness conditions, such as model decoders.
 * Shares the engine's scheduler and preserves the calling thread on the off-main fallback.
 */
internal fun Engine.whenIdle(
    isBusy: () -> Boolean,
    onEngineGone: () -> Unit = {},
    runsLast: Boolean = false,
    onIdle: (deferred: Boolean) -> Unit,
) {
    if (!isValid) {
        onEngineGone()
        return
    }
    val looper = Looper.myLooper()
    if (looper != Looper.getMainLooper()) {
        Log.w("Sceneview", "Filament teardown called off main; using the calling thread for compatibility")
    }
    if (looper == null) {
        onIdle(false)
        return
    }
    val handler = Handler(looper)
    teardowns().defer(
        isEngineAlive = { isValid },
        isBackendBusy = isBusy,
        // A refused post retains the teardown; it never authorizes a blocking release.
        schedule = { delayMs, block -> handler.postDelayed(block, delayMs) },
        teardown = onIdle,
        onEngineGone = onEngineGone,
        runsLast = runsLast,
    )
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
    // Destroyed already, through this path or another: gltfio zeroed its handle, and both calls
    // below would hand that zero to native code.
    if (model.isReleased) return
    runCatching { model.releaseSourceData() }
    runCatching { destroyAsset(model) }
}

fun Engine.safeDestroy() = runCatching {
    if (!isValid) return@runCatching
    // Teardowns still waiting for the backend (a renderer, an IBL prefilter context) run first:
    // the engine must outlive what it owns, and they would read freed memory after it (#3885).
    runCatching { backendIdleTeardowns.remove(this)?.runPending() }
    // One of those pending teardowns can be this engine's own deferred destroy
    // (destroyWhenBackendIdle), which has just called safeDestroy() re-entrantly and freed it.
    if (!isValid) return@runCatching
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
private val lightGenerationByEngine =
    java.util.WeakHashMap<Engine, java.util.concurrent.atomic.AtomicInteger>()
private val renderableGenerationByEngine =
    java.util.WeakHashMap<Engine, java.util.concurrent.atomic.AtomicInteger>()

// TransformManager has a second reindexing path besides destruction:
// `commitLocalTransformTransaction()` walks the whole array and `swapNode()`s every child that sits
// before its parent, so that world transforms resolve in one pass (`computeAllWorldTransforms`).
// gltfio's `Animator.applyAnimation` and `applyCrossFade` open and commit such a transaction on
// every call — from ModelNode's frame, from an app scrubbing a paused clip, from anywhere. A child
// only gets ahead of its parent through a destroy (the swap-remove above) or a reparent
// (`setParent` links, it never reorders), so each of those marks the order as unsorted. Until the
// order is sorted again, the next commit can reindex any entity at all, and no cached handle can be
// trusted across it: [TransformState.unsorted] tells `Node.transformInstance` /
// `Node.parentInstance` to resolve fresh on every read instead. [sortTransformsIfUnsorted], at the
// top of each SceneView frame, sorts once, bumps the generation once and lets the caches resume.
//
// Without this, an animated ModelNode added while another model was still alive, then that model
// destroyed, pointed its root handle at one of its own meshes after the animator's first commit:
// its transform went to the mesh, the root never moved, and a skinned model rendered as nothing.
internal class TransformState {
    /** Bumped every time the TransformManager array may have been reindexed. */
    var generation = 0

    /** A child may sit before its parent: the next transaction commit would reindex. */
    var unsorted = false
}

private val transformStateByEngine = java.util.WeakHashMap<Engine, TransformState>()

internal fun Engine.transformState(): TransformState =
    transformStateByEngine.getOrPut(this) { TransformState() }

internal fun Engine.transformGeneration(): Int = transformState().generation

internal fun Engine.bumpTransformGeneration() {
    val state = transformState()
    state.generation++
    // The swap-remove that made this bump necessary can also leave a child ahead of its parent.
    state.unsorted = true
}

/** Records that a TransformManager child may now sit before its parent in the packed array. */
internal fun Engine.markTransformOrderUnsorted() {
    transformState().unsorted = true
}

/**
 * Puts TransformManager back in parent-before-child order if a destroy or a reparent may have
 * broken it, and bumps the transform generation so cached handles are looked up again. Once per
 * frame at most, before any node's `onFrame`: a teardown of a thousand entities pays one O(n)
 * sort, not one per entity. A no-op while the order is known to be sorted.
 *
 * Library-group API, not app API: `arsceneview`'s frame loop calls it too.
 */
@androidx.annotation.RestrictTo(androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
fun Engine.sortTransformsIfUnsorted() {
    val state = transformState()
    if (!state.unsorted) return
    transformManager.openLocalTransformTransaction()
    transformManager.commitLocalTransformTransaction()
    state.unsorted = false
    state.generation++
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

/**
 * Destroys the indirect light and the skybox of [environment], then the textures it owns — the
 * cubemaps of a KTX or HDR environment, which Filament does not free with the light or the skybox
 * that samples them (#4358).
 *
 * Safe to call twice, and after [io.github.sceneview.loaders.EnvironmentLoader.destroyEnvironment].
 */
fun Engine.safeDestroyEnvironment(environment: Environment) {
    environment.destroy(
        destroyIndirectLight = { safeDestroyIndirectLight(it) },
        destroySkybox = { safeDestroySkybox(it) },
        destroyTexture = { safeDestroyTexture(it) },
    )
}

fun Engine.safeDestroyIndirectLight(indirectLight: IndirectLight) =
    runCatching { destroyIndirectLight(indirectLight) }

fun Engine.safeDestroySkybox(skybox: Skybox) = runCatching { destroySkybox(skybox) }

fun Engine.safeDestroyMaterial(material: Material) = runCatching { destroyMaterial(material) }
fun Engine.safeDestroyMaterialInstance(materialInstance: MaterialInstance) = runCatching {
    // Forgotten first: Filament may reuse the freed address for an instance SceneView never
    // recorded, which `materialOrNull` would then map to this instance's Material.
    materialRegistry.forget(materialInstance)
    destroyMaterialInstance(materialInstance)
}

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

/**
 * Destroys [geometry]'s vertex and index buffers, right away — no rendered frame is needed.
 *
 * A no-op while a node is still bound to [geometry] (`GeometryNode`, or any
 * `RenderableNode.setGeometry`): the last of those nodes to be destroyed releases the buffers
 * itself, so a geometry shared between nodes is freed once and never under a live renderable.
 * Idempotent.
 *
 * A raw renderable that was lent the buffers (`MeshNode(vertexBuffer = geometry.vertexBuffer, …)`)
 * is not counted: destroy it before, or in the same composition pass as, this call.
 */
fun Engine.destroyGeometry(geometry: Geometry) {
    geometry.destroy(this)
}

/** Same as [destroyGeometry]; kept for symmetry with the other `safeDestroy*` helpers. */
fun Engine.safeDestroyGeometry(geometry: Geometry) {
    geometry.destroy(this)
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
