package io.github.sceneview

import android.content.Context
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
 * `FEngine::destroy(Entity)` tears down the renderable, light, transform **and** camera
 * components in one call — so this reindexes all three packed component stores at once and must
 * bump all three generation counters below. `SplatNode.destroy()` is the production caller that
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
 * [destroyRenderable] and [safeDestroyEntity], the only two calls that can reindex the array.
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