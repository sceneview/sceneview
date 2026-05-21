package io.github.sceneview.web

import io.github.sceneview.web.bindings.*
import io.github.sceneview.web.nodes.CameraConfig
import io.github.sceneview.web.nodes.GeometryConfig
import io.github.sceneview.web.nodes.LightConfig
import io.github.sceneview.web.nodes.LightType
import io.github.sceneview.web.nodes.ModelConfig
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement

/**
 * SceneView for Web -- Filament.js based 3D viewer.
 *
 * Uses the same Filament rendering engine as SceneView Android,
 * compiled to WebAssembly for browser execution.
 *
 * This class actually initializes the Filament WASM module, creates a real
 * WebGL2 rendering context, and renders 3D content using the GPU.
 *
 * Basic usage:
 * ```kotlin
 * SceneView.create(canvas) {
 *     camera {
 *         eye(0.0, 1.5, 5.0)
 *         target(0.0, 0.0, 0.0)
 *     }
 *     light {
 *         directional()
 *         intensity(100_000.0)
 *     }
 *     model("models/damaged_helmet.glb")
 * }
 * ```
 */
class SceneView private constructor(
    val canvas: HTMLCanvasElement,
    val engine: Engine,
    val renderer: Renderer,
    val scene: Scene,
    val view: View,
    val camera: Camera,
    val swapChain: SwapChain,
    private val cameraEntity: Entity
) {
    private var animationFrameId: Int? = null
    private var isRunning = false
    private var lastTimestamp = 0.0

    /** Monotonic counter for synthesising unique tracker keys for un-keyed
     *  (procedural geometry) assets — see [loadedAssets]. */
    private var assetKeySeq = 0

    private val models = mutableListOf<LoadedModel>()
    private var assetLoader: AssetLoader? = null
    private val lightEntities = mutableListOf<Entity>()

    /**
     * Tracks the Filament `IndirectLight` (IBL) handle currently bound to the
     * scene so [loadEnvironment] can destroy a previous IBL before binding a
     * new one and [destroy] can release it — without this the GPU resource
     * leaks (issue #1496).
     */
    private val indirectLight = EnvironmentResourceTracker<IndirectLight> {
        engine.destroyIndirectLight(it)
    }

    /**
     * Tracks the Filament `Skybox` handle currently bound to the scene, for the
     * same leak-free replacement / teardown reason as [indirectLight]
     * (issue #1496).
     */
    private val skybox = EnvironmentResourceTracker<Skybox> {
        engine.destroySkybox(it)
    }

    /**
     * Single owner of every gltfio [FilamentAsset] live in the scene — both
     * URL-loaded models ([loadModel], keyed by URL) and procedural geometry
     * ([addGeometry], keyed by a synthetic id). Destroying a replaced asset
     * before adopting its successor stops the #1597 GPU leak (a 2nd `loadModel`
     * of the same URL previously orphaned the prior asset), and [release] tears
     * down everything still held at [destroy] time. Mirrors the
     * [indirectLight] / [skybox] leak-free-swap pattern of #1496.
     */
    private val loadedAssets = AssetResourceTracker<FilamentAsset> { asset ->
        scene.removeEntities(asset.getEntities())
        assetLoader?.destroyAsset(asset)
    }

    /**
     * When `true` (default), the first render frame where the loaded content's
     * union bounding box becomes non-degenerate triggers a one-time translation
     * of every loaded asset's root entity so the scene's centroid lands at the
     * world origin — i.e. at the orbit-camera target.
     *
     * Library-level port of the iOS `autoCenterContent` (issue #1026): demos
     * that place models at non-origin positions (e.g. `z = -2`) then render
     * visually centred in the canvas without each demo having to re-centre
     * itself. Set to `false` for narrative scenes that rely on intentional
     * off-centre placement. Cross-platform parity with iOS #1026 and the
     * Android sibling #1051. Closes #1052.
     */
    var autoCenterContent: Boolean = true

    /**
     * Union-diagonal-stability gate for [refreshContentCentering]: re-frames
     * the scene on every union-bounds growth (so a deferred async model still
     * re-centres — #1540), and latches only once the union diagonal has
     * settled across consecutive frames. Mirrors iOS's `lastFramedDiagonal` +
     * `framingStabilityEpsilon` logic (#1391).
     */
    private val autoCenterGate = AutoCenterGate()

    /** Tracks a loaded glTF asset with its animation state. */
    private class LoadedModel(
        val asset: FilamentAsset,
        val animator: Animator?,
        var animationTime: Double = 0.0
    ) {
        /**
         * `false` until `loadResources` has finished populating this asset's
         * renderable buffers/textures. While `false` the model's
         * `getBoundingBox()` reports a degenerate/wrong box, so the auto-center
         * pass ([refreshContentCentering] via [contentBoxes]) must exclude it —
         * otherwise it frames the scene on an unreadable diagonal (#1597).
         */
        var loaded: Boolean = false

        /**
         * The asset's root-entity transform *before* any auto-center offset
         * was applied — captured the first frame [refreshContentCentering]
         * touches this model. The auto-center pass re-runs every time a
         * deferred async sibling grows the union (#1540), so it must compose
         * each new offset onto this stored base rather than onto the
         * already-offset transform — otherwise the translation accumulates.
         * Filament.js represents a mat4 as a flat 16-element `number[]`.
         */
        var baseTransform: dynamic = null

        /**
         * `true` once this model's [asset] has been destroyed — either
         * replaced by a 2nd `loadModel` of the same URL, or torn down by
         * [destroy]. Set the instant the asset is freed so the still-pending
         * `loadResources(onDone=...)` callback can detect it: a stale `onDone`
         * for a superseded model must become a safe no-op and never touch the
         * freed `FilamentAsset` (`releaseSourceData()`), never flip [loaded],
         * and never re-arm the auto-center gate. Without this guard a quick
         * `loadModel(url)` → `loadModel(url)` (or `destroy()`) before the
         * first load's resources finish is a use-after-free on the WASM
         * heap (#1597 Tier-2 review).
         */
        var superseded: Boolean = false
    }

    /** Orbit camera controller -- initialized when cameraControls is enabled. */
    var cameraController: OrbitCameraController? = null
        private set

    /** Enable orbit camera controls (mouse drag to orbit, scroll to zoom, touch support). */
    fun enableCameraControls(
        distance: Double = 5.0,
        targetX: Double = 0.0,
        targetY: Double = 0.0,
        targetZ: Double = 0.0,
        autoRotate: Boolean = false
    ): OrbitCameraController {
        val controller = OrbitCameraController(canvas, camera).apply {
            this.distance = distance
            target(targetX, targetY, targetZ)
            this.autoRotate = autoRotate
        }
        cameraController = controller
        return controller
    }

    companion object {
        /** Default IBL URL — same "neutral" environment as SceneView Android. */
        const val DEFAULT_IBL_URL = "https://sceneview.github.io/assets/environments/neutral_ibl.ktx"
        const val DEFAULT_SKYBOX_URL = "https://sceneview.github.io/assets/environments/neutral_skybox.ktx"

        /**
         * Initialize Filament WASM and create a SceneView instance.
         *
         * This is the main entry point. It:
         * 1. Calls Filament.init() to load and compile the WASM module
         * 2. Creates a Filament Engine with a WebGL2 context on the canvas
         * 3. Sets up Scene, View, Camera, Renderer, and SwapChain
         * 4. Applies the user's configuration (camera, lights, models)
         * 5. Calls onReady with the fully initialized SceneView
         *
         * @param canvas The HTML canvas element to render into
         * @param assets List of asset URLs to preload (IBL, skybox KTX files)
         * @param configure DSL block to configure the scene
         * @param onReady Callback when the SceneView is fully initialized
         */
        fun create(
            canvas: HTMLCanvasElement,
            assets: Array<String> = emptyArray(),
            configure: SceneViewBuilder.() -> Unit = {},
            onReady: (SceneView) -> Unit
        ) {
            // Step 1: Initialize Filament WASM module and preload any assets
            init(assets) {
                try {
                    // Step 2: Create the Filament engine with WebGL2 context
                    // Use dynamic call because webpack externals + Kotlin companion objects
                    // don't resolve correctly for Filament's static Engine.create()
                    val filament: dynamic = js("Filament")
                    val engine: Engine = filament.Engine.create(canvas).unsafeCast<Engine>()
                    val renderer = engine.createRenderer()
                    val scene = engine.createScene()
                    val swapChain = engine.createSwapChain()
                    val view = engine.createView()

                    // Step 3: Create camera entity and camera
                    val cameraEntity = (js("Filament.EntityManager.get().create()") as Entity)
                    val camera = engine.createCamera(cameraEntity)

                    // Step 4: Connect view to camera and scene
                    view.setCamera(camera)
                    view.setScene(scene)

                    // Step 5: Set viewport to canvas pixel dimensions
                    val width = canvas.width
                    val height = canvas.height
                    view.setViewport(viewport(0, 0, width, height))

                    // Step 6: Default camera setup -- perspective projection
                    val aspect = if (height > 0) width.toDouble() / height.toDouble() else 1.0
                    camera.setProjectionFov(
                        fovInDegrees = 45.0,
                        aspect = aspect,
                        near = 0.1,
                        far = 1000.0
                    )

                    // Default camera position: slightly above and back, looking at origin
                    camera.lookAt(
                        float3(0.0, 1.5, 5.0),   // eye
                        float3(0.0, 0.0, 0.0),   // center
                        float3(0.0, 1.0, 0.0)    // up
                    )

                    // Default exposure matching model-viewer's exposure=1.1
                    // This makes IBL-lit models look bright and vibrant
                    camera.setExposureDirect(1.1)

                    // Set clear color to near-black (clean dark background)
                    renderer.setClearOptions(js("({clearColor: [0.05, 0.05, 0.07, 1.0], clear: true})"))

                    // --- Quality defaults for PBR rendering ---
                    // Screen-space ambient occlusion (soft contact shadows)
                    view.setAmbientOcclusionOptions(js("""({
                        enabled: true,
                        radius: 0.3,
                        bias: 0.0005,
                        intensity: 1.0,
                        quality: 1
                    })"""))

                    // Subtle bloom for emissive/bright highlights
                    view.setBloomOptions(js("""({
                        enabled: true,
                        strength: 0.1,
                        threshold: true,
                        levels: 4
                    })"""))

                    // Temporal anti-aliasing for smooth edges
                    view.setTemporalAntiAliasingOptions(js("""({
                        enabled: true
                    })"""))

                    val sceneView = SceneView(
                        canvas, engine, renderer, scene, view, camera, swapChain, cameraEntity
                    )

                    // Step 7: Apply user configuration (camera, lights, models, environment)
                    val builder = SceneViewBuilder(sceneView)
                    builder.configure()
                    builder.apply()

                    onReady(sceneView)
                } catch (e: Throwable) {
                    console.error("SceneView: Failed to initialize Filament engine", e)
                }
            }
        }
    }

    /** Resize the viewport to match the canvas dimensions. Call on window resize. */
    fun resize(width: Int = canvas.clientWidth, height: Int = canvas.clientHeight) {
        if (width <= 0 || height <= 0) return
        canvas.width = width
        canvas.height = height
        view.setViewport(viewport(0, 0, width, height))
        camera.setProjectionFov(
            fovInDegrees = 45.0,
            aspect = width.toDouble() / height.toDouble(),
            near = 0.1,
            far = 1000.0
        )
    }

    /** Enable automatic viewport resizing when the canvas CSS size changes. */
    var autoResize = true

    /** Start the render loop using requestAnimationFrame. */
    fun startRendering() {
        if (isRunning) return
        isRunning = true
        lastTimestamp = 0.0
        renderLoop(0.0)
    }

    /** Stop the render loop. */
    fun stopRendering() {
        isRunning = false
        animationFrameId?.let { window.cancelAnimationFrame(it) }
        animationFrameId = null
    }

    /**
     * Load a glTF/GLB model from a URL and add it to the scene.
     *
     * This performs the full loading pipeline:
     * 1. Fetch the .glb/.gltf file as an ArrayBuffer
     * 2. Create a FilamentAsset via the AssetLoader
     * 3. Add all renderable entities to the scene
     * 4. Call loadResources() to fetch external textures/buffers
     * 5. Release source data to free memory
     *
     * @param url URL to the .glb or .gltf file
     * @param onLoaded Optional callback when the model is fully loaded (with resources)
     */
    fun loadModel(
        url: String,
        onLoaded: ((FilamentAsset) -> Unit)? = null
    ) {
        val loader = assetLoader ?: engine.createAssetLoader().also { assetLoader = it }

        // Derive the base path for resolving relative resource URIs
        val basePath = url.substringBeforeLast('/') + "/"

        window.fetch(url).then { response ->
            response.arrayBuffer()
        }.then { buffer ->
            val asset = loader.createAsset(buffer)
            if (asset != null) {
                // #1597: a 2nd loadModel of the same URL must release the prior
                // asset for this logical model before adopting the replacement,
                // otherwise the previous FilamentAsset leaks on the GPU. The
                // tracker's destroyer also removes the old entities from the
                // scene. Drop the stale LoadedModel from `models` here too so
                // the render loop / auto-center pass never touch a freed asset.
                //
                // #1597 (Tier-2): the prior model's `loadResources` may still
                // be in flight — flag it `superseded` so its pending `onDone`
                // becomes a no-op instead of touching the asset we destroy
                // below via `replaceWith` (use-after-free on the WASM heap).
                loadedAssets.current(url)?.let { prior ->
                    models.removeAll { stale ->
                        (stale.asset == prior).also { if (it) stale.superseded = true }
                    }
                }

                // Add all entities to the scene so they become visible
                val entities = asset.getEntities()
                scene.addEntities(entities)

                // Get the animator from the asset instance for animation playback
                val animator = try {
                    asset.getInstance().getAnimator()
                } catch (e: Throwable) {
                    null
                }

                val loadedModel = LoadedModel(asset, animator)
                models.add(loadedModel)
                loadedAssets.replaceWith(url, asset)

                // A new model changes the content bounds, so the auto-center pass
                // must re-frame. The #1391-style diagonal-stability gate already
                // re-frames on every union growth (so a deferred async model is
                // covered even without this call — that is the #1540 fix), but an
                // explicit reset still handles content *replacement*: a shrinking
                // union would otherwise look "stable" and never re-frame.
                autoCenterGate.reset()

                // Load external resources (textures, buffers) referenced by the glTF.
                // This is REQUIRED for models to render with correct materials.
                asset.loadResources(
                    onDone = {
                        // #1597 (Tier-2): if a 2nd loadModel of this URL — or
                        // destroy() — replaced/freed `asset` before its
                        // resources finished, this callback is stale. Bail out
                        // before touching the freed FilamentAsset: no
                        // releaseSourceData() on a dead handle, no `loaded`
                        // flip, no gate reset, no onLoaded for a model that is
                        // no longer in the scene.
                        if (loadedModel.superseded) {
                            console.log(
                                "SceneView: dropped stale loadResources for $url " +
                                    "(asset superseded before resources finished)",
                            )
                            return@loadResources
                        }
                        // Release the source glTF data now that resources are loaded
                        asset.releaseSourceData()
                        // #1597: only now is getBoundingBox() readable — mark the
                        // model loaded so the auto-center pass starts including it.
                        loadedModel.loaded = true
                        // The model just became framable — re-arm the gate so the
                        // auto-center pass re-frames on this freshly readable box.
                        autoCenterGate.reset()
                        console.log("SceneView: Model loaded from $url (${entities.size} entities)")
                        onLoaded?.invoke(asset)
                    },
                    onFetched = null,
                    basePath = basePath,
                    asyncInterval = null
                )
            } else {
                console.error("SceneView: AssetLoader failed to parse model from $url")
            }
        }.catch { error ->
            console.error("SceneView: Error fetching model from $url", error)
        }
    }

    /**
     * Load the default neutral IBL environment.
     * Provides physically-correct PBR reflections without a visible skybox —
     * models look like they're in a photography studio.
     */
    fun loadDefaultEnvironment() {
        loadEnvironment(DEFAULT_IBL_URL)
    }

    /** Load an IBL (Image-Based Lighting) from a KTX file URL. */
    fun loadEnvironment(iblUrl: String, skyboxUrl: String? = null) {
        // Fetch and create IBL (indirect lighting) from a KTX1 file
        window.fetch(iblUrl).then { it.arrayBuffer() }.then { buffer ->
            val ibl = engine.createIblFromKtx1(buffer)
            // Destroy the previous IBL (if any) before swapping it out, so a
            // 2nd loadEnvironment / loadDefaultEnvironment call does not leak
            // the prior GPU resource (issue #1496).
            indirectLight.replaceWith(ibl)
            scene.setIndirectLight(ibl)
            console.log("SceneView: IBL loaded from $iblUrl")
        }.catch { error ->
            console.error("SceneView: Error loading IBL from $iblUrl", error)
        }

        // Optionally load a skybox from a separate KTX file
        skyboxUrl?.let { url ->
            window.fetch(url).then { it.arrayBuffer() }.then { buffer ->
                val sky = engine.createSkyFromKtx1(buffer)
                // Same leak-free swap as the IBL above (issue #1496).
                skybox.replaceWith(sky)
                scene.setSkybox(sky)
                console.log("SceneView: Skybox loaded from $url")
            }.catch { error ->
                console.error("SceneView: Error loading skybox from $url", error)
            }
        }
    }

    /**
     * Add a light to the scene using the Filament LightManager Builder API.
     *
     * The Filament.js LightManager.Builder is accessed via:
     *   Filament.LightManager.Builder(type).intensity(n).direction([x,y,z]).build(engine, entity)
     */
    fun addLight(config: LightConfig) {
        val entity = (js("Filament.EntityManager.get().create()") as Entity)

        // Map our LightType enum to Filament's numeric type constants
        // In Filament.js: 0 = SUN, 1 = DIRECTIONAL, 2 = POINT, 3 = FOCUSED_SPOT, 4 = SPOT
        val lightType = when (config.type) {
            LightType.DIRECTIONAL -> 1
            LightType.POINT -> 2
            LightType.SPOT -> 4
        }

        // Use the Builder pattern: LightManager.Builder(type).intensity(...).build(engine, entity)
        val builder = LightManager.Builder(lightType)
        builder.intensity(config.intensity)
        builder.color(float3(
            config.colorR.toDouble(),
            config.colorG.toDouble(),
            config.colorB.toDouble()
        ))
        builder.castShadows(true)

        if (config.type == LightType.DIRECTIONAL) {
            builder.direction(float3(
                config.directionX.toDouble(),
                config.directionY.toDouble(),
                config.directionZ.toDouble()
            ))
        } else {
            builder.position(float3(
                config.positionX.toDouble(),
                config.positionY.toDouble(),
                config.positionZ.toDouble()
            ))
            builder.falloff(10.0)
        }

        builder.build(engine, entity)
        scene.addEntity(entity)
        lightEntities.add(entity)
    }

    /**
     * Add a procedural geometry primitive to the scene.
     *
     * Generates an in-memory GLB from the KMP core geometry generators
     * and loads it through the gltfio pipeline, giving geometry nodes
     * the same PBR material system as loaded glTF models.
     *
     * @param config Geometry configuration (type, size, color, position, scale)
     */
    fun addGeometry(config: GeometryConfig) {
        val glbBuffer = GeometryGLBBuilder.buildGLB(config)
        val loader = assetLoader ?: engine.createAssetLoader().also { assetLoader = it }

        try {
            val asset = loader.createAsset(glbBuffer)
            if (asset != null) {
                val entities = asset.getEntities()
                scene.addEntities(entities)

                val animator = try {
                    asset.getInstance().getAnimator()
                } catch (e: Throwable) {
                    null
                }

                val loadedModel = LoadedModel(asset, animator)
                models.add(loadedModel)
                // Track for leak-free teardown (#1597). Geometry has no logical
                // URL identity, so synthesise a unique key — each primitive is
                // a distinct asset, never a replacement.
                loadedAssets.replaceWith("geometry#${assetKeySeq++}", asset)

                // Re-arm the auto-center pass — a new primitive grows the union
                // bounds. The diagonal-stability gate also re-frames on its own
                // (#1540), but resetting keeps geometry added after a latched
                // pass consistent with `loadModel`.
                autoCenterGate.reset()

                // Finalize the asset — loadResources uploads vertex/index buffers to GPU.
                // Even for self-contained GLBs (no external resources), this step is required.
                asset.loadResources(
                    onDone = {
                        // #1597 (Tier-2): destroy() can free this geometry
                        // asset before its buffers finish uploading — guard
                        // the stale callback so it never touches a dead
                        // FilamentAsset. (Geometry has a unique key so it is
                        // never replaced, but teardown still races it.)
                        if (loadedModel.superseded) return@loadResources
                        asset.releaseSourceData()
                        // #1597: getBoundingBox() is only readable post-load —
                        // mark loaded so the auto-center pass includes it.
                        loadedModel.loaded = true
                        autoCenterGate.reset()
                    },
                    onFetched = null,
                    basePath = "",
                    asyncInterval = null
                )
                console.log("SceneView: Geometry '${config.geometryType.name.lowercase()}' added")
            } else {
                console.error("SceneView: Failed to create geometry asset for ${config.geometryType}")
            }
        } catch (e: Throwable) {
            console.error("SceneView: Error creating geometry ${config.geometryType}", e)
        }
    }

    /**
     * Auto-fit the camera to frame all loaded models.
     * Computes the bounding box of all assets and adjusts the orbit controller distance.
     */
    fun fitToModels() {
        if (models.isEmpty()) return
        fitToBounds(ContentCentering.union(contentBoxes()))
    }

    /**
     * Read every *fully loaded* asset's asset-space AABB as
     * [ContentCentering.Aabb]s.
     *
     * `getBoundingBox()` reports a degenerate/wrong box until `loadResources()`
     * has populated the renderables, so a model whose [LoadedModel.loaded] flag
     * is still `false` is excluded entirely — this is the #1597 fix: the
     * auto-center pass must never frame the scene on a not-yet-loaded model's
     * unreadable diagonal. The defensive `try/catch` stays as a second guard.
     * Shared by [fitToModels] and [refreshContentCentering] so the union-AABB
     * read happens exactly once per call site instead of being duplicated.
     */
    private fun contentBoxes(): List<ContentCentering.Aabb> = models.mapNotNull { model ->
        // #1597: skip models whose resources are still in flight — their box is
        // not yet readable, so including them would frame on a wrong diagonal.
        if (!model.loaded) return@mapNotNull null
        try {
            val aabb = model.asset.getBoundingBox()
            val mn: dynamic = aabb.min
            val mx: dynamic = aabb.max
            ContentCentering.Aabb(
                doubleArrayOf(
                    (mn[0] as Number).toDouble(),
                    (mn[1] as Number).toDouble(),
                    (mn[2] as Number).toDouble(),
                ),
                doubleArrayOf(
                    (mx[0] as Number).toDouble(),
                    (mx[1] as Number).toDouble(),
                    (mx[2] as Number).toDouble(),
                ),
            )
        } catch (e: Throwable) {
            // The asset's bounds are not readable yet (resources still loading) — skip
            // this model for now. Surface it once so a genuine failure is not invisible;
            // the pass re-runs on later frames until the framing has settled.
            console.warn("SceneView: skipping a model in auto-center (bounds not ready)", e)
            null
        }
    }

    /**
     * Dolly the orbit controller so [bounds] (the union AABB of all content)
     * fits the frustum. A no-op when [bounds] is `null` (nothing loaded) or
     * camera controls are disabled. Extracted so [fitToModels] and the
     * auto-centre path ([refreshContentCentering]) share one implementation
     * and one union-AABB read.
     */
    private fun fitToBounds(bounds: ContentCentering.Aabb?) {
        if (bounds == null) return
        val controller = cameraController ?: return
        val center = ContentCentering.center(bounds)
        val radius = ContentCentering.diagonal(bounds) / 2.0
        if (radius <= 0.0) return

        controller.target(center[0], center[1], center[2])
        controller.distance = radius * 2.5
        controller.minDistance = radius * 0.5
        controller.maxDistance = radius * 10.0
    }

    /**
     * Translate every loaded asset's root entity so the union bounding box of
     * all content lands centred on the world origin (the orbit-camera target),
     * then dolly the orbit camera so that union fits the frustum.
     *
     * Runs every render frame until the content's union diagonal has settled
     * across consecutive frames — see [AutoCenterGate]. A no-op once the gate
     * has latched, and a no-op entirely when [autoCenterContent] is `false`.
     *
     * ## #1540: deferred async models re-frame
     *
     * The previous design latched on the **first** non-degenerate frame, so an
     * async model that finished *after* a sibling had already centred never
     * re-centred — the multi-model "bunched-in-the-corner" bug #1391 fixed on
     * iOS. This port mirrors that fix: every frame the union diagonal is
     * measured and the pass re-frames whenever it grew (a streamed model just
     * landed), latching only once the diagonal is stable. So a 2nd model
     * loaded async always pulls the framing back to the combined extent.
     *
     * ## #1540: auto-dolly, not just auto-centre
     *
     * The pass now also calls [fitToBounds] with the same union AABB so the
     * web viewer auto-DOLLIES the orbit camera to fit content size — small and
     * large models were previously mis-framed because only auto-centring ran.
     *
     * Library-level port of the iOS `refreshContentCentering` (#1026 / #1391):
     * on iOS an intermediate `contentRoot` Entity is translated; the web
     * Filament `Scene` has no parent root, so each asset's own root entity is
     * offset instead — the visual result is identical. Closes #1052, #1540.
     */
    private fun refreshContentCentering() {
        if (!autoCenterGate.shouldRun(autoCenterContent, models.isNotEmpty())) return

        // Single union-AABB read, shared with the dolly fit below (#1540 de-dup).
        val union = ContentCentering.union(contentBoxes())
        val offset = ContentCentering.centeringOffset(union) ?: return

        // Skip frames where the union diagonal has not moved since the last
        // framed pass — the scene is already settled and the gate will latch.
        // A freshly streamed model grows the diagonal and forces a re-frame.
        val diagonal = ContentCentering.diagonal(union)
        if (!autoCenterGate.shouldFrame(diagonal)) {
            autoCenterGate.recordFraming(diagonal)
            return
        }

        // Apply the centring translation to each asset's root entity via the
        // TransformManager. The first time this model is touched its current
        // transform is captured as `baseTransform`; every subsequent re-frame
        // (a deferred async sibling grew the union — #1540) composes the new
        // offset onto that *base* rather than onto the already-offset
        // transform, so the translation never accumulates. Composing onto the
        // base keeps any per-model scale/position the consumer set intact.
        val tm = engine.getTransformManager()
        for (model in models) {
            try {
                val root = model.asset.getRoot()
                if (!tm.hasComponent(root)) tm.create(root)
                val instance = tm.getInstance(root)
                if (model.baseTransform == null) {
                    model.baseTransform = copyMat4(tm.getTransform(instance))
                }
                tm.setTransform(instance, translatedMat4(model.baseTransform, offset))
            } catch (e: Throwable) {
                console.error("SceneView: auto-center failed for a model", e)
            }
        }

        // Auto-dolly: fit the orbit camera to the content size (#1540). The
        // union is already centred on the origin by the offset above, so the
        // fit's own target re-aims at the (now origin) centroid harmlessly.
        fitToBounds(union)

        // Record this framing — latches the gate once the diagonal stabilises.
        autoCenterGate.recordFraming(diagonal)
        console.log(
            "SceneView: auto-centered content (offset ${offset[0]}, ${offset[1]}, " +
                "${offset[2]}, diagonal $diagonal)",
        )
    }

    /**
     * Return a fresh flat 16-element `number[]` copy of the column-major 4x4
     * [mat]. Used to snapshot a model's base transform before the auto-center
     * pass first offsets it (#1540), so later re-frames compose onto an
     * immutable base.
     */
    private fun copyMat4(mat: dynamic): dynamic {
        val out = js("[]")
        for (i in 0 until 16) {
            out.push((mat[i] as Number).toDouble())
        }
        return out
    }

    /**
     * Return a copy of the column-major 4x4 [mat] with [offset] added to its
     * translation column (indices 12, 13, 14). Filament.js represents a mat4
     * as a flat 16-element `number[]`.
     */
    private fun translatedMat4(mat: dynamic, offset: DoubleArray): dynamic {
        val out = copyMat4(mat)
        out[12] = (out[12] as Number).toDouble() + offset[0]
        out[13] = (out[13] as Number).toDouble() + offset[1]
        out[14] = (out[14] as Number).toDouble() + offset[2]
        return out
    }

    /** Clean up all Filament resources. */
    fun destroy() {
        stopRendering()
        cameraController?.dispose()

        // #1597 (Tier-2): mark every model superseded BEFORE releasing its
        // asset so any in-flight loadResources callback that fires after
        // teardown is a no-op instead of a use-after-free on a freed
        // FilamentAsset.
        models.forEach { it.superseded = true }

        // Destroy every loaded gltfio asset (#1597). The tracker is the single
        // owner of all live FilamentAssets — URL models and geometry alike —
        // so this releases each exactly once, including any not yet covered by
        // the per-replace destroy. The `models` list is then purely render
        // state and just needs clearing.
        loadedAssets.release()
        assetLoader?.delete()
        models.clear()

        // Destroy light entities. The LightManager component is a separately
        // managed native allocation — destroying the entity alone leaks it
        // (#1700), mirroring the Android LightNode.destroy() teardown. Destroy
        // the component first, then the entity.
        val lightManager = engine.getLightManager()
        lightEntities.forEach { entity ->
            if (lightManager.hasComponent(entity)) lightManager.destroy(entity)
            engine.destroyEntity(entity)
        }
        lightEntities.clear()

        // Destroy the environment GPU resources — IBL + skybox (issue #1496).
        // Detach from the scene first so the engine never holds a dangling
        // reference, then destroy the handles.
        if (indirectLight.current != null) scene.setIndirectLight(null)
        indirectLight.release()
        if (skybox.current != null) scene.setSkybox(null)
        skybox.release()

        // Destroy core Filament objects
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        engine.destroySwapChain(swapChain)
        val filament: dynamic = js("Filament")
        filament.Engine.destroy(engine)
    }

    /**
     * The render loop -- called every frame via requestAnimationFrame.
     *
     * Each frame:
     * 1. Auto-resizes viewport if CSS size changed
     * 2. Updates orbit camera controller (rotation, damping)
     * 3. Advances glTF animations
     * 4. Calls engine.execute() to process pending async operations
     * 5. Renders the frame via beginFrame/renderView/endFrame
     */
    private fun renderLoop(timestamp: Double) {
        if (!isRunning) return

        // Auto-resize viewport if canvas CSS size changed
        if (autoResize) {
            val w = canvas.clientWidth
            val h = canvas.clientHeight
            if (w > 0 && h > 0 && (w != canvas.width || h != canvas.height)) {
                resize(w, h)
            }
        }

        // Auto-center content on the first frame its bounds are non-degenerate
        // (i.e. async-loaded models have populated). No-op once centered or
        // when autoCenterContent is disabled. Port of iOS #1026 — closes #1052.
        refreshContentCentering()

        // Update orbit camera
        cameraController?.update()

        // Track animation time
        val deltaSeconds = if (lastTimestamp > 0) (timestamp - lastTimestamp) / 1000.0 else 0.0
        lastTimestamp = timestamp

        // Update glTF animations for all loaded models
        models.forEach { model ->
            model.animator?.let { animator ->
                val count = animator.getAnimationCount()
                if (count > 0) {
                    model.animationTime += deltaSeconds
                    val duration = animator.getAnimationDuration(0)
                    if (duration > 0) {
                        // Loop the animation
                        model.animationTime = model.animationTime % duration
                    }
                    // Apply animation 0 at the accumulated (looped) time so
                    // skeletal/keyframe animations actually advance. The time
                    // argument is mandatory — without it the animator re-applies
                    // every frame at t=0 and the model renders frozen (#1697).
                    animator.applyAnimation(0, model.animationTime)
                    animator.updateBoneMatrices()
                }
            }
        }

        // Process any pending async Filament operations (texture uploads, etc.)
        engine.execute()

        // Render frame
        if (renderer.beginFrame(swapChain)) {
            renderer.renderView(view)
            renderer.endFrame()
        }

        animationFrameId = window.requestAnimationFrame(::renderLoop)
    }
}

