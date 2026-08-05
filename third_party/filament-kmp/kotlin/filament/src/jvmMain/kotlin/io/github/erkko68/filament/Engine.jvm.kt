package io.github.erkko68.filament

import io.github.erkko68.filament.ffm.FilamentC
import io.github.erkko68.filament.ffm.FilaEngineConfig
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment

actual class Engine public constructor(public var nativeHandle: MemorySegment?) {
    private val mTransformManager by lazy { TransformManager(FilamentC.FilaEngine_getTransformManager(nativeHandle)) }
    private val mLightManager by lazy { LightManager(FilamentC.FilaEngine_getLightManager(nativeHandle)) }
    private val mRenderableManager by lazy { RenderableManager(FilamentC.FilaEngine_getRenderableManager(nativeHandle)) }
    private val mEntityManager by lazy { EntityManager(FilamentC.FilaEngine_getEntityManager(nativeHandle)) }

    actual enum class Backend {
        DEFAULT, OPENGL, VULKAN, METAL, WEBGPU, NOOP;
        internal fun toNative(): Int = ordinal
        companion object {
            internal fun fromNative(backend: Int): Backend = values()[backend]
        }
    }

    actual enum class FeatureLevel {
        FEATURE_LEVEL_0, FEATURE_LEVEL_1, FEATURE_LEVEL_2, FEATURE_LEVEL_3;
        internal fun toNative(): Int = ordinal
        companion object {
            internal fun fromNative(level: Int): FeatureLevel = values()[level]
        }
    }

    actual enum class StereoscopicType {
        NONE, INSTANCED, MULTIVIEW;
        internal fun toNative(): Int = ordinal
        companion object {
            internal fun fromNative(type: Int): StereoscopicType = values()[type]
        }
    }

    actual enum class GpuContextPriority {
        DEFAULT, LOW, MEDIUM, HIGH, REALTIME;
        internal fun toNative(): Int = ordinal
        companion object {
            internal fun fromNative(priority: Int): GpuContextPriority = values()[priority]
        }
    }

    actual class Config actual constructor() {
        actual var commandBufferSizeMB: Long = 3 * 1
        actual var perRenderPassArenaSizeMB: Long = 3
        actual var driverHandleArenaSizeMB: Long = 0
        actual var minCommandBufferSizeMB: Long = 1
        actual var perFrameCommandsSizeMB: Long = 2
        actual var jobSystemThreadCount: Long = 0
        actual var stereoscopicType: StereoscopicType = StereoscopicType.NONE
        actual var stereoscopicEyeCount: Long = 2
        actual var resourceAllocatorCacheSizeMB: Long = 64
        actual var resourceAllocatorCacheMaxAge: Long = 1

        actual enum class ShaderLanguage {
            DEFAULT, MSL, METAL_LIBRARY;
        }
        actual var preferredShaderLanguage: ShaderLanguage = ShaderLanguage.DEFAULT
        actual var forceGLES2Context: Boolean = false
        actual var gpuContextPriority: GpuContextPriority = GpuContextPriority.DEFAULT
        actual var sharedUboInitialSizeInBytes: Long = 256 * 64

        internal fun toNative(arena: Arena): MemorySegment {
            val s = FilaEngineConfig.allocate(arena)
            FilaEngineConfig.commandBufferSizeMB(s, commandBufferSizeMB.toInt())
            FilaEngineConfig.perRenderPassArenaSizeMB(s, perRenderPassArenaSizeMB.toInt())
            FilaEngineConfig.driverHandleArenaSizeMB(s, driverHandleArenaSizeMB.toInt())
            FilaEngineConfig.minCommandBufferSizeMB(s, minCommandBufferSizeMB.toInt())
            FilaEngineConfig.perFrameCommandsSizeMB(s, perFrameCommandsSizeMB.toInt())
            FilaEngineConfig.jobSystemThreadCount(s, jobSystemThreadCount.toInt())
            FilaEngineConfig.stereoscopicType(s, stereoscopicType.toNative())
            FilaEngineConfig.stereoscopicEyeCount(s, stereoscopicEyeCount.toByte())
            FilaEngineConfig.resourceAllocatorCacheSizeMB(s, resourceAllocatorCacheSizeMB.toInt())
            FilaEngineConfig.resourceAllocatorCacheMaxAge(s, resourceAllocatorCacheMaxAge.toByte())
            FilaEngineConfig.preferredShaderLanguage(s, preferredShaderLanguage.ordinal)
            FilaEngineConfig.forceGLES2Context(s, forceGLES2Context)
            FilaEngineConfig.gpuContextPriority(s, gpuContextPriority.toNative())
            FilaEngineConfig.sharedUboInitialSizeInBytes(s, sharedUboInitialSizeInBytes.toInt())
            return s
        }
    }

    actual class Builder actual constructor() {
        init { ensureFilamentLoaded() }
        private val nativeBuilder = FilamentC.FilaEngineBuilder_create()

        actual fun backend(backend: Backend): Builder {
            FilamentC.FilaEngineBuilder_backend(nativeBuilder, backend.toNative())
            return this
        }

        actual fun sharedContext(sharedContext: Any): Builder {
            // sharedContext is void* in C, but Any in KMP. Only a Long address is meaningful here.
            if (sharedContext is Long) {
                FilamentC.FilaEngineBuilder_sharedContext(nativeBuilder, MemorySegment.ofAddress(sharedContext))
            }
            return this
        }

        actual fun config(config: Config): Builder {
            confined { arena -> FilamentC.FilaEngineBuilder_config(nativeBuilder, config.toNative(arena)) }
            return this
        }

        actual fun featureLevel(featureLevel: FeatureLevel): Builder {
            FilamentC.FilaEngineBuilder_featureLevel(nativeBuilder, featureLevel.toNative())
            return this
        }

        actual fun paused(paused: Boolean): Builder {
            FilamentC.FilaEngineBuilder_paused(nativeBuilder, paused)
            return this
        }

        actual fun feature(name: String, value: Boolean): Builder {
            confined { arena -> FilamentC.FilaEngineBuilder_feature(nativeBuilder, arena.cstr(name), value) }
            return this
        }

        actual fun colorGrading(colorGrading: ColorGrading.Builder): Builder {
            FilamentC.FilaEngineBuilder_colorGrading(nativeBuilder, colorGrading.nativeHandle)
            return this
        }

        actual fun build(): Engine {
            val handle = FilamentC.FilaEngineBuilder_build(nativeBuilder)
            FilamentC.FilaEngineBuilder_destroy(nativeBuilder)
            if (handle.isNullPtr()) throw IllegalStateException("Failed to build Engine")
            return Engine(handle)
        }
    }

    actual companion object {
        actual fun create(): Engine = Builder().build()
        actual fun create(backend: Backend): Engine = Builder().backend(backend).build()
        actual fun create(sharedContext: Any): Engine = Builder().sharedContext(sharedContext).build()
        actual fun getSteadyClockTimeNano(): Long {
            ensureFilamentLoaded()
            return FilamentC.FilaEngine_getSteadyClockTimeNano()
        }
    }

    actual fun isValid(): Boolean = !nativeHandle.isNullPtr()
    actual fun destroy() {
        nativeHandle?.let { FilamentC.FilaEngine_destroy(it) }
        nativeHandle = null
    }

    actual val backend: Backend get() = Backend.fromNative(FilamentC.FilaEngine_getBackend(nativeHandle))
    actual val supportedFeatureLevel: FeatureLevel get() = FeatureLevel.fromNative(FilamentC.FilaEngine_getSupportedFeatureLevel(nativeHandle))
    actual fun setActiveFeatureLevel(featureLevel: FeatureLevel): FeatureLevel = FeatureLevel.fromNative(FilamentC.FilaEngine_setActiveFeatureLevel(nativeHandle, featureLevel.toNative()))
    actual fun getActiveFeatureLevel(): FeatureLevel = FeatureLevel.fromNative(FilamentC.FilaEngine_getActiveFeatureLevel(nativeHandle))
    actual fun setAutomaticInstancingEnabled(enable: Boolean) = FilamentC.FilaEngine_setAutomaticInstancingEnabled(nativeHandle, enable)
    actual fun isAutomaticInstancingEnabled(): Boolean = FilamentC.FilaEngine_isAutomaticInstancingEnabled(nativeHandle)
    actual val config: Config get() {
        // C-wrapper doesn't expose getConfig; return defaults (matches nativeMain).
        return Config()
    }
    actual fun getMaxStereoscopicEyes(): Long = FilamentC.FilaEngine_getMaxStereoscopicEyes(nativeHandle)

    actual fun isValidRenderer(renderer: Renderer): Boolean = FilamentC.FilaEngine_isValidRenderer(nativeHandle, renderer.nativeHandle)
    actual fun isValidView(view: View): Boolean = FilamentC.FilaEngine_isValidView(nativeHandle, view.nativeHandle)
    actual fun isValidScene(scene: Scene): Boolean = FilamentC.FilaEngine_isValidScene(nativeHandle, scene.nativeHandle)
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — not bound in filament.js.")
    actual fun isValidFence(fence: Fence): Boolean = FilamentC.FilaEngine_isValidFence(nativeHandle, fence.nativeHandle)
    actual fun isValidIndexBuffer(indexBuffer: IndexBuffer): Boolean = FilamentC.FilaEngine_isValidIndexBuffer(nativeHandle, indexBuffer.nativeHandle)
    actual fun isValidVertexBuffer(vertexBuffer: VertexBuffer): Boolean = FilamentC.FilaEngine_isValidVertexBuffer(nativeHandle, vertexBuffer.nativeHandle)
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — not bound in filament.js.")
    actual fun isValidSkinningBuffer(skinningBuffer: SkinningBuffer): Boolean = FilamentC.FilaEngine_isValidSkinningBuffer(nativeHandle, skinningBuffer.nativeHandle)
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — not bound in filament.js.")
    actual fun isValidMorphTargetBuffer(morphTargetBuffer: MorphTargetBuffer): Boolean = FilamentC.FilaEngine_isValidMorphTargetBuffer(nativeHandle, morphTargetBuffer.nativeHandle)
    actual fun isValidIndirectLight(ibl: IndirectLight): Boolean = FilamentC.FilaEngine_isValidIndirectLight(nativeHandle, ibl.nativeHandle)
    actual fun isValidMaterial(material: Material): Boolean = FilamentC.FilaEngine_isValidMaterial(nativeHandle, material.nativeHandle)
    actual fun isValidMaterialInstance(material: Material, materialInstance: MaterialInstance): Boolean = FilamentC.FilaEngine_isValidMaterialInstance(nativeHandle, material.nativeHandle, materialInstance.nativeHandle)
    actual fun isValidExpensiveMaterialInstance(materialInstance: MaterialInstance): Boolean = FilamentC.FilaEngine_isValidExpensiveMaterialInstance(nativeHandle, materialInstance.nativeHandle)
    actual fun isValidSkybox(skybox: Skybox): Boolean = FilamentC.FilaEngine_isValidSkybox(nativeHandle, skybox.nativeHandle)
    actual fun isValidColorGrading(colorGrading: ColorGrading): Boolean = FilamentC.FilaEngine_isValidColorGrading(nativeHandle, colorGrading.nativeHandle)
    actual fun isValidTexture(texture: Texture): Boolean = FilamentC.FilaEngine_isValidTexture(nativeHandle, texture.nativeHandle)
    actual fun isValidRenderTarget(renderTarget: RenderTarget): Boolean = FilamentC.FilaEngine_isValidRenderTarget(nativeHandle, renderTarget.nativeHandle)
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — not bound in filament.js.")
    actual fun isValidStream(stream: Stream): Boolean = FilamentC.FilaEngine_isValidStream(nativeHandle, stream.nativeHandle)
    actual fun isValidSwapChain(swapChain: SwapChain): Boolean = FilamentC.FilaEngine_isValidSwapChain(nativeHandle, swapChain.nativeHandle)

    actual fun createSwapChain(surface: NativeSurface): SwapChain = SwapChain(FilamentC.FilaEngine_createSwapChain(nativeHandle, surface.handle, 0L))
    actual fun createSwapChain(surface: NativeSurface, flags: Long): SwapChain = SwapChain(FilamentC.FilaEngine_createSwapChain(nativeHandle, surface.handle, flags))
    actual fun createSwapChain(width: Int, height: Int, flags: Long): SwapChain = SwapChain(FilamentC.FilaEngine_createSwapChainHeadless(nativeHandle, width, height, flags))
    actual fun destroySwapChain(swapChain: SwapChain) {
        FilamentC.FilaEngine_destroySwapChain(nativeHandle, swapChain.nativeHandle)
        swapChain.nativeHandle = null
    }

    actual fun createView(): View = View(FilamentC.FilaEngine_createView(nativeHandle))
    actual fun destroyView(view: View) {
        FilamentC.FilaEngine_destroyView(nativeHandle, view.nativeHandle)
        view.nativeHandle = null
    }

    actual fun createRenderer(): Renderer = Renderer(this, FilamentC.FilaEngine_createRenderer(nativeHandle))
    actual fun destroyRenderer(renderer: Renderer) {
        FilamentC.FilaEngine_destroyRenderer(nativeHandle, renderer.nativeHandle)
        renderer.nativeHandle = null
    }

    actual fun createCamera(): Camera {
        val handle = FilamentC.FilaEngine_createCameraAuto(nativeHandle)
        val entity = FilamentC.FilaCamera_getEntity(handle)
        return Camera(handle, entity)
    }
    actual fun createCamera(entity: Entity): Camera = Camera(FilamentC.FilaEngine_createCamera(nativeHandle, entity), entity)
    actual fun getCameraComponent(entity: Entity): Camera? {
        val handle = FilamentC.FilaEngine_getCameraComponent(nativeHandle, entity)
        return if (!handle.isNullPtr()) Camera(handle, entity) else null
    }
    actual fun destroyCamera(camera: Camera) {
        FilamentC.FilaEngine_destroyCamera(nativeHandle, camera.nativeHandle)
        camera.nativeHandle = null
    }
    actual fun destroyCameraComponent(entity: Entity) = FilamentC.FilaEngine_destroyCameraComponent(nativeHandle, entity)

    actual fun createScene(): Scene = Scene(FilamentC.FilaEngine_createScene(nativeHandle))
    actual fun destroyScene(scene: Scene) {
        FilamentC.FilaEngine_destroyScene(nativeHandle, scene.nativeHandle)
        scene.nativeHandle = null
    }

    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — fences are not bound in filament.js.")
    actual fun createFence(): Fence = Fence(FilamentC.FilaEngine_createFence(nativeHandle))
    actual fun destroyFence(fence: Fence) {
        FilamentC.FilaEngine_destroyFence(nativeHandle, fence.nativeHandle)
        fence.nativeHandle = null
    }

    actual fun destroyIndexBuffer(indexBuffer: IndexBuffer) {
        FilamentC.FilaEngine_destroyIndexBuffer(nativeHandle, indexBuffer.nativeHandle)
        indexBuffer.nativeHandle = null
    }
    actual fun destroyVertexBuffer(vertexBuffer: VertexBuffer) {
        FilamentC.FilaEngine_destroyVertexBuffer(nativeHandle, vertexBuffer.nativeHandle)
        vertexBuffer.nativeHandle = null
    }
    actual fun destroySkinningBuffer(skinningBuffer: SkinningBuffer) {
        FilamentC.FilaEngine_destroySkinningBuffer(nativeHandle, skinningBuffer.nativeHandle)
        skinningBuffer.nativeHandle = null
    }
    actual fun destroyMorphTargetBuffer(morphTargetBuffer: MorphTargetBuffer) {
        FilamentC.FilaEngine_destroyMorphTargetBuffer(nativeHandle, morphTargetBuffer.nativeHandle)
        morphTargetBuffer.nativeHandle = null
    }
    actual fun destroyIndirectLight(ibl: IndirectLight) {
        FilamentC.FilaEngine_destroyIndirectLight(nativeHandle, ibl.nativeHandle)
        ibl.nativeHandle = null
    }
    actual fun destroyMaterial(material: Material) {
        FilamentC.FilaEngine_destroyMaterial(nativeHandle, material.nativeHandle)
    }
    actual fun destroyMaterialInstance(materialInstance: MaterialInstance) {
        FilamentC.FilaEngine_destroyMaterialInstance(nativeHandle, materialInstance.nativeHandle)
    }
    actual fun destroySkybox(skybox: Skybox) {
        FilamentC.FilaEngine_destroySkybox(nativeHandle, skybox.nativeHandle)
        skybox.nativeHandle = null
    }
    actual fun destroyColorGrading(colorGrading: ColorGrading) {
        FilamentC.FilaEngine_destroyColorGrading(nativeHandle, colorGrading.nativeHandle)
        colorGrading.nativeHandle = null
    }
    actual fun destroyTexture(texture: Texture) {
        FilamentC.FilaEngine_destroyTexture(nativeHandle, texture.nativeHandle)
    }
    actual fun destroyRenderTarget(target: RenderTarget) {
        FilamentC.FilaEngine_destroyRenderTarget(nativeHandle, target.nativeHandle)
    }
    actual fun destroyStream(stream: Stream) {
        FilamentC.FilaEngine_destroyStream(nativeHandle, stream.nativeHandle)
        stream.nativeHandle = null
    }
    actual fun destroyEntity(entity: Entity) = FilamentC.FilaEntityManager_destroy(FilamentC.FilaEngine_getEntityManager(nativeHandle), entity)

    actual fun getTransformManager(): TransformManager = mTransformManager
    actual fun getLightManager(): LightManager = mLightManager
    actual fun getRenderableManager(): RenderableManager = mRenderableManager
    actual fun getEntityManager(): EntityManager = mEntityManager

    actual fun flushAndWait() { FilamentC.FilaEngine_flushAndWait(nativeHandle, 1_000_000_000L) }
    actual fun flushAndWait(timeout: Long): Boolean = FilamentC.FilaEngine_flushAndWait(nativeHandle, timeout)
    actual fun flush() = FilamentC.FilaEngine_flush(nativeHandle)
    actual fun hasUnrecoverableFailure(): Boolean = FilamentC.FilaEngine_hasUnrecoverableFailure(nativeHandle)
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "state is only tracked locally — filament.js does not bind pause, so it has no effect on rendering.")
    actual var paused: Boolean
        get() = FilamentC.FilaEngine_isPaused(nativeHandle)
        set(value) { FilamentC.FilaEngine_setPaused(nativeHandle, value) }
    actual fun unprotected() = FilamentC.FilaEngine_unprotected(nativeHandle)
    actual fun hasFeatureFlag(name: String): Boolean = confined { arena -> FilamentC.FilaEngine_hasFeatureFlag(nativeHandle, arena.cstr(name)) }
    actual fun setFeatureFlag(name: String, value: Boolean): Boolean = confined { arena ->
        FilamentC.FilaEngine_setFeatureFlag(nativeHandle, arena.cstr(name), value)
        true
    }
    actual fun getFeatureFlag(name: String): Boolean = confined { arena -> FilamentC.FilaEngine_getFeatureFlag(nativeHandle, arena.cstr(name)) }

    actual fun enableAccurateTranslations() = FilamentC.FilaEngine_enableAccurateTranslations(nativeHandle)

    actual enum class CompilerPriorityQueue { CRITICAL, HIGH, LOW }
    actual enum class FeatureState { FALSE, TRUE, INDETERMINATE }

    actual fun compile(priority: CompilerPriorityQueue, material: Material, view: View, shadowReceiver: FeatureState, skinning: FeatureState, callback: (() -> Unit)?) {
        val cb: MemorySegment
        val userData: MemorySegment
        if (callback != null) {
            cb = Completions.compileStub
            userData = Completions.register(callback)
        } else {
            cb = NULL
            userData = NULL
        }
        FilamentC.FilaEngine_compile(
            nativeHandle,
            priority.ordinal.toByte(),
            material.nativeHandle,
            view.nativeHandle,
            shadowReceiver.ordinal.toByte(),
            skinning.ordinal.toByte(),
            cb,
            userData,
        )
    }
}
