package io.github.sceneview.web

import io.github.sceneview.scene.SceneGraph
import io.github.sceneview.web.bindings.*
import io.github.sceneview.web.nodes.CameraConfig
import io.github.sceneview.web.nodes.GeometryConfig
import io.github.sceneview.web.nodes.GeometryNode
import io.github.sceneview.web.nodes.LightConfig
import io.github.sceneview.web.nodes.LightType
import io.github.sceneview.web.nodes.ModelConfig
import io.github.sceneview.web.nodes.ModelNode
import io.github.sceneview.web.nodes.Node
import kotlinx.browser.window
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Uint8Array
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

    /**
     * On-demand render gate (#2332). The render loop runs every animation frame
     * for input/animation/upload bookkeeping, but only submits a GPU frame when
     * something actually changed — see [RenderGate]. An idle static scene then
     * costs ~one near-empty rAF callback instead of a full SSAO+bloom+TAA
     * pipeline pass every frame. Starts dirty so the first frame always paints.
     */
    private val renderGate = RenderGate()

    /**
     * Count of asynchronous resource loads currently in flight — a model fetch
     * + `loadResources`, a geometry build, or an environment KTX fetch. While
     * `> 0` the gate treats the scene as active so streamed geometry/textures
     * paint smoothly as they upload, then settle once every load terminates.
     * Each load increments exactly once on entry and decrements exactly once on
     * its first terminal outcome (success, supersession, or error).
     */
    private var pendingLoads = 0

    /**
     * #1597 (Tier-2): set as the FIRST statement of [destroy] so any in-flight
     * async callback that lands after teardown — e.g. a [loadEnvironment] KTX
     * fetch resolving after the WASM Engine is freed — can bail out instead of
     * calling into a destroyed engine/scene (use-after-free). Callbacks that
     * bail MUST still run their settle helper so [pendingLoads] never leaks.
     */
    private var destroyed = false

    /**
     * Stored bound reference to [renderLoop] so the per-frame
     * `requestAnimationFrame` reschedule reuses one function object instead of
     * allocating a fresh member-reference wrapper every tick (#2332).
     */
    private val renderLoopRef: (Double) -> Unit = ::renderLoop

    /**
     * Mark the scene dirty so the render loop submits at least one more GPU
     * frame (plus a short settle tail). Call from any mutation that changes what
     * the next frame should look like but does not itself move the camera or run
     * an animation — a new light, a background-color change, a resize, or a
     * freshly loaded/streamed asset. Cheap and idempotent, so callers never
     * debounce. Delegates to [RenderGate.requestRender].
     */
    internal fun requestRender() {
        renderGate.requestRender()
    }

    /** Monotonic counter for synthesising unique tracker keys for un-keyed
     *  (procedural geometry) assets — see [loadedAssets]. */
    private var assetKeySeq = 0

    private val models = mutableListOf<LoadedModel>()
    private var assetLoader: AssetLoader? = null
    private val lightEntities = mutableListOf<Entity>()

    /**
     * Retained-mode node tree (#2024, slice 1). Holds the [Node]s added via
     * [addNode] — the `sceneview-core` [SceneGraph] tracks membership
     * (roots, recursive removal, per-frame dispatch) while transform
     * inheritance itself is composed by Filament's `TransformManager`
     * parent tree, mirroring Android.
     *
     * Slice 1 ships the pure transform graph (empty pivot nodes); the
     * concrete `ModelNode`/`GeometryNode`/`LightNode` subtypes and the
     * `@JsExport` handles arrive in later slices — see
     * `.claude/plans/v5-web-node-graph.md`.
     */
    val sceneGraph = SceneGraph()

    /**
     * The single content-root pivot [Node] the auto-centre pass translates
     * (#2024 slice 3 / P4). Replaces the pre-slice-3 whole-scene design where
     * `refreshContentCentering` offset every flat asset's *own* root entity
     * transform individually: now every flat asset's root is re-parented under
     * this one node, and centring the union is a single node translation —
     * exactly the iOS `contentRoot` Entity approach (the doc at the old
     * `refreshContentCentering` said this was the ideal but impossible without a
     * root node; slice 1's `Node` makes it possible). Lazily created on the
     * first centring pass so a scene with `autoCenterContent = false` never
     * allocates it. Node-owned content (`addModelNode`/…) is framed through its
     * own pivot and is excluded from this pass.
     */
    private var contentRoot: Node? = null

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

    /**
     * The engine's [TransformManager], resolved once at construction instead of
     * per model per frame. `engine.getTransformManager()` crosses the WASM↔JS
     * boundary; the auto-center pass ([refreshContentCentering]) ran it inside
     * its per-model loop, paying that marshalling cost N times every non-latched
     * frame for no reason — the handle never changes (#2268).
     */
    private val transformManager: TransformManager = engine.getTransformManager()

    /** Tracks a loaded glTF asset with its animation state. */
    private class LoadedModel(
        val asset: FilamentAsset,
        val animator: Animator?,
        var animationTime: Double = 0.0,
        /**
         * When `true` (the Android `ModelNode` default) the render loop plays
         * the model's animation 0 every frame; when `false` the model renders
         * static — gates the `animator.applyAnimation(0, …)` call in
         * [renderLoop] so `model { autoAnimate(false) }` actually stops playback
         * (#2432).
         */
        val autoAnimate: Boolean = true,
        /**
         * Uniform local scale applied to the asset's root entity at load time
         * (mirrors Android `ModelNode(scale = Scale(value))` — *raw* uniform
         * scale, not `scaleToUnits` normalisation). Baked into the root
         * transform once entities are in the scene, and used to scale this
         * model's asset-space `getBoundingBox()` so the auto-centre / auto-dolly
         * pass frames the *rendered* (scaled) extent (#2432).
         */
        val scale: Float = 1f,
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

        /**
         * `true` once this asset's root entity has been re-parented under the
         * shared [contentRoot] pivot (#2024 slice 3 / P4 root-node centering) —
         * done once per model, the first frame the centering pass touches it.
         * After that the model no longer carries a per-asset `baseTransform`
         * offset: the single `contentRoot` node translation centres the whole
         * union, exactly like the iOS `contentRoot` Entity. Nodes created
         * through the node factories (`addModelNode`/…) already own their asset
         * root under a pivot, so those are excluded from the flat-content
         * centering pass via [LoadedModel.nodeOwned].
         */
        var adoptedByContentRoot: Boolean = false

        /**
         * `true` when this model's asset root is owned by a scene-graph node
         * pivot (`addModelNode`/`addGeometryNode`/typed factories) rather than
         * being flat world-space content. The flat-content centering pass
         * ([refreshContentCentering]) skips node-owned assets — their framing is
         * the caller's responsibility through the node transform. Flat
         * `loadModel` / `addGeometry` content stays `false` and is centred.
         */
        var nodeOwned: Boolean = false
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
         * @param onError Callback invoked if initialization fails. Init runs
         *   asynchronously inside the Filament `init` callback, so a thrown
         *   error cannot propagate to the caller's stack — without this hook a
         *   failure is only `console.error`-ed and any Promise wrapping
         *   [create] (e.g. `createViewer`) hangs forever. Wire it to your
         *   reject path so callers see a failed Promise instead of a silent
         *   hang.
         * @param onReady Callback when the SceneView is fully initialized
         */
        fun create(
            canvas: HTMLCanvasElement,
            assets: Array<String> = emptyArray(),
            configure: SceneViewBuilder.() -> Unit = {},
            onError: ((Throwable) -> Unit)? = null,
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
                    // `unsafeCast`, NOT `as Entity`: a Kotlin `as` against the
                    // `external class Entity` compiles to `tmp instanceof Entity`,
                    // but `Entity` is not a runtime constructor in Filament.js
                    // (entities are integers) — the right-hand side is `undefined`,
                    // so the cast throws `TypeError: Right-hand side of 'instanceof'
                    // is not an object` and the whole init hangs. Same reason the
                    // `Engine.create()` above goes through `js("Filament")` +
                    // `unsafeCast`.
                    val cameraEntity = js("Filament.EntityManager.get().create()").unsafeCast<Entity>()
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
                        far = 1000.0,
                        // Required — embind enforces strict arity 5. See fovVertical().
                        fov = fovVertical()
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
                    // Keep the log for the browser console, but ALSO signal the
                    // failure to [onError] — this callback fires inside the async
                    // `init` continuation, so a bare throw is swallowed here and a
                    // Promise wrapping create() would otherwise never settle.
                    console.error("SceneView: Failed to initialize Filament engine", e)
                    onError?.invoke(e)
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
            far = 1000.0,
            // Required — embind enforces strict arity 5. See fovVertical().
            fov = fovVertical()
        )
        // A new viewport / projection changes every pixel — repaint (#2332).
        requestRender()
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
     * @param autoAnimate When `true` (default, matching Android `ModelNode`) the
     *   render loop plays the model's animation 0; `false` renders it static (#2432).
     * @param scale Uniform local scale applied to the model's root entity —
     *   *raw* uniform scale like Android `ModelNode(scale = Scale(value))`, not
     *   `scaleToUnits` normalisation. The default `1f` leaves the model at its
     *   authored size (#2432).
     */
    fun loadModel(
        url: String,
        onLoaded: ((FilamentAsset) -> Unit)? = null,
        autoAnimate: Boolean = true,
        scale: Float = 1f,
    ) = loadModelInternal(url, onLoaded, autoAnimate, scale, onAssetCreated = null)

    /** The asset's animation player, `null` when it has no animations. */
    private fun assetAnimatorOrNull(asset: FilamentAsset): Animator? =
        @Suppress("SwallowedException")
        try {
            asset.getInstance().getAnimator()
        } catch (e: Throwable) {
            null
        }

    /**
     * The actual pipeline behind [loadModel]. [onAssetCreated] is the node
     * adoption hook (#2024 slice 2): it fires synchronously right after the
     * asset's entities enter the scene (and the root scale is baked) but
     * BEFORE the first frame can render them — so `addModelNode` re-parents
     * the asset root under its pivot with no one-frame visual jump — and
     * before the async `loadResources` completes ([onLoaded] keeps firing at
     * resources-done, as always).
     */
    internal fun loadModelInternal(
        url: String,
        onLoaded: ((FilamentAsset) -> Unit)? = null,
        autoAnimate: Boolean = true,
        scale: Float = 1f,
        nodeOwned: Boolean = false,
        onAssetCreated: ((FilamentAsset) -> Unit)? = null,
    ) {
        val loader = assetLoader ?: engine.createAssetLoader().also { assetLoader = it }

        // Derive the base path for resolving relative resource URIs
        val basePath = url.substringBeforeLast('/') + "/"

        // #2332: keep the render gate "active" for the whole async load so the
        // model streams in smoothly, then settles. Decrement exactly once on the
        // first terminal outcome (loaded, superseded, parse failure, or fetch
        // error) so the count can never under- or over-shoot.
        pendingLoads++
        var loadSettled = false
        fun settleLoad() {
            if (!loadSettled) {
                loadSettled = true
                pendingLoads--
                requestRender()
            }
        }

        window.fetch(url).then { response ->
            response.arrayBuffer()
        }.then { buffer ->
            // #1597 (Tier-2): if destroy() ran while this initial GLB fetch was
            // in flight, the loader/engine/scene are freed WASM handles — bail
            // out before createAsset/addEntities touch them (use-after-free),
            // but still settle so pendingLoads never leaks. The `superseded`
            // guard below only covers the late loadResources/onDone step, NOT
            // this initial continuation. Mirrors loadEnvironment's KTX guard
            // (#2687) — same `destroyed` flag, checked before the first engine
            // call.
            if (destroyed) {
                console.log(
                    "SceneView: dropped stale model load for $url " +
                        "(SceneView destroyed before fetch resolved)",
                )
                settleLoad()
                return@then
            }
            // Filament.js gltfio `createAsset` expects a typed-array VIEW
            // (Uint8Array), NOT a raw ArrayBuffer — passing the ArrayBuffer
            // throws an embind BindingError. Mirrors the conversion the
            // hand-authored sceneview.js does after `response.arrayBuffer()`.
            val asset = loader.createAsset(Uint8Array(buffer.unsafeCast<ArrayBuffer>()))
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

                // #2432: bake the consumer's uniform `scale` into the root
                // entity transform now that the entities are in the scene. Done
                // before any render/auto-centre frame can run on this model so
                // the asset's local transform is already scaled when the pass
                // re-parents it under the content-root pivot (#2024 P4) and never
                // has to special-case scale. A no-op for the default `scale == 1f`.
                applyRootScale(asset, scale)

                // #2024 slice 2: let a ModelNode pivot adopt the asset root
                // before the first frame can paint the untransformed asset.
                onAssetCreated?.invoke(asset)

                // The scene graph just changed — paint it (#2332).
                requestRender()

                val loadedModel = LoadedModel(
                    asset, assetAnimatorOrNull(asset), autoAnimate = autoAnimate, scale = scale,
                ).apply { this.nodeOwned = nodeOwned }
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
                            settleLoad()
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
                        // Resources are uploaded — request a repaint and let the
                        // gate's settle tail flush the final texture uploads (#2332).
                        settleLoad()
                    },
                    onFetched = null,
                    basePath = basePath,
                    asyncInterval = null
                )
            } else {
                console.error("SceneView: AssetLoader failed to parse model from $url")
                settleLoad()
            }
        }.catch { error ->
            console.error("SceneView: Error fetching model from $url", error)
            settleLoad()
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
        // #2332: each KTX fetch is an in-flight load — keep the gate active until
        // it lands, decrementing exactly once on success or error.
        pendingLoads++
        var iblSettled = false
        fun settleIbl() {
            if (!iblSettled) { iblSettled = true; pendingLoads--; requestRender() }
        }

        // Fetch and create IBL (indirect lighting) from a KTX1 file
        window.fetch(iblUrl).then { it.arrayBuffer() }.then { buffer ->
            // #1597 (Tier-2): if destroy() ran while the fetch was in flight,
            // the engine/scene are freed WASM handles — bail out before
            // touching them, but still settle so pendingLoads never leaks.
            // Same pattern as loadModel's `superseded` guard.
            if (destroyed) {
                console.log(
                    "SceneView: dropped stale IBL load for $iblUrl " +
                        "(SceneView destroyed before fetch resolved)",
                )
                settleIbl()
                return@then
            }
            // Uint8Array view, not the raw ArrayBuffer — see loadModel (embind
            // BindingError otherwise).
            val ibl = engine.createIblFromKtx1(Uint8Array(buffer.unsafeCast<ArrayBuffer>()))
            // Destroy the previous IBL (if any) before swapping it out, so a
            // 2nd loadEnvironment / loadDefaultEnvironment call does not leak
            // the prior GPU resource (issue #1496).
            indirectLight.replaceWith(ibl)
            scene.setIndirectLight(ibl)
            console.log("SceneView: IBL loaded from $iblUrl")
            settleIbl()
        }.catch { error ->
            console.error("SceneView: Error loading IBL from $iblUrl", error)
            settleIbl()
        }

        // Optionally load a skybox from a separate KTX file
        skyboxUrl?.let { url ->
            pendingLoads++
            var skySettled = false
            fun settleSky() {
                if (!skySettled) { skySettled = true; pendingLoads--; requestRender() }
            }
            window.fetch(url).then { it.arrayBuffer() }.then { buffer ->
                // #1597 (Tier-2): same destroy() guard as the IBL above — never
                // touch a freed engine/scene, but always settle the counter.
                if (destroyed) {
                    console.log(
                        "SceneView: dropped stale skybox load for $url " +
                            "(SceneView destroyed before fetch resolved)",
                    )
                    settleSky()
                    return@then
                }
                // Uint8Array view, not the raw ArrayBuffer — see loadModel.
                val sky = engine.createSkyFromKtx1(Uint8Array(buffer.unsafeCast<ArrayBuffer>()))
                // Same leak-free swap as the IBL above (issue #1496).
                skybox.replaceWith(sky)
                scene.setSkybox(sky)
                console.log("SceneView: Skybox loaded from $url")
                settleSky()
            }.catch { error ->
                console.error("SceneView: Error loading skybox from $url", error)
                settleSky()
            }
        }
    }

    /**
     * Add a light to the scene using the Filament LightManager Builder API.
     *
     * The Filament.js LightManager.Builder is accessed via:
     *   Filament.LightManager.Builder(type).intensity(n).direction([x,y,z]).build(engine, entity)
     */
    /**
     * Adds a [node] to the retained node tree (#2024).
     *
     * @param node The node to add.
     * @param parent Optional parent — when non-null, [node] is attached as a
     *   child (Filament composes `world = parentWorld * local` for it); when
     *   null it becomes a root node. Mirrors Android's `SceneScope` attach.
     */
    fun addNode(node: Node, parent: Node? = null) {
        sceneGraph.addNode(node, parent)
        requestRender()
    }

    /**
     * Removes a [node] (and its whole subtree) from the node tree.
     *
     * Removal only detaches — it does **not** free the nodes' Filament
     * entities, so a removed node can be re-added. Call [Node.destroy] to
     * release a node's engine resources for good (the Android
     * `removeNode` vs `destroy` split).
     */
    fun removeNode(node: Node) {
        sceneGraph.removeNode(node)
        requestRender()
    }

    fun addLight(config: LightConfig) {
        buildLightEntity(config)
    }

    /**
     * Creates a fresh Filament entity through the runtime `Filament` global.
     *
     * `unsafeCast`, NOT the `external EntityManager.get()` companion: the
     * `external class EntityManager` captures `$module$filament.EntityManager`
     * at MODULE-LOAD — before `Filament.init()` attaches the embind classes —
     * so `EntityManager.get()` is `undefined` during `create()`
     * (→ "Cannot read properties of undefined (reading 'get')"). The `js(...)`
     * global resolves lazily, the same fix `create()`'s camera-entity and
     * [buildLightEntity]'s light-entity use. The retained node factories
     * ([io.github.sceneview.web.addLightNode] / `addCameraNode`) go through
     * this so a node built during init never trips the module-load hole
     * (#2024 slice 2b).
     */
    internal fun newEntity(): Entity =
        js("Filament.EntityManager.get().create()").unsafeCast<Entity>()

    /**
     * Builds a Filament light from [config], adds its entity to the scene, and
     * tracks it in [lightEntities] for leak-free teardown (#1700) — the shared
     * body of [addLight] and the retained `addLightNode` factory (#2024 slice
     * 2b). Returns the built light entity so `addLightNode` can re-parent it
     * under a [io.github.sceneview.web.nodes.LightNode] pivot and mutate it
     * through the `LightManager` instance bindings afterwards.
     */
    internal fun buildLightEntity(config: LightConfig): Entity {
        // `unsafeCast`, NOT `as Entity` — see the create() camera-entity note:
        // `as` against the external `Entity` class emits `instanceof Entity`,
        // and `Filament.Entity` is `undefined` at runtime, which throws. This
        // path runs inside create() via the default 3-point lighting, so the
        // same crash fires here too.
        val entity = js("Filament.EntityManager.get().create()").unsafeCast<Entity>()

        // Map our LightType to Filament's `LightManager$Type` embind ENUM
        // values — NOT raw ints. `LightManager.Builder(type)` is an embind
        // method whose `type` arg is the enum object; passing a bare integer
        // throws a BindingError. (`\$` escapes Kotlin string interpolation.)
        val lightType: dynamic = when (config.type) {
            LightType.DIRECTIONAL -> js("Filament.LightManager\$Type.DIRECTIONAL")
            LightType.POINT -> js("Filament.LightManager\$Type.POINT")
            LightType.SPOT -> js("Filament.LightManager\$Type.SPOT")
        }

        // Resolve LightManager off the runtime `Filament` global. The
        // `external class LightManager` binding captures
        // `$module$filament.LightManager` at MODULE-LOAD — before
        // `Filament.init()` attaches the embind classes — so it is `undefined`
        // (→ "Cannot read properties of undefined (reading 'Builder')"). Same
        // lazy `js("Filament")` resolution `Engine.create()` above uses.
        val filament: dynamic = js("Filament")
        val builder = filament.LightManager.Builder(lightType).unsafeCast<LightManagerBuilder>()
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
        // A new light re-lights every pixel — repaint (#2332).
        requestRender()
        return entity
    }

    /**
     * Add a procedural geometry primitive to the scene.
     *
     * Generates an in-memory GLB from the KMP core geometry generators
     * and loads it through the gltfio pipeline, giving geometry nodes
     * the same PBR material system as loaded glTF models.
     *
     * @param config Geometry configuration (type, size, color, position, scale)
     * @param nodeOwned `true` when the primitive is created through a node
     *   factory (`addGeometryNode`/typed factories, #2024) — its [LoadedModel]
     *   is then marked node-owned and excluded from the flat-content
     *   auto-centre pass, since it is framed via its own pivot (P4). Flat
     *   `addGeometry` callers leave it `false` (the default).
     * @return The created gltfio asset (its buffers still uploading async), or
     *   `null` if the geometry build failed. Callers that don't need the
     *   handle can keep ignoring it — the return is additive (#2024 slice 2).
     */
    fun addGeometry(config: GeometryConfig, nodeOwned: Boolean = false): FilamentAsset? {
        val glbBuffer = GeometryGLBBuilder.buildGLB(config)
        val loader = assetLoader ?: engine.createAssetLoader().also { assetLoader = it }

        // #2332: a geometry build + GPU upload is an in-flight load — keep the
        // gate active until its buffers land, decrementing exactly once.
        pendingLoads++
        var geomSettled = false
        fun settleGeometry() {
            if (!geomSettled) { geomSettled = true; pendingLoads--; requestRender() }
        }

        try {
            // Uint8Array view, not the raw ArrayBuffer `buildGLB` returns —
            // gltfio's createAsset throws an embind BindingError on a bare
            // ArrayBuffer (see loadModel).
            val asset = loader.createAsset(Uint8Array(glbBuffer))
            if (asset != null) {
                val entities = asset.getEntities()
                scene.addEntities(entities)

                val loadedModel = LoadedModel(asset, assetAnimatorOrNull(asset))
                    .apply { this.nodeOwned = nodeOwned }
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
                        if (loadedModel.superseded) {
                            settleGeometry()
                            return@loadResources
                        }
                        asset.releaseSourceData()
                        // #1597: getBoundingBox() is only readable post-load —
                        // mark loaded so the auto-center pass includes it.
                        loadedModel.loaded = true
                        autoCenterGate.reset()
                        // Buffers uploaded — repaint + flush via the settle tail (#2332).
                        settleGeometry()
                    },
                    onFetched = null,
                    basePath = "",
                    asyncInterval = null
                )
                // The scene graph just changed — paint it (#2332).
                requestRender()
                console.log("SceneView: Geometry '${config.geometryType.name.lowercase()}' added")
                return asset
            } else {
                console.error("SceneView: Failed to create geometry asset for ${config.geometryType}")
                settleGeometry()
                return null
            }
        } catch (e: Throwable) {
            console.error("SceneView: Error creating geometry ${config.geometryType}", e)
            settleGeometry()
            return null
        }
    }

    /**
     * Auto-fit the camera to frame all loaded models.
     * Computes the bounding box of all assets and adjusts the orbit controller distance.
     */
    fun fitToModels() {
        if (models.isEmpty()) return
        fitToBounds(ContentCentering.union(contentBoxes()))
        // The camera dolly/target moved — repaint (#2332). The orbit controller
        // also detects the move on its next tick, but request explicitly so a
        // fit on an otherwise-idle scene paints immediately.
        requestRender()
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
    private fun contentBoxes(
        source: List<LoadedModel> = models,
    ): List<ContentCentering.Aabb> = source.mapNotNull { model ->
        // #1597: skip models whose resources are still in flight — their box is
        // not yet readable, so including them would frame on a wrong diagonal.
        if (!model.loaded) return@mapNotNull null
        try {
            val aabb = model.asset.getBoundingBox()
            val mn: dynamic = aabb.min
            val mx: dynamic = aabb.max
            // #2432: `getBoundingBox()` is asset-space (unscaled). Scale the box
            // by the model's uniform `scale` so the centring offset and
            // auto-dolly use the box the geometry actually renders at — the root
            // transform was scaled by the same factor in `applyRootScale`.
            ContentCentering.scale(
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
                ),
                model.scale.toDouble(),
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
     * Translate the single content-root pivot so the union bounding box of all
     * flat content lands centred on the world origin (the orbit-camera target),
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
     * on iOS an intermediate `contentRoot` Entity is translated. Slice 3 / P4
     * brings the web port to the same design — a single real [contentRoot]
     * [Node] pivot is translated, and every flat asset's root entity is
     * re-parented under it (once), so centring the whole union collapses to one
     * node translation instead of the pre-slice-3 per-asset offset bookkeeping.
     * The visual result is identical (the pivot is a pure translation, so each
     * asset's world transform is `contentRoot(offset) * assetLocal`, i.e. the
     * same `base + offset` the old path wrote per asset). Closes #1052, #1540.
     *
     * ## Node-owned content is excluded
     *
     * Content created through the node factories (`addModelNode` /
     * `addGeometryNode` / typed primitives) already owns its asset root under
     * its own scene-graph pivot; its framing is the caller's responsibility
     * through the node transform, so those models ([LoadedModel.nodeOwned]) are
     * skipped here. Only flat `loadModel` / `addGeometry` world-space content is
     * centred — the exact set the pre-slice-3 pass touched.
     */
    private fun refreshContentCentering() {
        if (!autoCenterGate.shouldRun(autoCenterContent, models.isNotEmpty())) return

        // Only flat (non-node-owned) content is auto-centred through the shared
        // content-root pivot — node-owned assets are framed via their own pivot.
        val flatModels = models.filter { !it.nodeOwned }
        if (flatModels.isEmpty()) return

        // Single union-AABB read, shared with the dolly fit below (#1540 de-dup).
        val union = ContentCentering.union(contentBoxes(flatModels))
        val offset = ContentCentering.centeringOffset(union) ?: return

        // Skip frames where the union diagonal has not moved since the last
        // framed pass — the scene is already settled and the gate will latch.
        // A freshly streamed model grows the diagonal and forces a re-frame.
        val diagonal = ContentCentering.diagonal(union)
        if (!autoCenterGate.shouldFrame(diagonal)) {
            autoCenterGate.recordFraming(diagonal)
            return
        }

        // Re-parent every flat asset root under the single content-root pivot
        // (once per model), then translate that one node by the centring offset.
        // Re-parenting keeps each asset's own local transform (including the
        // #2432 root-scale bake), so the world transform stays
        // `contentRoot(offset) * assetLocal` — byte-identical to the old
        // per-asset `base + offset`, but with one node translation instead of N
        // writes. The `TransformManager.setParent` call is the same one the node
        // graph uses everywhere else (proven in the kotlin-bundle probe).
        // The shared content-root pivot, created lazily and registered as a
        // scene-graph root so `destroy`'s node teardown frees its entity. See
        // the `contentRoot` field.
        val root = contentRoot ?: Node(engine, newEntity()).also {
            it.name = "content-root"
            contentRoot = it
            addNode(it)
        }
        for (model in flatModels) {
            if (model.adoptedByContentRoot) continue
            try {
                root.adoptChildEntity(model.asset.getRoot())
                model.adoptedByContentRoot = true
            } catch (e: Throwable) {
                console.error("SceneView: failed to adopt a model under the content root", e)
            }
        }
        // The offset is the translation that centres the union on the origin;
        // set it directly as the pivot's position (the pivot starts at origin,
        // so this is absolute, and re-runs on a grown union simply overwrite it —
        // no accumulation, replacing the old per-asset base bookkeeping).
        root.position = io.github.sceneview.math.Position(
            offset[0].toFloat(),
            offset[1].toFloat(),
            offset[2].toFloat(),
        )

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
     * Read the column-major 4x4 [mat] (a Filament.js flat 16-element JS
     * `number[]`) into a fresh primitive [DoubleArray] of 16 un-boxed `double`s.
     * Used by [applyRootScale] to read the asset root's current transform before
     * multiplying in the #2432 uniform scale. Allocates one [DoubleArray] per
     * call — fine, it runs once per load, not per frame.
     */
    private fun readMat4(mat: dynamic): DoubleArray {
        val out = DoubleArray(16)
        for (i in 0 until 16) {
            out[i] = (mat[i] as Number).toDouble()
        }
        return out
    }

    /**
     * Bake a uniform [scale] into the root entity of [asset] (#2432).
     *
     * Multiplies the linear (rotation/scale) 3×3 block of the root's current
     * column-major transform by [scale] while leaving the translation column
     * (indices 12, 13, 14) and the homogeneous row untouched — so the model
     * grows/shrinks about its own root origin, exactly like Android
     * `ModelNode(scale = Scale(value))`. Composing onto the *current* transform
     * (rather than overwriting) preserves any glTF-authored root transform.
     *
     * A no-op for the default `scale == 1f`. Runs on the main/render thread
     * (called from [loadModel]'s resolved `then`), never from a worker, so the
     * Filament JNI/WASM call is safe. Failures are logged, not thrown, so a
     * malformed asset cannot break the load pipeline.
     */
    private fun applyRootScale(asset: FilamentAsset, scale: Float) {
        if (scale == 1f) return
        try {
            val tm = transformManager
            val root = asset.getRoot()
            if (!tm.hasComponent(root)) tm.create(root)
            val instance = tm.getInstance(root)
            val current = readMat4(tm.getTransform(instance))
            val s = scale.toDouble()
            // Filament.js `setTransform` expects a plain JS `number[]`, not a
            // Kotlin `DoubleArray` (which lowers to a Float64Array) — build a
            // plain JS array and write into it. This runs once per load, so the
            // one allocation is immaterial (no need for a reused scratch here).
            val out: dynamic = js("new Array(16)")
            for (i in 0 until 16) out[i] = current[i]
            // Scale the 3×3 linear block (columns 0,1,2 → indices 0,1,2,4,5,6,8,9,10),
            // preserving translation (12,13,14) and the homogeneous row (3,7,11,15).
            for (col in 0 until 3) {
                val o = col * 4
                out[o] = current[o] * s
                out[o + 1] = current[o + 1] * s
                out[o + 2] = current[o + 2] * s
            }
            tm.setTransform(instance, out)
        } catch (e: Throwable) {
            console.error("SceneView: failed to apply root scale $scale", e)
        }
    }

    /** Clean up all Filament resources. */
    fun destroy() {
        // #1597 (Tier-2): flag FIRST so any async callback still in flight
        // (loadEnvironment's KTX fetches) bails out instead of calling into
        // the freed engine/scene below.
        destroyed = true
        stopRendering()
        cameraController?.dispose()

        // Tear down the retained node tree (#2024). Snapshot every tracked
        // node FIRST, then drop the graph's tracking (which detaches each
        // child from its parent — Filament setParent(null) on live entities),
        // then destroy each node exactly once (isDestroyed-guarded) so every
        // transform component + entity is freed — the same
        // component-then-entity rule as the light teardown below.
        val graphNodes = sceneGraph.findAllNodes { true }
        sceneGraph.rootNodes.toList().forEach { sceneGraph.removeNode(it) }
        graphNodes.forEach { (it as? Node)?.destroy() }

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
        // Free the camera entity's EntityManager handle too — destroying only
        // the camera component leaks the integer entity slot, the exact inverse
        // of the #1700 light leak. Component first, then entity (parity with the
        // light teardown above).
        engine.destroyEntity(cameraEntity)
        engine.destroySwapChain(swapChain)
        val filament: dynamic = js("Filament")
        filament.Engine.destroy(engine)
    }

    /**
     * The render loop -- called every frame via requestAnimationFrame.
     *
     * Each frame ALWAYS:
     * 1. Auto-resizes viewport if CSS size changed
     * 2. Updates orbit camera controller (rotation, damping)
     * 3. Advances glTF animations
     * 4. Calls engine.execute() to process pending async operations
     *
     * …then submits a GPU frame (beginFrame/renderView/endFrame) **only when the
     * scene is dirty** — the camera moved, an animation is playing, an async
     * load is in flight, the auto-center pass is still running, a resize was
     * just applied, or a mutation called [requestRender] (#2332). The rAF loop
     * itself is never gated, so an idle static scene keeps ticking cheaply and
     * resumes painting the instant anything changes — the gate can only ever
     * cost a stale frame, never a frozen canvas. See [RenderGate].
     */
    private fun renderLoop(timestamp: Double) {
        if (!isRunning) return

        // Auto-resize viewport if canvas CSS size changed. resize() marks the
        // scene dirty, so a resize while otherwise idle still repaints.
        if (autoResize) {
            val w = canvas.clientWidth
            val h = canvas.clientHeight
            val needsResize = w > 0 && h > 0 && (w != canvas.width || h != canvas.height)
            if (needsResize) {
                resize(w, h)
            }
        }

        // The auto-center pass runs (and may move content) on any frame where it
        // is enabled, has content, and has not yet latched. Capture that *before*
        // running it so the final, latching reframe still counts as activity and
        // paints. No-op once centered / disabled. Port of iOS #1026 (#1052).
        val autoCenterActive = autoCenterContent && models.isNotEmpty() && !autoCenterGate.didCenter
        refreshContentCentering()

        // Update orbit camera — reports whether the eye/target actually moved
        // this frame (auto-rotate, damping tail, or a fresh drag/zoom/pan).
        val cameraMoved = cameraController?.update() ?: false

        // Track animation time
        val deltaSeconds = if (lastTimestamp > 0) (timestamp - lastTimestamp) / 1000.0 else 0.0
        lastTimestamp = timestamp

        // Fan the frame tick out through the retained node tree (#2024) so
        // Node.onFrame overrides can animate. The empty-graph guard keeps the
        // idle rAF tick as lean as #2332 left it — a scene with no nodes pays
        // one list-isEmpty check. Node mutations that change pixels must call
        // requestRender() themselves (nodes carry no renderables in slice 1,
        // so a transform write alone never dirties the frame).
        if (sceneGraph.rootNodes.isNotEmpty()) {
            sceneGraph.dispatchFrame(deltaSeconds.toFloat())
        }

        // Update glTF animations for all loaded models. Indexed loop (not
        // forEach) so the per-frame closure + iterator allocation is gone — this
        // runs every tick, including idle ones (#2332). `animating` keeps the
        // gate live for as long as any animation is actually playing.
        var animating = false
        for (i in models.indices) {
            val model = models[i]
            // #2432: honour `model { autoAnimate(false) }` — a static model
            // neither advances animation 0 nor keeps the render gate live.
            if (!model.autoAnimate) continue
            val animator = model.animator ?: continue
            val count = animator.getAnimationCount()
            if (count > 0) {
                animating = true
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

        // Process any pending async Filament operations (texture uploads, etc.).
        // Always runs so streamed uploads progress even on gated frames.
        engine.execute()

        // Render frame — only when something changed (#2332). `pendingLoads > 0`
        // keeps streaming content painting; the gate's settle tail flushes the
        // final uploads after activity stops.
        val active = cameraMoved || animating || pendingLoads > 0 || autoCenterActive
        if (renderGate.shouldRender(active)) {
            if (renderer.beginFrame(swapChain)) {
                renderer.renderView(view)
                renderer.endFrame()
                // Consume one owed settle frame only on an actual submit — a
                // frame Filament skipped for pacing must not burn the budget.
                renderGate.didRender()
            }
        }

        animationFrameId = window.requestAnimationFrame(renderLoopRef)
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
            // #2024 slice 2b: an explicit light{} becomes a retained LightNode
            // (identity pivot over the same built light entity, so the lit
            // result is byte-identical to the flat path) and is addressable via
            // sceneView.sceneGraph afterwards. The default 3-point fill below
            // stays flat — it is an implementation default, not user content.
            sceneView.addLightNode(lightConfig!!)
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

        // #2024 slice 2: the DSL delegates to the retained node tree — each
        // model{}/geometry{} becomes a real Node (identity pivot over the
        // asset root, so the visual result is byte-identical to the flat
        // path) and is addressable via sceneView.sceneGraph afterwards.
        modelConfigs.forEach { config ->
            sceneView.addModelNode(
                config.url,
                autoAnimate = config.autoAnimate,
                scale = config.scale,
                onLoaded = config.onLoaded,
            )
        }
        geometryConfigs.forEach { config ->
            sceneView.addGeometryNode(config)
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
