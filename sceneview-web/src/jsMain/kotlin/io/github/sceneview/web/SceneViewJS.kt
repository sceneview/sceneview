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
                sv.loadModel(url) {
                    resolve(url)
                }
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
    }

    /**
     * Fit the camera to frame all loaded models.
     */
    @JsName("fitToModels")
    fun fitToModels() {
        _sceneView?.fitToModels()
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
        _sceneView?.autoCenterContent = enabled
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
const val SCENEVIEW_VERSION = "4.16.1"
