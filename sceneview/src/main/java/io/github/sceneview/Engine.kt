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

fun Engine.safeDestroyEntity(entity: Entity) = runCatching { destroyEntity(entity) }

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

// TransformManager compacts its packed array on removal by swapping the last live entity into
// the freed slot, silently reindexing that entity's cached EntityInstance handles (Node's
// transformInstance / parentInstance, #2977/#2978). This per-Engine generation counter lets
// those caches detect "a transform component was destroyed since I last read" in O(1), without
// tracking every live Node. Bumped by destroyTransformable() (Node.destroy()'s path) and by
// ModelLoader.destroyModel() (glTF asset teardown destroys transform components directly via
// AssetLoader.destroyAsset, bypassing Node.destroy() entirely).
//
// getOrPut below is check-then-act, not atomic — WeakHashMap has no internal synchronization.
// Safe under this codebase's existing main-thread-only assumption for anything touching Filament
// JNI (see e.g. the @MainThread annotations on ModelLoader's Filament-touching functions,
// ModelLoader.kt's createInstance and others).
private val transformGenerationByEngine =
    java.util.WeakHashMap<Engine, java.util.concurrent.atomic.AtomicInteger>()

internal fun Engine.transformGeneration(): Int =
    transformGenerationByEngine.getOrPut(this) { java.util.concurrent.atomic.AtomicInteger() }.get()

internal fun Engine.bumpTransformGeneration() {
    transformGenerationByEngine.getOrPut(this) { java.util.concurrent.atomic.AtomicInteger() }
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