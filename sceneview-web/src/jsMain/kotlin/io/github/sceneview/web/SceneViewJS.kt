package io.github.sceneview.web

import kotlin.js.Promise

/**
 * SceneViewer — the JavaScript-facing 3D viewer class.
 *
 * Instances are created via `sceneview.createViewer()` or `sceneview.modelViewer()`.
 * All methods use `@JsName` to ensure stable, unmangled names in compiled JS.
 *
 * ```js
 * sceneview.createViewer("canvas").then(function(sv) {
 *   sv.loadModel("model.glb");
 *   sv.setAutoRotate(true);
 * });
 * ```
 */
@JsExport
@JsName("SceneViewer")
class SceneViewJS {
    private var _sceneView: SceneView? = null
    private var disposed = false

    /**
     * The [NodeHost] the handles created by this viewer call back into. A
     * private field (not `SceneViewJS : NodeHost`) so the `@JsExport` class does
     * not surface the internal `Node`-typed [NodeHost] methods on its exported
     * shape (Kotlin/JS warns "exported declaration uses non-exportable parameter
     * type 'Node'" for public overrides). The handle keeps the reference; JS
     * never sees it.
     */
    private val nodeHost: NodeHost = object : NodeHost {
        override fun requestRender() {
            _sceneView?.requestRender()
        }

        override fun applyNodeVisibility(node: io.github.sceneview.web.nodes.Node) {
            _sceneView?.applyNodeVisibility(node)
        }

        override fun removeNodeInternal(node: io.github.sceneview.web.nodes.Node) {
            // Purge the WHOLE subtree from the handle registry — removal
            // cascades to descendants, and factory-created children
            // (addCubeNode(parent = …)) hold entries too. Walk before the
            // graph detach mutates the child sets.
            dropHandlesRecursive(node)
            _sceneView?.removeNode(node)
        }
    }

    private fun dropHandlesRecursive(node: io.github.sceneview.web.nodes.Node) {
        nodeHandles.remove(node)
        node.childNodes.forEach { child ->
            (child as? io.github.sceneview.web.nodes.Node)?.let { dropHandlesRecursive(it) }
        }
    }

    /**
     * Node → handle registry so [hitTest] returns the **same** JS handle
     * instances the `add*Node` factories handed out (`===`-comparable), and
     * repeated hits on one node do not mint new wrappers (#2024 P5c).
     * Entries are dropped when a node is removed/destroyed through its handle.
     */
    private val nodeHandles = mutableMapOf<io.github.sceneview.web.nodes.Node, NodeHandle>()

    private fun handleFor(node: io.github.sceneview.web.nodes.Node): NodeHandle =
        nodeHandles.getOrPut(node) { NodeHandle(node, nodeHost) }

    internal fun attach(sv: SceneView) {
        _sceneView = sv
    }

    /**
     * Load a glTF/GLB model from a URL.
     * Returns a Promise that resolves with the URL when loaded.
     */
    @JsName("loadModel")
    fun loadModel(url: String): Promise<String> {
        val sv = _sceneView ?: return Promise.reject(Throwable("SceneViewer not initialized"))
        return Promise { resolve, reject ->
            try {
                // `onLoaded` is named: `loadModel` now has trailing `autoAnimate`
                // / `scale` params (#2432), so a trailing-lambda would bind to the
                // wrong parameter.
                sv.loadModel(url, onLoaded = { resolve(url) })
            } catch (e: Throwable) {
                reject(e)
            }
        }
    }

    /**
     * Load environment lighting from a KTX IBL file URL.
     */
    @JsName("setEnvironment")
    fun setEnvironment(iblUrl: String) {
        _sceneView?.loadEnvironment(iblUrl, null)
    }

    /**
     * Load environment lighting with skybox.
     */
    @JsName("setEnvironmentWithSkybox")
    fun setEnvironmentWithSkybox(iblUrl: String, skyboxUrl: String) {
        _sceneView?.loadEnvironment(iblUrl, skyboxUrl)
    }

    /**
     * Set the camera orbit position in spherical coordinates.
     * @param theta Horizontal angle in radians
     * @param phi Vertical angle in radians
     * @param distance Distance from target
     */
    @JsName("setCameraOrbit")
    fun setCameraOrbit(theta: Double, phi: Double, distance: Double) {
        _sceneView?.cameraController?.let {
            it.theta = theta
            it.phi = phi
            it.distance = distance
        }
    }

    /**
     * Set the camera orbit target (look-at point).
     */
    @JsName("setCameraTarget")
    fun setCameraTarget(x: Double, y: Double, z: Double) {
        _sceneView?.cameraController?.target(x, y, z)
    }

    /**
     * Enable or disable auto-rotation.
     */
    @JsName("setAutoRotate")
    fun setAutoRotate(enabled: Boolean) {
        _sceneView?.cameraController?.autoRotate = enabled
    }

    /**
     * Set auto-rotation speed in radians per frame.
     */
    @JsName("setAutoRotateSpeed")
    fun setAutoRotateSpeed(speed: Double) {
        _sceneView?.cameraController?.autoRotateSpeed = speed
    }

