@file:Suppress("UnusedParameter") // timestamp in renderFrame is part of XRAnimationFrameRequestCallback signature

package io.github.sceneview.web.xr

import io.github.sceneview.web.SceneView
import io.github.sceneview.web.bindings.*
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement

/**
 * WebXR session manager — handles the XR session lifecycle with Filament rendering.
 *
 * This class bridges the WebXR Device API with the Filament.js renderer,
 * managing session creation, the XR render loop, pose tracking, hit testing,
 * and input handling.
 *
 * Supports both AR (immersive-ar) and VR (immersive-vr) sessions.
 *
 * Usage:
 * ```kotlin
 * WebXRSession.create(
 *     canvas = canvas,
 *     mode = XRSessionMode.IMMERSIVE_AR,
 *     features = WebXRSession.Features(
 *         required = arrayOf(XRFeature.HIT_TEST),
 *         optional = arrayOf(XRFeature.LIGHT_ESTIMATION, XRFeature.DOM_OVERLAY)
 *     )
 * ) { xrSession ->
 *     xrSession.onHitTest = { pose -> /* place content */ }
 *     xrSession.onInputSelect = { source, pose -> /* handle input */ }
 *     xrSession.start()
 * }
 * ```
 */
class WebXRSession private constructor(
    val sceneView: SceneView,
    val xrSession: XRSession,
    val referenceSpace: XRReferenceSpace,
    val mode: String,
    private val glLayer: XRWebGLLayer
) {
    // -- Callbacks --

    /** Called each frame with the viewer pose (head tracking). */
    var onFrame: ((XRFrame, XRViewerPose?) -> Unit)? = null

    /** Called when a hit test finds a surface (AR). Provides the closest hit pose. */
    var onHitTest: ((XRPose) -> Unit)? = null

    /** Called when the user performs a primary select action (tap, trigger). */
    var onInputSelect: ((XRInputSource, XRPose?) -> Unit)? = null

    /** Called when a squeeze action is performed (grip button on controllers). */
    var onInputSqueeze: ((XRInputSource, XRPose?) -> Unit)? = null

    /** Called when input sources change (controllers connected/disconnected). */
    var onInputSourcesChange: ((Array<XRInputSource>, Array<XRInputSource>) -> Unit)? = null

    /** Called when the session ends. */
    var onSessionEnd: (() -> Unit)? = null

    // -- State --

    private var hitTestSource: XRHitTestSource? = null
    private var isRunning = false

    /**
     * Guards against double-freeing the Filament [SceneView]: [stop] ends the
     * session and `session.end()` later fires `onend`, which also tears the
     * scene down. Both paths route through [destroySceneView]; this flag makes
     * the second call a no-op (#2045).
     */
    private var sceneViewDestroyed = false

    /** Whether this is an AR session. */
    val isAR: Boolean get() = mode == XRSessionMode.IMMERSIVE_AR

    /** Whether this is a VR session. */
    val isVR: Boolean get() = mode == XRSessionMode.IMMERSIVE_VR

    /**
     * Feature configuration for requesting an XR session.
     */
    data class Features(
        val required: Array<String> = emptyArray(),
        val optional: Array<String> = emptyArray()
    )

    companion object {

        /**
         * Check if a session mode is supported in the current browser.
         *
         * @param mode One of [XRSessionMode] constants
         * @param callback Receives true if supported
         */
        fun checkSupport(mode: String = XRSessionMode.IMMERSIVE_AR, callback: (Boolean) -> Unit) {
            val xr = Navigator.xr
            if (xr == null) {
                callback(false)
                return
            }
            xr.isSessionSupported(mode).then { supported: Boolean ->
                callback(supported)
            }.catch {
                callback(false)
            }
        }

        /**
         * Create and configure a WebXR session with Filament rendering.
         *
         * Must be called from a user gesture (click/tap event handler).
         *
         * @param canvas The HTML canvas element for rendering
         * @param mode Session mode — "immersive-ar" or "immersive-vr"
         * @param features Required and optional features to request
         * @param referenceSpaceType The reference space type — defaults to "local-floor"
         * @param onError Called if session creation fails
         * @param onReady Called with the configured session
         */
        fun create(
            canvas: HTMLCanvasElement,
            mode: String = XRSessionMode.IMMERSIVE_AR,
            features: Features = Features(
                required = if (mode == XRSessionMode.IMMERSIVE_AR) arrayOf(XRFeature.HIT_TEST) else emptyArray(),
                optional = arrayOf(XRFeature.DOM_OVERLAY, XRFeature.LIGHT_ESTIMATION, XRFeature.HAND_TRACKING)
            ),
            referenceSpaceType: String = XRReferenceSpaceType.LOCAL_FLOOR,
            onError: ((String) -> Unit)? = null,
            onReady: (WebXRSession) -> Unit
        ) {
            val xr = Navigator.xr
            if (xr == null) {
                onError?.invoke("WebXR not supported in this browser")
                return
            }

            // Build session options
            val options = js("{}")
            if (features.required.isNotEmpty()) {
                options.requiredFeatures = features.required
            }
            if (features.optional.isNotEmpty()) {
                options.optionalFeatures = features.optional
            }

            xr.requestSession(mode, options).then { session: XRSession ->
                // Create SceneView with Filament
                SceneView.create(
                    canvas = canvas,
                    configure = {
                        // XR sessions manage their own camera — disable orbit controls
                        cameraControls(false)
                    },
                    // Surface a Filament init failure through this flow's onError
                    // instead of letting create() swallow it into console.error.
                    onError = { error -> onError?.invoke("Failed to initialize Filament for XR: ${error.message}") },
                    onReady = { sceneView ->
                        // Get WebGL2 context with XR compatibility
                        val gl = canvas.asDynamic().getContext("webgl2", js("{xrCompatible: true}"))
                        val xrLayer = XRWebGLLayer(session, gl)

                        val renderStateInit = js("{}")
                        renderStateInit.baseLayer = xrLayer
                        session.updateRenderState(renderStateInit)

                        session.requestReferenceSpace(referenceSpaceType).then { refSpace: XRReferenceSpace ->
                            val xrSession = WebXRSession(sceneView, session, refSpace, mode, xrLayer)
                            xrSession.setupEventHandlers()

                            // Set up hit testing for AR sessions
                            if (mode == XRSessionMode.IMMERSIVE_AR) {
                                xrSession.setupHitTesting(session)
                            }

                            onReady(xrSession)
                        }
                    }
                )
            }.catch { error: dynamic ->
                val message = error?.message?.toString() ?: "Failed to start XR session"
                onError?.invoke(message)
                console.error("WebXRSession: Failed to start $mode session:", error)
            }
        }
    }

    /** Start the XR render loop. */
    fun start() {
        isRunning = true
        xrSession.requestAnimationFrame(::renderFrame)
    }

    /** Stop the XR session and clean up. */
    fun stop() {
        isRunning = false
        hitTestSource?.cancel()
        hitTestSource = null
        xrSession.end()
        // #2045: ending the session does not free the Filament SceneView the
        // session allocated — destroy it explicitly so the engine + WebGL2
        // context are released. Idempotent: the `onend` handler also calls
        // this (covers the user ending the session from the system UI).
        destroySceneView()
    }

    /**
     * Tear down the Filament [SceneView] created for this session — the engine,
     * renderer, WebGL2 context and every GPU resource. Idempotent so a [stop]
     * followed by an `onend` (or vice versa) does not double-free (#2045).
     */
    private fun destroySceneView() {
        if (sceneViewDestroyed) return
        sceneViewDestroyed = true
        sceneView.destroy()
    }

    /**
     * Load a 3D model into the AR/VR scene.
     *
     * @param url URL of the glTF/GLB model
     * @param onLoaded Callback when the model is loaded
     */
    fun loadModel(url: String, onLoaded: ((FilamentAsset) -> Unit)? = null) {
        sceneView.loadModel(url, onLoaded)
    }

    /**
     * Set the position of an entity using a 4x4 matrix from an [XRRigidTransform].
     *
     * @param entity The Filament Entity object
     * @param transform The XR transform to apply
     */
    fun setEntityTransform(entity: Entity, transform: XRRigidTransform) {
        val tm = sceneView.engine.getTransformManager()
        if (tm.hasComponent(entity)) {
            val instance = tm.getInstance(entity)
            tm.setTransform(instance, transform.matrix)
        }
    }

    // -- Private --

    private fun setupEventHandlers() {
        xrSession.onend = {
            isRunning = false
            onSessionEnd?.invoke()
            // #2045: also covers the user ending the session from the system
            // UI / headset menu (not via stop()). Idempotent with stop().
            destroySceneView()
        }

        xrSession.onselect = { event ->
            val inputSource = event.asDynamic().inputSource as? XRInputSource
            if (inputSource != null) {
                val pose = event.asDynamic().frame?.let { frame ->
                    (frame as? XRFrame)?.getPose(inputSource.targetRaySpace, referenceSpace)
                }
                onInputSelect?.invoke(inputSource, pose as? XRPose)
            }
        }

        xrSession.onsqueeze = { event ->
            val inputSource = event.asDynamic().inputSource as? XRInputSource
            if (inputSource != null) {
                val pose = event.asDynamic().frame?.let { frame ->
                    (frame as? XRFrame)?.getPose(inputSource.targetRaySpace, referenceSpace)
                }
                onInputSqueeze?.invoke(inputSource, pose as? XRPose)
            }
        }

        xrSession.oninputsourceschange = { event ->
            val added = event.asDynamic().added as? Array<XRInputSource> ?: emptyArray()
            val removed = event.asDynamic().removed as? Array<XRInputSource> ?: emptyArray()
            onInputSourcesChange?.invoke(added, removed)
        }
    }

    private fun setupHitTesting(session: XRSession) {
        session.requestReferenceSpace(XRReferenceSpaceType.VIEWER).then { viewerSpace: XRReferenceSpace ->
            val hitTestOptions = js("{}")
            hitTestOptions.space = viewerSpace
            session.asDynamic().requestHitTestSource(hitTestOptions).then { source: XRHitTestSource ->
                hitTestSource = source
            }
        }
    }

    private fun renderFrame(timestamp: Double, frame: XRFrame) {
        if (!isRunning) return

        val pose = frame.getViewerPose(referenceSpace)

        // Process hit tests (AR only)
        hitTestSource?.let { source ->
            val results = frame.getHitTestResults(source)
            if (results.isNotEmpty()) {
                val hitPose = results[0].getPose(referenceSpace)
                if (hitPose != null) {
                    onHitTest?.invoke(hitPose)
                }
            }
        }

        // Dispatch frame callback
        onFrame?.invoke(frame, pose)

        // Process pending Filament async operations
        sceneView.engine.execute()

        // Render every XRView (#2046): one for AR, two eyes for stereo VR.
        // Each view contributes its own pose + projection + viewport, so the
        // image registers with the device optics / passthrough.
        if (pose != null) {
            val views = pose.views
            if (views.isNotEmpty()) {
                if (sceneView.renderer.beginFrame(sceneView.swapChain)) {
                    for (view in views) {
                        renderView(view)
                    }
                    sceneView.renderer.endFrame()
                }
            }
        }

        xrSession.requestAnimationFrame(::renderFrame)
    }

    /**
     * Render a single [XRView] (#2046).
     *
     * Filament.js has no single-pass stereo binding here, so each eye is a
     * separate [Renderer.renderView] pass — the camera's model and projection
     * matrices are set from this eye, the Filament viewport is clipped to the
     * eye's region of the shared `XRWebGLLayer` framebuffer, and the scene is
     * drawn once per eye. For mono AR this loop runs exactly once.
     */
    private fun renderView(view: XRView) {
        // Pose: XRView.transform.matrix is the eye's model (camera-to-world)
        // matrix — feed it straight to the Filament camera.
        sceneView.camera.setModelMatrix(view.transform.matrix)

        // Projection: apply the device-supplied per-eye projection so the FOV
        // matches the headset optics / camera passthrough instead of the fixed
        // 45° perspective from SceneView.create.
        sceneView.camera.setCustomProjection(
            view.projectionMatrix,
            sceneView.camera.getNear(),
            sceneView.camera.getCullingFar(),
        )

        // Viewport: clip rendering to this eye's region of the XR framebuffer.
        val viewport = glLayer.getViewport(view)
        val viewportArray = js("[]")
        viewportArray.push(viewport.x, viewport.y, viewport.width, viewport.height)
        sceneView.view.setViewport(viewportArray)

        sceneView.renderer.renderView(sceneView.view)
    }
}