/**
 * DSL builder for SceneView configuration.
 */
class SceneViewBuilder(private val sceneView: SceneView) {
    private var cameraConfig: CameraConfig? = null
    private var lightConfig: LightConfig? = null
    private val modelConfigs = mutableListOf<ModelConfig>()
    private val geometryConfigs = mutableListOf<GeometryConfig>()
    private var iblUrl: String? = null
    private var skyboxUrl: String? = null
    private var cameraControlsEnabled = true
    private var autoRotateEnabled = false
    private var useDefaultEnvironment = true
    private var autoCenterContentEnabled = true

    /** Configure the camera. */
    fun camera(block: CameraConfig.() -> Unit) {
        cameraConfig = CameraConfig().apply(block)
    }

    /** Configure a directional light. */
    fun light(block: LightConfig.() -> Unit) {
        lightConfig = LightConfig().apply(block)
    }

    /** Add a glTF/GLB model by URL. */
    fun model(url: String, block: ModelConfig.() -> Unit = {}) {
        modelConfigs.add(ModelConfig(url).apply(block))
    }

    /** Add a procedural geometry primitive (cube, sphere, cylinder, plane). */
    fun geometry(block: GeometryConfig.() -> Unit) {
        geometryConfigs.add(GeometryConfig().apply(block))
    }