    /**
     * Set minimum and maximum zoom distances.
     */
    @JsName("setZoomLimits")
    fun setZoomLimits(min: Double, max: Double) {
        _sceneView?.cameraController?.let {
            it.minDistance = min
            it.maxDistance = max
        }
    }

    /**
     * Start the render loop. Called automatically by createViewer().
     */
    @JsName("startRendering")
    fun startRendering() {
        _sceneView?.startRendering()
    }

    /**
     * Stop the render loop.
     */
    @JsName("stopRendering")
    fun stopRendering() {
        _sceneView?.stopRendering()
    }

    /**
     * Resize the viewport. Normally handled automatically.
     */
    @JsName("resize")
    fun resize(width: Int, height: Int) {
        _sceneView?.resize(width, height)
    }

    /**
     * Set the background clear color (RGBA, 0-1 range).
     * Use this to sync the 3D canvas background with your page theme.
     */
    @JsName("setBackgroundColor")
    fun setBackgroundColor(r: Double, g: Double, b: Double, a: Double) {
        val sv = _sceneView ?: return
        val opts = js("{}")
        val color = js("[]")
        color.push(r, g, b, a)
        opts["clearColor"] = color
        opts["clear"] = true
        sv.renderer.setClearOptions(opts)
        // The clear color is not a camera move, so the on-demand gate cannot
        // infer it — request a repaint explicitly (#2332).
        sv.requestRender()
    }

    /**
     * Fit the camera to frame all loaded models.
     *
     * @param margin Optional multiplier on the fit distance — the Web
     *   counterpart of iOS `.framingMargin(_:)` (#2946). `1.0` (default) keeps
     *   the historical fit, `< 1` frames tighter, `> 1` leaves more air.
     *   Clamped to `0.2…10`. Unlike Android's additive `padding` fraction
     *   (`margin == 1 + padding`).
     */
    @JsName("fitToModels")
    fun fitToModels(margin: Double = 1.0) {
        _sceneView?.fitToModels(margin)
    }

    /**
     * Toggle library-level auto-centring of loaded content.
     *
     * When enabled (the default), content is translated so its bounding box
     * is centred on the orbit-camera target the first frame it finishes
     * loading. Pass `false` for narrative scenes with intentional off-centre
     * placement. Port of iOS `autoCenterContent` (#1026).
     */
    @JsName("setAutoCenterContent")
    fun setAutoCenterContent(enabled: Boolean) {
        _sceneView?.let {
            it.autoCenterContent = enabled
            // Toggling the framing policy may change what the next frame shows —
            // repaint so an idle scene reflects it (#2332).
            it.requestRender()
        }
    }

    // --- Node scene-graph (#2024 slice 3 / P4) -----------------------------
    //
    // The first `@JsExport` scene-graph surface: plain-JS callers create a
    // node, keep the returned `NodeHandle`, and mutate it after `create()`.
    // Each factory runs the exact retained-node pipeline the Kotlin DSL uses
    // (`addNode`/`addModelNode`/`addCubeNode`/`addSphereNode`/`addLightNode`),
    // so the JS surface and the Kotlin surface build byte-identical graphs.

    /**
     * Adds an empty pivot [io.github.sceneview.web.nodes.Node] to the scene and
     * returns a [NodeHandle] to it — the web mirror of an Android `Node()` used
     * as a grouping transform. Parent other nodes under it with
     * [NodeHandle.addChild] to move them together.
     */
    @JsName("addNode")
    fun addNode(): NodeHandle {
        val sv = _sceneView ?: throw IllegalStateException("SceneViewer not initialized")
        val node = io.github.sceneview.web.nodes.Node(sv.engine, sv.newEntity())
        sv.addNode(node)
        return handleFor(node)
    }

    /**
     * Loads a glTF/GLB model and returns a Promise that resolves with a
     * [NodeHandle] once the model has finished loading — the JS mirror of
     * `SceneView.addModelNode(url)`. Rejects if the viewer is uninitialised.
     *
     * Resolving on load (rather than immediately) keeps the JS contract simple:
     * the handle is usable the moment the caller gets it, with its content
     * already in the scene.
     */
    @JsName("addModelNode")
    fun addModelNode(url: String): Promise<NodeHandle> {
        val sv = _sceneView ?: return Promise.reject(Throwable("SceneViewer not initialized"))
        return Promise { resolve, reject ->
            try {
                // `addModelNode` returns the pivot node synchronously and starts
                // the async load; `onLoaded` fires (with the asset) once the
                // model is in the scene. Resolve the handle then, wrapping the
                // very node `addModelNode` returned. `onLoaded` is a NAMED param
                // — `addModelNode` has trailing `parent`/`autoAnimate`/`scale`
                // params, so a trailing lambda would bind to the wrong one.
                //
                // A nullable holder lets the `onLoaded` closure reference the
                // pivot; it is assigned synchronously (before any async load can
                // complete), so `pivot` is always non-null when `onLoaded` runs.
                var pivot: io.github.sceneview.web.nodes.ModelNode? = null
                pivot = sv.addModelNode(url, onLoaded = { resolve(handleFor(pivot!!)) })
            } catch (e: Throwable) {
                reject(e)
            }
        }
    }

