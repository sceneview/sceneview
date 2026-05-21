@file:JsModule("filament")
@file:JsNonModule

package io.github.sceneview.web.bindings

import org.w3c.dom.HTMLCanvasElement

// -----------------------------------------------------------------------
// Filament.js external declarations for Kotlin/JS
//
// These match the filament 1.52.x npm package TypeScript definitions.
// Key difference from Android Filament: entities are Entity objects (not raw Int),
// manager instances are wrapper objects, and many methods take float3 arrays [x,y,z]
// instead of individual components.
// -----------------------------------------------------------------------

// --- Module-level functions ---

external fun init(assets: Array<String>, onReady: (() -> Unit)? = definedExternally)
external fun fetch(assets: Array<String>, onDone: (() -> Unit)? = definedExternally, onFetched: ((String) -> Unit)? = definedExternally)
external fun clearAssetCache()
external fun fitIntoUnitCube(box: Aabb): dynamic // returns mat4

/** Pre-fetched assets keyed by URL. Available after init() or fetch(). */
external val assets: dynamic // { [url: string]: Uint8Array }

// --- Type aliases ---
// Filament.js uses number[] or gl-matrix types for vectors/matrices.
// In Kotlin/JS we use dynamic or Array<Double>.

// --- Entity ---

external class Entity {
    fun getId(): Int
    fun delete()
}

// --- AABB / Box ---

external interface Aabb {
    var min: dynamic // float3
    var max: dynamic // float3
}

external interface Box {
    var center: dynamic // float3
    var halfExtent: dynamic // float3
}

// --- Engine ---

// Static methods on JS classes don't map to Kotlin companion objects.
// Filament.Engine.create() is a JS static method, accessed via this external object.
@JsName("Engine")
external object EngineFactory {
    fun create(canvas: HTMLCanvasElement, contextOptions: dynamic = definedExternally): Engine
    fun destroy(engine: Engine)
}

external class Engine {

    fun execute()
    fun createCamera(entity: Entity): Camera
    fun createRenderer(): Renderer
    fun createScene(): Scene
    fun createSwapChain(): SwapChain
    fun createView(): View
    fun createAssetLoader(): AssetLoader
    fun createMaterial(urlOrBuffer: dynamic): Material

    // KTX texture/IBL/skybox helpers
    fun createIblFromKtx1(urlOrBuffer: dynamic): IndirectLight
    fun createSkyFromKtx1(urlOrBuffer: dynamic): Skybox
    fun createTextureFromKtx1(urlOrBuffer: dynamic, options: dynamic = definedExternally): Texture
    fun createTextureFromKtx2(urlOrBuffer: dynamic, options: dynamic = definedExternally): Texture
    fun createTextureFromJpeg(urlOrBuffer: dynamic, options: dynamic = definedExternally): Texture
    fun createTextureFromPng(urlOrBuffer: dynamic, options: dynamic = definedExternally): Texture

    // Destroy methods
    fun destroySwapChain(swapChain: SwapChain)
    fun destroyRenderer(renderer: Renderer)
    fun destroyView(view: View)
    fun destroyScene(scene: Scene)
    fun destroyCameraComponent(camera: Entity)
    fun destroyMaterial(material: Material)
    fun destroyEntity(entity: Entity)
    fun destroyIndirectLight(indirectLight: IndirectLight)
    fun destroySkybox(skybox: Skybox)
    fun destroyTexture(texture: Texture)

    // Manager accessors
    fun getLightManager(): LightManager
    fun getRenderableManager(): RenderableManager
    fun getTransformManager(): TransformManager
}

// --- Renderer ---

external class Renderer {
    fun render(swapChain: SwapChain, view: View)
    fun beginFrame(swapChain: SwapChain): Boolean
    fun endFrame()
    fun renderView(view: View)
    fun setClearOptions(options: dynamic)
}

// --- Scene ---

external class Scene {
    fun addEntity(entity: Entity)
    fun addEntities(entities: Array<Entity>)
    fun remove(entity: Entity)
    fun removeEntities(entities: Array<Entity>)
    fun setIndirectLight(light: IndirectLight?)
    fun setSkybox(skybox: Skybox?)
    fun getLightCount(): Int
    fun getRenderableCount(): Int
}

// --- View ---