    /** Set environment lighting from KTX IBL files. */
    fun environment(iblUrl: String, skyboxUrl: String? = null) {
        this.iblUrl = iblUrl
        this.skyboxUrl = skyboxUrl
        this.useDefaultEnvironment = false
    }

    /** Disable the default neutral IBL environment. */
    fun noEnvironment() {
        this.useDefaultEnvironment = false
    }

    /** Enable orbit camera controls (drag to orbit, scroll to zoom, touch). Enabled by default. */
    fun cameraControls(enabled: Boolean = true) {
        cameraControlsEnabled = enabled
    }

    /** Enable auto-rotation of the camera around the target. */
    fun autoRotate(enabled: Boolean = true) {
        autoRotateEnabled = enabled
    }

    /**
     * Auto-centre loaded content on the world origin once it has finished
     * loading. Enabled by default — pass `false` for narrative scenes that
     * rely on intentional off-centre placement.
     *
     * Library-level port of iOS `autoCenterContent` (#1026). Closes #1052.
     */
    fun autoCenterContent(enabled: Boolean = true) {
        autoCenterContentEnabled = enabled
    }

    internal fun apply() {
        sceneView.autoCenterContent = autoCenterContentEnabled
        cameraConfig?.applyTo(sceneView.camera)

        // If no explicit light was configured, add model-viewer-like 3-point lighting
        if (lightConfig != null) {
            sceneView.addLight(lightConfig!!)
        } else {
            // Key light — main directional, slightly warm
            val keyLight = LightConfig().apply {
                directional()
                intensity(50_000.0)
                direction(0.6f, -1.0f, -0.8f)
            }
            sceneView.addLight(keyLight)

            // Fill light — softer, from the opposite side
            val fillLight = LightConfig().apply {
                directional()
                intensity(25_000.0)
                direction(-0.6f, -0.5f, 0.8f)
            }
            sceneView.addLight(fillLight)

            // Rim/back light — highlights edges, cool tint
            val rimLight = LightConfig().apply {
                directional()
                intensity(30_000.0)
                color(0.85f, 0.9f, 1.0f) // slight cool tint
                direction(0.0f, -0.3f, 1.0f)
            }
            sceneView.addLight(rimLight)
        }

        // Load IBL environment for physically-correct PBR reflections
        if (iblUrl != null) {
            sceneView.loadEnvironment(iblUrl!!, skyboxUrl)
        } else if (useDefaultEnvironment) {
            // Load the bundled neutral IBL — same as Android SceneView default
            sceneView.loadDefaultEnvironment()
        }

        modelConfigs.forEach { config ->
            sceneView.loadModel(config.url, config.onLoaded)
        }
        geometryConfigs.forEach { config ->
            sceneView.addGeometry(config)
        }
        if (cameraControlsEnabled) {
            val cam = cameraConfig
            sceneView.enableCameraControls(
                distance = cam?.eyeZ ?: 5.0,
                targetX = cam?.targetX ?: 0.0,
                targetY = cam?.targetY ?: 0.0,
                targetZ = cam?.targetZ ?: 0.0,
                autoRotate = autoRotateEnabled
            )
        }
    }
}
