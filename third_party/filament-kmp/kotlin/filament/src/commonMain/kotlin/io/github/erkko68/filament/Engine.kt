package io.github.erkko68.filament

/**
 * Engine is Filament's main entry-point.
 *
 * An Engine instance keeps track of all resources created by the user and manages the
 * rendering thread as well as the hardware renderer.
 *
 * To use Filament, an Engine instance must be created first using `Engine.create()`.
 * Engine essentially represents (or is associated with) a hardware context (e.g., an OpenGL ES
 * context or a Vulkan device).
 *
 * Rendering typically happens in an operating system's window (which can be fullscreen), which is
 * managed by a Renderer.
 *
 * A typical Filament render loop looks like this:
 *
 * ```
 * val engine = Engine.create()
 * val swapChain = engine.createSwapChain(nativeWindow)
 * val renderer = engine.createRenderer()
 * val scene = engine.createScene()
 * val view = engine.createView()
 *
 * view.setScene(scene)
 *
 * while (!quit) {
 *     // Wait for VSYNC and user input events
 *     if (renderer.beginFrame(swapChain)) {
 *         renderer.render(view)
 *         renderer.endFrame()
 *     }
 * }
 *
 * engine.destroy(view)
 * engine.destroy(scene)
 * engine.destroy(renderer)
 * engine.destroy(swapChain)
 * Engine.destroy(engine)
 * ```
 */