external class View {
    fun setCamera(camera: Camera)
    fun setScene(scene: Scene)
    fun setViewport(viewport: dynamic) // float4: [x, y, width, height]
    fun setBloomOptions(options: dynamic)
    fun setAmbientOcclusionOptions(options: dynamic)
    fun setTemporalAntiAliasingOptions(options: dynamic)
    fun setColorGrading(grading: dynamic)
    fun setRenderTarget(target: dynamic)
    fun setPostProcessingEnabled(enabled: Boolean)
}

// --- Camera ---
// NOTE: lookAt takes float3 arrays, not 9 individual numbers.

external class Camera {
    fun setProjection(
        projection: dynamic, // Camera$Projection enum
        left: Double, right: Double, bottom: Double, top: Double,
        near: Double, far: Double
    )

    fun setProjectionFov(
        fovInDegrees: Double,
        aspect: Double,
        near: Double,
        far: Double,
        fov: dynamic = definedExternally // Camera$Fov enum
    )

    fun setLensProjection(focalLength: Double, aspect: Double, near: Double, far: Double)

    /** @param eye float3 [x,y,z], @param center float3, @param up float3 */
    fun lookAt(eye: dynamic, center: dynamic, up: dynamic)

    fun setExposure(aperture: Double, shutterSpeed: Double, sensitivity: Double)
    fun setExposureDirect(exposure: Double)
    fun setModelMatrix(view: dynamic) // mat4
    fun getProjectionMatrix(): dynamic // mat4
    fun getViewMatrix(): dynamic // mat4
    fun getModelMatrix(): dynamic // mat4
    fun getPosition(): dynamic // float3
    fun getNear(): Double
    fun getCullingFar(): Double
}

// --- SwapChain ---

external class SwapChain

// --- Lighting ---

external class IndirectLight {
    companion object {
        fun Builder(): IndirectLightBuilder
    }
    fun setIntensity(intensity: Double)
    fun getIntensity(): Double
    fun setRotation(value: dynamic) // mat3
}

external class IndirectLightBuilder {
    fun reflections(cubemap: Texture): IndirectLightBuilder
    fun irradianceSh(nbands: Int, f32array: dynamic): IndirectLightBuilder
    fun intensity(value: Double): IndirectLightBuilder
    fun rotation(value: dynamic): IndirectLightBuilder
    fun build(engine: Engine): IndirectLight
}

external class Skybox {
    fun setColor(color: dynamic) // float4
    companion object {
        fun Builder(): SkyboxBuilder
    }
}

external class SkyboxBuilder {
    fun color(rgba: dynamic): SkyboxBuilder // float4
    fun environment(envmap: Texture): SkyboxBuilder
    fun showSun(show: Boolean): SkyboxBuilder
    fun build(engine: Engine): Skybox
}

// --- Texture ---

external class Texture

// --- Material ---

external class Material {
    fun createInstance(): MaterialInstance
    fun getDefaultInstance(): MaterialInstance
    fun getName(): String
}

external class MaterialInstance {
    fun getName(): String
    fun setFloatParameter(name: String, value: Double)
    fun setFloat3Parameter(name: String, value: dynamic) // float3
    fun setFloat4Parameter(name: String, value: dynamic) // float4
    fun setTextureParameter(name: String, value: Texture, sampler: dynamic)
    fun setColor3Parameter(name: String, ctype: dynamic, value: dynamic) // float3
    fun setColor4Parameter(name: String, ctype: dynamic, value: dynamic) // float4
}

// --- LightManager ---

external class LightManager {
    companion object {
        fun Builder(ltype: dynamic): LightManagerBuilder
    }

    fun hasComponent(entity: Entity): Boolean
    fun getInstance(entity: Entity): dynamic // LightManager$Instance
    /**
     * Destroy the light component attached to [entity]. The component is a
     * separately-managed native allocation — `Engine.destroyEntity` alone
     * leaks it (#1700). Call this before destroying the entity.
     */
    fun destroy(entity: Entity)
    fun setPosition(instance: dynamic, value: dynamic) // float3
    fun getPosition(instance: dynamic): dynamic
    fun setDirection(instance: dynamic, value: dynamic) // float3
    fun getDirection(instance: dynamic): dynamic
    fun setColor(instance: dynamic, value: dynamic) // float3
    fun getColor(instance: dynamic): dynamic
    fun setIntensity(instance: dynamic, intensity: Double)
    fun getIntensity(instance: dynamic): Double
}