    /**
     * Loads a Gaussian Splatting file (`.ply` INRIA / `.spz` Niantic) and returns a
     * Promise that resolves with the [NodeHandle] of its [io.github.sceneview.web.nodes.SplatNode]
     * once the cloud is parsed and in the scene — the JS mirror of
     * `SceneView.addSplatNode(url)` (#2646 P2). Rejects on fetch/parse failure or an
     * uninitialised viewer.
     */
    @JsName("addSplatNode")
    fun addSplatNode(url: String): Promise<NodeHandle> {
        val sv = _sceneView ?: return Promise.reject(Throwable("SceneViewer not initialized"))
        return Promise { resolve, reject ->
            try {
                sv.addSplatNode(
                    url,
                    onLoaded = { splat -> resolve(handleFor(splat)) },
                    onError = { reject(it) },
                )
            } catch (e: Throwable) {
                reject(e)
            }
        }
    }

    /**
     * Adds a cube primitive and returns its [NodeHandle] — JS mirror of
     * `SceneView.addCubeNode(size)`. The content is in the scene synchronously.
     */
    @JsName("addCubeNode")
    fun addCubeNode(size: Double): NodeHandle {
        val sv = _sceneView ?: throw IllegalStateException("SceneViewer not initialized")
        return handleFor(sv.addCubeNode(size))
    }

    /**
     * Adds a sphere primitive and returns its [NodeHandle] — JS mirror of
     * `SceneView.addSphereNode(radius)`.
     */
    @JsName("addSphereNode")
    fun addSphereNode(radius: Double): NodeHandle {
        val sv = _sceneView ?: throw IllegalStateException("SceneViewer not initialized")
        return handleFor(sv.addSphereNode(radius))
    }

    /**
     * Adds a light and returns its [NodeHandle] — JS mirror of
     * `SceneView.addLightNode(config)`. [type] is one of `"directional"`,
     * `"point"`, or `"spot"`; any other value throws.
     */
    @JsName("addLightNode")
    fun addLightNode(type: String): NodeHandle {
        val sv = _sceneView ?: throw IllegalStateException("SceneViewer not initialized")
        val config = io.github.sceneview.web.nodes.LightConfig().apply {
            when (type) {
                "directional" -> directional()
                "point" -> point()
                "spot" -> spot()
                else -> throw IllegalArgumentException(
                    "Unknown light type '$type' — expected 'directional', 'point', or 'spot'",
                )
            }
        }
        return handleFor(sv.addLightNode(config))
    }

    /**
     * Removes a node (and its subtree) from the graph and frees its Filament
     * entity — the JS mirror of `SceneView.removeNode` + `Node.destroy`.
     * Equivalent to [NodeHandle.destroy].
     */
    @JsName("removeNode")
    fun removeNode(node: NodeHandle) {
        node.destroy()
    }

    /**
     * Picks the retained nodes under a screen point (#2024 P5c) — the JS
     * mirror of `SceneView.hitTest(x, y)`.
     *
     * Coordinates are **canvas pixels** ((0,0) = top-left). For a pointer
     * event on the canvas: `sv.hitTest(event.offsetX * (canvas.width /
     * canvas.clientWidth), event.offsetY * (canvas.height /
     * canvas.clientHeight))` (the factor corrects devicePixelRatio-scaled
     * backing stores).
     *
     * Returns the hit nodes' handles sorted nearest-first — the **same**
     * handle instances the `add*Node` factories returned, so `===`
     * comparison against your kept references works:
     *
     * ```js
     * const cube = sv.addCubeNode(0.5);
     * canvas.addEventListener('click', (e) => {
     *   const hits = sv.hitTest(e.offsetX, e.offsetY);
     *   if (hits[0] === cube) cube.setScaleUniform(1.5);
     * });
     * ```
     *
     * Nodes without bounds (empty pivots, lights, cameras), invisible nodes,
     * and nodes with `isHittable == false` are skipped. Model nodes become
     * pickable once loaded (their AABB is only readable then); geometry and
     * splat nodes are pickable immediately.
     */
    @JsName("hitTest")
    fun hitTest(x: Double, y: Double): Array<NodeHandle> {
        val sv = _sceneView ?: return emptyArray()
        return sv.hitTest(x.toFloat(), y.toFloat())
            .mapNotNull { hit ->
                (hit.node as? io.github.sceneview.web.nodes.Node)?.let { handleFor(it) }
            }
            .toTypedArray()
    }

    /**
     * Clean up all GPU resources. Call when removing the viewer.
     */
    @JsName("dispose")
    fun dispose() {
        if (!disposed) {
            disposed = true
            _sceneView?.destroy()
            _sceneView = null
        }
    }
}

/**
 * Library version.
 *
 * Kept in lockstep with the repo-wide `VERSION_NAME` (`gradle.properties`) — `sceneview-web` has no
 * Gradle `buildConfig` plugin, so this literal is the single source of truth for the JS surface.
 * Bump it together with every other version location (see CLAUDE.md "Version Location Map").
 */
const val SCENEVIEW_VERSION = "4.31.0"