expect class Engine {
    /**
     * Rendering backend selection.
     */
    enum class Backend {
        /** Platform's optimal choice (usually Vulkan or Metal) */
        DEFAULT,
        /** OpenGL ES */
        OPENGL,
        /** Vulkan */
        VULKAN,
        /** Metal (iOS/macOS) */
        METAL,
        /** WebGPU */
        WEBGPU,
        /** No-op backend for testing */
        NOOP,
    }

    /**
     * Backend feature levels control available rendering capabilities and performance characteristics.
     *
     * Higher feature levels provide more capabilities but require more powerful hardware.
     */
    enum class FeatureLevel {
        /**
         * Minimum feature set; OpenGL ES 2 compatible.
         * No post-processing, limited lighting models, minimal texture formats.
         */
        FEATURE_LEVEL_0,
        /** Metal-level feature set; good for mid-range devices. */
        FEATURE_LEVEL_1,
        /** Full feature set with all capabilities. */
        FEATURE_LEVEL_2,
        /** Advanced features beyond the standard feature set. */
        FEATURE_LEVEL_3,
    }

    /**
     * Stereoscopic rendering technique for VR and 3D displays.
     */
    enum class StereoscopicType {
        /** No stereoscopic rendering (monoscopic). */
        NONE,
        /** Instanced stereo rendering (two draw calls, one per eye). */
        INSTANCED,
        /** Multiview stereo rendering (single draw call using instancing, faster). */
        MULTIVIEW,
    }

    /**
     * GPU context priority for work scheduling and preemption.
     *
     * Used to hint the GPU driver about the priority of this context's work.
     */
    enum class GpuContextPriority {
        /** Default priority. */
        DEFAULT,
        /** Low priority; can be preempted by other work. */
        LOW,
        /** Medium priority. */
        MEDIUM,
        /** High priority. */
        HIGH,
        /** Real-time priority; minimal preemption. */
        REALTIME,
    }

    /**
     * Advanced parameters for customizing Engine initialization.
     *
     * These settings control memory allocation, threading, and rendering behavior.
     */
    class Config() {
        /** Size of the command buffer in MB (default depends on backend). */
        var commandBufferSizeMB: Long
        /** Per-render-pass arena size in MB. */
        var perRenderPassArenaSizeMB: Long
        /** Driver handle arena size in MB. */
        var driverHandleArenaSizeMB: Long
        /** Minimum command buffer size in MB. */
        var minCommandBufferSizeMB: Long
        /** Size of per-frame commands in MB. */
        var perFrameCommandsSizeMB: Long
        /** Number of threads for the job system (0 = CPU count). */
        var jobSystemThreadCount: Long
        /** Stereoscopic rendering technique to use. */
        var stereoscopicType: StereoscopicType
        /** Number of stereoscopic eyes (usually 2 for VR). */
        var stereoscopicEyeCount: Long
        /** Size of the resource allocator cache in MB. */
        var resourceAllocatorCacheSizeMB: Long
        /** Maximum age of cached resources (in frames). */
        var resourceAllocatorCacheMaxAge: Long

        /**
         * Preferred shader language for platform.
         */
        enum class ShaderLanguage {
            /** Use platform default. */
            DEFAULT,
            /** Metal Shading Language (Apple). */
            MSL,
            /** Pre-compiled Metal library. */
            METAL_LIBRARY,
        }

        /** Preferred shader language to use. */
        var preferredShaderLanguage: ShaderLanguage
        /** Force OpenGL ES 2.0 context (if applicable). */
        var forceGLES2Context: Boolean
        /** GPU context priority hint for the driver. */
        var gpuContextPriority: GpuContextPriority
        /** Initial size of shared uniform buffer objects in bytes. */
        var sharedUboInitialSizeInBytes: Long
    }

    /**
     * Builder for creating and configuring an Engine instance.
     */
    class Builder() {
        /**
         * Set the rendering backend to use.
         *
         * @param backend The backend to use (DEFAULT lets the system choose).
         * @return This Builder, for chaining calls.
         */
        fun backend(backend: Backend): Builder

        /**
         * Share a platform-specific rendering context with the Engine.
         *
         * This is useful for rendering to multiple windows or integrating with
         * existing rendering systems.
         *
         * @param sharedContext Platform-specific context object (e.g., EGLContext on Android).
         * @return This Builder, for chaining calls.
         */
        fun sharedContext(sharedContext: Any): Builder

        /**
         * Set advanced Engine configuration options.
         *
         * @param config Configuration object with memory and threading settings.
         * @return This Builder, for chaining calls.
         */
        fun config(config: Config): Builder

        /**
         * Set the feature level to use.
         *
         * The effective feature level is the minimum of this value and the backend's maximum.
         *
         * @param featureLevel Desired feature level.
         * @return This Builder, for chaining calls.
         */
        fun featureLevel(featureLevel: FeatureLevel): Builder

        /**
         * Pause rendering immediately after Engine creation.
         *
         * Set `engine.paused = false` to resume.
         *
         * @param paused true to start paused, false to start active.
         * @return This Builder, for chaining calls.
         */
        fun paused(paused: Boolean): Builder

        /**
         * Enable or disable a feature flag.
         *
         * @param name Feature flag name.
         * @param value true to enable, false to disable.
         * @return This Builder, for chaining calls.
         */
        fun feature(name: String, value: Boolean): Builder

        /**
         * Set the default color grading configuration.
         *
         * @param colorGrading ColorGrading.Builder with default configuration.
         * @return This Builder, for chaining calls.
         */
        fun colorGrading(colorGrading: ColorGrading.Builder): Builder

        /**
         * Creates the Engine instance.
         *
         * @return The newly created Engine.
         */
        fun build(): Engine
    }

    companion object {
        /**
         * Create an Engine with the platform's optimal backend (usually Vulkan or Metal).
         *
         * @return A new Engine instance using the default backend.
         */
        fun create(): Engine

        /**
         * Create an Engine with a specific rendering backend.
         *
         * @param backend The backend to use (OPENGL, VULKAN, METAL, WEBGPU, or NOOP).
         * @return A new Engine instance using the specified backend.
         */
        fun create(backend: Backend): Engine

        /**
         * Create an Engine sharing a platform-specific rendering context.
         *
         * This allows multiple Engine instances or integration with existing rendering contexts.
         *
         * @param sharedContext Platform-specific context (e.g., EGLContext on Android).
         * @return A new Engine instance sharing the given context.
         */
        fun create(sharedContext: Any): Engine

        /**
         * Get the current steady clock time in nanoseconds.
         *
         * This is useful for frame timing and synchronization with Engine's frame pacing.
         *
         * @return Current time in nanoseconds since an unspecified epoch.
         */
        fun getSteadyClockTimeNano(): Long
    }

    /**
     * Check if this Engine is still valid (not destroyed).
     *
     * @return true if the Engine is valid and can be used, false if destroyed.
     */
    fun isValid(): Boolean

    /**
     * Destroy the Engine and all its resources.
     *
     * This is a blocking operation. All Renderer, View, Scene, and other resources
     * should ideally be destroyed first, though the Engine will clean up remaining resources.
     */
    fun destroy()

    /**
     * The rendering backend being used by this Engine.
     */
    val backend: Backend

    /**
     * The highest feature level supported by this backend.
     */
    val supportedFeatureLevel: FeatureLevel

    /**
     * Set the active feature level.
     *
     * The feature level must not exceed the supported level for this backend.
     *
     * @param featureLevel The desired FeatureLevel.
     * @return The actually set FeatureLevel (may be clamped to supported level).
     */
    fun setActiveFeatureLevel(featureLevel: FeatureLevel): FeatureLevel

    /**
     * Get the currently active feature level.
     *
     * @return The active FeatureLevel.
     */
    fun getActiveFeatureLevel(): FeatureLevel

    /**
     * Enable or disable automatic GPU instancing of identical drawables.
     *
     * When enabled, the engine automatically batches identical renderables to reduce draw calls.
     *
     * @param enable true to enable instancing, false to disable.
     */
    fun setAutomaticInstancingEnabled(enable: Boolean)

    /**
     * Check if automatic GPU instancing is enabled.
     *
     * @return true if instancing is enabled, false otherwise.
     */
    fun isAutomaticInstancingEnabled(): Boolean

    /**
     * The Engine's advanced configuration — the Config object used when creating this Engine.
     */
    val config: Config

    /**
     * Get the maximum number of stereoscopic eyes configured for this Engine.
     *
     * @return Number of eyes (typically 2 for VR, 1 for monoscopic).
     */
    fun getMaxStereoscopicEyes(): Long
    /**
     * Validate a Renderer object created by this Engine.
     *
     * @param renderer Renderer to check.
     * @return true if the Renderer is valid and owned by this Engine.
     */
    fun isValidRenderer(renderer: Renderer): Boolean

    /** Validate a View. @return true if valid. */
    fun isValidView(view: View): Boolean
    /** Validate a Scene. @return true if valid. */
    fun isValidScene(scene: Scene): Boolean
    /** Validate a Fence. @return true if valid. @throws UnsupportedOperationException on JS — Fence is unbound on web. */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — not bound in filament.js.")
    fun isValidFence(fence: Fence): Boolean
    /** Validate a RenderTarget. @return true if valid. */
    fun isValidRenderTarget(renderTarget: RenderTarget): Boolean
    /** Validate an IndexBuffer. @return true if valid. */
    fun isValidIndexBuffer(indexBuffer: IndexBuffer): Boolean
    /** Validate a VertexBuffer. @return true if valid. */
    fun isValidVertexBuffer(vertexBuffer: VertexBuffer): Boolean
    /** Validate a SkinningBuffer. @return true if valid. @throws UnsupportedOperationException on JS — SkinningBuffer is unbound on web. */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — not bound in filament.js.")
    fun isValidSkinningBuffer(skinningBuffer: SkinningBuffer): Boolean
    /** Validate a MorphTargetBuffer. @return true if valid. @throws UnsupportedOperationException on JS — MorphTargetBuffer is unbound on web. */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — not bound in filament.js.")
    fun isValidMorphTargetBuffer(morphTargetBuffer: MorphTargetBuffer): Boolean
    /** Validate an IndirectLight. @return true if valid. */
    fun isValidIndirectLight(ibl: IndirectLight): Boolean
    /** Validate a Material. @return true if valid. */
    fun isValidMaterial(material: Material): Boolean
    /** Validate a MaterialInstance for a given Material. @return true if valid. */
    fun isValidMaterialInstance(material: Material, materialInstance: MaterialInstance): Boolean
    /** Validate a MaterialInstance (more expensive check). @return true if valid. */
    fun isValidExpensiveMaterialInstance(materialInstance: MaterialInstance): Boolean
    /** Validate a Skybox. @return true if valid. */
    fun isValidSkybox(skybox: Skybox): Boolean
    /** Validate ColorGrading. @return true if valid. */
    fun isValidColorGrading(colorGrading: ColorGrading): Boolean
    /** Validate a Texture. @return true if valid. */
    fun isValidTexture(texture: Texture): Boolean
    /** Validate a Stream. @return true if valid. @throws UnsupportedOperationException on JS — Stream is unbound on web. */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — not bound in filament.js.")
    fun isValidStream(stream: Stream): Boolean
    /** Validate a SwapChain. @return true if valid. */
    fun isValidSwapChain(swapChain: SwapChain): Boolean

    /** Create a SwapChain from a native display surface. */
    fun createSwapChain(surface: NativeSurface): SwapChain
    /** Create a SwapChain from a native display surface with flags. */
    fun createSwapChain(surface: NativeSurface, flags: Long): SwapChain
    /** Create an offscreen SwapChain of specified dimensions. */
    fun createSwapChain(width: Int, height: Int, flags: Long): SwapChain
    /** Destroy a SwapChain. */
    fun destroySwapChain(swapChain: SwapChain)

    /** Create a View for rendering. */
    fun createView(): View
    /** Destroy a View. */
    fun destroyView(view: View)

    /** Create a Renderer associated with this Engine. */
    fun createRenderer(): Renderer
    /** Destroy a Renderer. */
    fun destroyRenderer(renderer: Renderer)

    /** Create a Camera as a standalone component. */
    fun createCamera(): Camera
    /** Create a Camera attached to an entity. */
    fun createCamera(entity: Entity): Camera
    /** Get the Camera component attached to an entity, or null if not present. */
    fun getCameraComponent(entity: Entity): Camera?
    /** Destroy a Camera. */
    fun destroyCamera(camera: Camera)
    /** Destroy the Camera component on an entity. */
    fun destroyCameraComponent(entity: Entity)

    /** Create a Scene for collecting renderable objects. */
    fun createScene(): Scene
    /** Destroy a Scene. */
    fun destroyScene(scene: Scene)

    /** Create a Fence for GPU synchronization. @throws UnsupportedOperationException on JS — fences are unbound on web. */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "throws UnsupportedOperationException — fences are not bound in filament.js.")
    fun createFence(): Fence
    /** Destroy a Fence. */
    fun destroyFence(fence: Fence)

    /** Destroy an IndexBuffer. */
    fun destroyIndexBuffer(indexBuffer: IndexBuffer)
    /** Destroy a VertexBuffer. */
    fun destroyVertexBuffer(vertexBuffer: VertexBuffer)
    /** Destroy a SkinningBuffer. */
    fun destroySkinningBuffer(skinningBuffer: SkinningBuffer)
    /** Destroy a MorphTargetBuffer. */
    fun destroyMorphTargetBuffer(morphTargetBuffer: MorphTargetBuffer)
    /** Destroy an IndirectLight. */
    fun destroyIndirectLight(ibl: IndirectLight)
    /** Destroy a Material. */
    fun destroyMaterial(material: Material)
    /** Destroy a MaterialInstance. */
    fun destroyMaterialInstance(materialInstance: MaterialInstance)
    /** Destroy a Skybox. */
    fun destroySkybox(skybox: Skybox)
    /** Destroy ColorGrading. */
    fun destroyColorGrading(colorGrading: ColorGrading)
    /** Destroy a Texture. */
    fun destroyTexture(texture: Texture)
    /** Destroy a RenderTarget. */
    fun destroyRenderTarget(target: RenderTarget)
    /** Destroy a Stream. */
    fun destroyStream(stream: Stream)
    /** Destroy an Entity. */
    fun destroyEntity(entity: Entity)

    /** Get the TransformManager for managing entity transforms. */
    fun getTransformManager(): TransformManager
    /** Get the LightManager for managing light components. */
    fun getLightManager(): LightManager
    /** Get the RenderableManager for managing renderable components. */
    fun getRenderableManager(): RenderableManager
    /** Get the EntityManager for creating and managing entities. */
    fun getEntityManager(): EntityManager

    /** Block until all pending GPU work completes (potentially long wait). */
    fun flushAndWait()
    /** Block until all pending GPU work completes or timeout expires. @return true if successful. */
    fun flushAndWait(timeout: Long): Boolean
    /** Flush pending GPU commands to the driver (non-blocking). */
    fun flush()
    /**
     * Whether the Engine is in an unrecoverable failure state (e.g. the GPU device was lost).
     * Once true, the Engine must be destroyed and recreated. @return true if such a failure occurred.
     */
    fun hasUnrecoverableFailure(): Boolean
    /** Whether rendering is currently paused. Set to pause or resume rendering. */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "state is only tracked locally — filament.js does not bind pause, so it has no effect on rendering.")
    var paused: Boolean
    /** Deprecated no-op method. */
    fun unprotected()
    /** Check if a feature flag exists. */
    fun hasFeatureFlag(name: String): Boolean
    /** Set a feature flag value. @return true if successful. */
    fun setFeatureFlag(name: String, value: Boolean): Boolean
    /** Get a feature flag value. @return true if enabled. */
    fun getFeatureFlag(name: String): Boolean

    /** Enable high-precision world-space translations for better numerical stability with large translations. */
    fun enableAccurateTranslations()

    /**
     * Material compilation priority queue.
     */
    enum class CompilerPriorityQueue {
        /** Compile immediately. */
        CRITICAL,
        /** Compile before LOW priority. */
        HIGH,
        /** Compile last. */
        LOW
    }

    /**
     * Feature state for conditional material compilation.
     */
    enum class FeatureState {
        /** Feature is disabled. */
        FALSE,
        /** Feature is enabled. */
        TRUE,
        /** Feature state is uncertain; material may compile both variants. */
        INDETERMINATE
    }

    /**
     * Asynchronously compile a material variant for specific rendering features.
     *
     * After issuing multiple compile() calls, call flush() to let the backend begin work.
     * The callback is invoked on the main thread when compilation is complete.
     *
     * @param priority Compilation priority (CRITICAL, HIGH, or LOW).
     * @param material Material to compile variants for.
     * @param view View providing rendering context.
     * @param shadowReceiver Whether the material receives shadows.
     * @param skinning Whether the material uses skeletal animation.
     * @param callback Optional callback invoked when compilation completes.
     */
    fun compile(priority: CompilerPriorityQueue, material: Material, view: View, shadowReceiver: FeatureState, skinning: FeatureState, callback: (() -> Unit)? = null)
}