external class LightManagerBuilder {
    fun castLight(enable: Boolean): LightManagerBuilder
    fun castShadows(enable: Boolean): LightManagerBuilder
    fun color(rgb: dynamic): LightManagerBuilder // float3
    fun direction(value: dynamic): LightManagerBuilder // float3
    fun intensity(value: Double): LightManagerBuilder
    fun position(value: dynamic): LightManagerBuilder // float3
    fun falloff(value: Double): LightManagerBuilder
    fun build(engine: Engine, entity: Entity)
}

// --- TransformManager ---

external class TransformManager {
    fun hasComponent(entity: Entity): Boolean
    fun getInstance(entity: Entity): dynamic // TransformManager$Instance
    fun setTransform(instance: dynamic, xform: dynamic) // mat4
    fun getTransform(instance: dynamic): dynamic // mat4
    fun getWorldTransform(instance: dynamic): dynamic // mat4
    fun create(entity: Entity)
    fun destroy(entity: Entity)
    fun openLocalTransformTransaction()
    fun commitLocalTransformTransaction()
}

// --- RenderableManager ---

external class RenderableManager {
    fun hasComponent(entity: Entity): Boolean
    fun getInstance(entity: Entity): dynamic // RenderableManager$Instance
    fun destroy(entity: Entity)
    fun setMaterialInstanceAt(instance: dynamic, primitiveIndex: Int, materialInstance: MaterialInstance)
    fun getMaterialInstanceAt(instance: dynamic, primitiveIndex: Int): MaterialInstance
    fun getAxisAlignedBoundingBox(instance: dynamic): Box
}

// --- AssetLoader (gltfio) ---

@JsName("gltfio\$AssetLoader")
external class AssetLoader {
    fun createAsset(urlOrBuffer: dynamic): FilamentAsset?
    fun destroyAsset(asset: FilamentAsset)
    fun delete()
}

// --- FilamentAsset (gltfio) ---

@JsName("gltfio\$FilamentAsset")
external class FilamentAsset {
    /**
     * Loads external resources (textures, buffers) referenced by the glTF.
     * MUST be called after createAsset() for models to render correctly.
     *
     * @param onDone callback when all resources are loaded
     * @param onFetched callback for each individual resource
     * @param basePath base URL for resolving relative paths (null = same directory as model)
     * @param asyncInterval milliseconds between async operations (null = default)
     */
    fun loadResources(
        onDone: (() -> Unit)? = definedExternally,
        onFetched: ((String) -> Unit)? = definedExternally,
        basePath: String? = definedExternally,
        asyncInterval: Double? = definedExternally,
        options: dynamic = definedExternally
    )

    fun getEntities(): Array<Entity>
    fun getRoot(): Entity
    fun getBoundingBox(): Aabb
    fun releaseSourceData()
    fun getName(entity: Entity): String?
    fun getResourceUris(): Array<String>
    fun getRenderableEntities(): Array<Entity>
    fun getLightEntities(): Array<Entity>
    fun getCameraEntities(): Array<Entity>
    fun getInstance(): FilamentInstance
    fun getEngine(): Engine
    fun getWireframe(): Entity
}

// --- FilamentInstance (gltfio) ---

@JsName("gltfio\$FilamentInstance")
external class FilamentInstance {
    fun getEntities(): dynamic // Vector<Entity>
    fun getRoot(): Entity
    fun getAnimator(): Animator
}

// --- Animator (gltfio) ---
// applyAnimation(index, time) — the second `time` argument is required for the
// animation to advance. The Filament.js `.d.ts` historically omitted it, but
// the underlying C++ `Animator::applyAnimation(size_t, float)` and the runtime
// both accept it (Filament's own filament-viewer.js calls it with two args).

@JsName("gltfio\$Animator")
external class Animator {
    fun applyAnimation(index: Int, time: Double)
    fun applyCrossFade(previousAnimIndex: Int, previousAnimTime: Double, alpha: Double)
    fun updateBoneMatrices()
    fun resetBoneMatrices()
    fun getAnimationCount(): Int
    fun getAnimationDuration(index: Int): Double
    fun getAnimationName(index: Int): String
}

// --- EntityManager ---

external class EntityManager {
    companion object {
        fun get(): EntityManager
    }
    fun create(): Entity
}

