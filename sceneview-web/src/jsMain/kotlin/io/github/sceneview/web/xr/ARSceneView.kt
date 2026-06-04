@file:Suppress(
    "MaxLineLength",    // WebXR Promise chain lambdas with type annotations are legitimately long
    "UnusedParameter",  // timestamp in renderFrame is part of the XRAnimationFrameRequestCallback signature
)

package io.github.sceneview.web.xr

import io.github.sceneview.web.SceneView
import io.github.sceneview.web.bindings.*
import org.w3c.dom.HTMLCanvasElement

/**
 * AR SceneView for Web — WebXR-based augmented reality in the browser.
 *
 * Uses the WebXR Device API for camera passthrough AR on supported devices
 * (Chrome Android, Safari iOS 18+, Quest Browser, etc.) combined with
 * Filament.js for 3D rendering.
 *
 * This is a high-level convenience wrapper around [WebXRSession] for AR-only use.
 * For VR or more control, use [WebXRSession] directly.
 *
 * Usage:
 * ```kotlin
 * ARSceneView.checkSupport { supported ->
 *     if (supported) {
 *         ARSceneView.create(canvas) { arSession ->
 *             arSession.onHitTest = { pose ->
 *                 // Place model at hit position
 *                 arSession.sceneView.loadModel("models/chair.glb")
 *             }
 *             arSession.start()
 *         }
 *     }
 * }
 * ```
 */
class ARSceneView private constructor(
    val sceneView: SceneView,
    val session: XRSession,
    val referenceSpace: XRReferenceSpace,
    private val glLayer: XRWebGLLayer,
) {
    /** Callback when user taps and a hit test result is found. */
    var onHitTest: ((XRPose) -> Unit)? = null

    /** Callback when session ends. */
    var onSessionEnd: (() -> Unit)? = null

    /** Callback when select (tap) input occurs. */
    var onSelect: ((XRInputSource) -> Unit)? = null

    private var hitTestSource: XRHitTestSource? = null
    private var isRunning = false

    /**
     * Reusable per-XRView viewport scratch arrays, keyed by the view's index in
     * `pose.views` (#2274). AR is mono (one view) but the loop is shared with the
     * stereo path. Mutating one cached array per view in place — instead of
     * allocating a fresh `[x, y, w, h]` array per view per frame — kills a
     * per-requestAnimationFrame allocation source (GC sawtooth, worst on iOS
     * Safari). `setViewport` reads synchronously, so reuse is safe. We also skip
     * `setViewport` entirely when the viewport is byte-for-byte unchanged.
     */
    private val viewportScratch = mutableListOf<dynamic>()

    /**
     * Idempotency guard for [destroySceneView] — `stop()` and the `onend`
     * handler both tear the Filament [SceneView] down, this prevents a
     * double-free (#2045).
     */
    private var sceneViewDestroyed = false

    companion object {

        /**
         * Check if AR is supported in the current browser.
         */
        fun checkSupport(callback: (Boolean) -> Unit) {
            WebXRSession.checkSupport(XRSessionMode.IMMERSIVE_AR, callback)
        }

        /**
         * Create an AR session with Filament rendering.
         *
         * Must be called from a user gesture (click/tap event handler).
         *
         * @param canvas The HTML canvas for rendering
         * @param features Additional features to request (hit-test is always required)
         * @param onError Called if AR session creation fails
         * @param onReady Callback with the configured ARSceneView
         */
        fun create(
            canvas: HTMLCanvasElement,
            features: WebXRSession.Features = WebXRSession.Features(
                required = arrayOf(XRFeature.HIT_TEST),
                optional = arrayOf(XRFeature.DOM_OVERLAY, XRFeature.LIGHT_ESTIMATION)
            ),
            onError: ((String) -> Unit)? = null,
            onReady: (ARSceneView) -> Unit
        ) {
            val xr = Navigator.xr
            if (xr == null) {
                onError?.invoke("WebXR not supported in this browser")
                return
            }

            // Build session options
            val options = js("{}")
            options.requiredFeatures = features.required
            if (features.optional.isNotEmpty()) {
                options.optionalFeatures = features.optional
            }

            xr.requestSession(XRSessionMode.IMMERSIVE_AR, options).then { session: XRSession ->
                // Create SceneView with Filament
                SceneView.create(
                    canvas = canvas,
                    configure = {
                        // AR uses camera from WebXR, not user-configured camera
                        cameraControls(false)
                    },
                    // Surface a Filament init failure through this flow's onError
                    // instead of letting create() swallow it into console.error.
                    onError = { error -> onError?.invoke("Failed to initialize Filament for AR: ${error.message}") },
                    onReady = { sceneView ->
                        // Set up XR rendering
                        val gl = canvas.asDynamic().getContext("webgl2", js("{xrCompatible: true}"))
                        val xrLayer = XRWebGLLayer(session, gl)

                        val renderStateInit = js("{}")
                        renderStateInit.baseLayer = xrLayer
                        session.updateRenderState(renderStateInit)

                        session.requestReferenceSpace(XRReferenceSpaceType.LOCAL_FLOOR).then { refSpace: XRReferenceSpace ->
                            val arView = ARSceneView(sceneView, session, refSpace, xrLayer)

                            // Set up hit test source from viewer space
                            session.requestReferenceSpace(XRReferenceSpaceType.VIEWER).then { viewerSpace: XRReferenceSpace ->
                                val hitTestOptions = js("{}")
                                hitTestOptions.space = viewerSpace
                                session.asDynamic().requestHitTestSource(hitTestOptions).then { source: XRHitTestSource ->
                                    arView.hitTestSource = source
                                }
                            }

                            // Handle select (tap) events
                            session.onselect = { event ->
                                val inputSource = event.asDynamic().inputSource as? XRInputSource
                                if (inputSource != null) {
                                    arView.onSelect?.invoke(inputSource)
                                }
                            }

                            session.onend = {
                                arView.isRunning = false
                                arView.onSessionEnd?.invoke()
                                // #2045: free the Filament SceneView when the
                                // session ends from the system UI, not stop().
                                arView.destroySceneView()
                            }

                            onReady(arView)
                        }
                    }
                )
            }.catch { error: dynamic ->
                val message = error?.message?.toString() ?: "Failed to start AR session"
                onError?.invoke(message)
                console.error("ARSceneView: Failed to start AR session:", error)
            }
        }
    }

    /** Start the AR render loop. */
    fun start() {
        isRunning = true
        session.requestAnimationFrame(::renderFrame)
    }

    /** Stop the AR session. */
    fun stop() {
        isRunning = false
        hitTestSource?.cancel()
        hitTestSource = null
        session.end()
        // #2045: destroy the Filament SceneView the session allocated so the
        // engine + WebGL2 context are freed. Idempotent with the `onend` path.
        destroySceneView()
    }

    /** Load a 3D model into the AR scene. */
    fun loadModel(url: String, onLoaded: ((FilamentAsset) -> Unit)? = null) {
        sceneView.loadModel(url, onLoaded)
    }

    /**
     * Tear down the Filament [SceneView] — engine, renderer, WebGL2 context and
     * GPU resources. Idempotent so `stop()` and `onend` cannot double-free
     * (#2045).
     */
    private fun destroySceneView() {
        if (sceneViewDestroyed) return
        sceneViewDestroyed = true
        sceneView.destroy()
    }

    private fun renderFrame(timestamp: Double, frame: XRFrame) {
        if (!isRunning) return

        val pose = frame.getViewerPose(referenceSpace)

        // Process hit tests
        hitTestSource?.let { source ->
            val results = frame.getHitTestResults(source)
            if (results.isNotEmpty()) {
                val hitPose = results[0].getPose(referenceSpace)
                if (hitPose != null) {
                    onHitTest?.invoke(hitPose)
                }
            }
        }

        // Process pending Filament async operations (texture uploads, etc.)
        sceneView.engine.execute()

        // Render every XRView (#2046). AR is mono — one view — but the loop is
        // shared with the stereo path: each view sets its own pose, the
        // device-supplied projection, and its viewport before being drawn.
        if (pose != null) {
            val views = pose.views
            if (views.isNotEmpty()) {
                if (sceneView.renderer.beginFrame(sceneView.swapChain)) {
                    for (i in views.indices) {
                        renderView(views[i], i)
                    }
                    sceneView.renderer.endFrame()
                }
            }
        }

        session.requestAnimationFrame(::renderFrame)
    }

    /**
     * Render a single [XRView] (#2046): apply the eye's pose and the
     * device-supplied projection matrix, clip the Filament viewport to the
     * eye's region of the `XRWebGLLayer` framebuffer, then draw the scene.
     *
     * @param viewIndex the view's position in `pose.views`, used to key its
     *   reusable viewport scratch array (#2274).
     */
    private fun renderView(view: XRView, viewIndex: Int) {
        sceneView.camera.setModelMatrix(view.transform.matrix)
        sceneView.camera.setCustomProjection(
            view.projectionMatrix,
            sceneView.camera.getNear(),
            sceneView.camera.getCullingFar(),
        )

        val viewport = glLayer.getViewport(view)
        // Reuse the per-view scratch array (#2274): mutate in place, allocate only the
        // first time this view index is seen. Always call setViewport (the Filament
        // `view` viewport is shared state) — the #2274 win is the zero allocation, not
        // skipping the call. Mono AR is single-view so a skip would be safe here, but we
        // keep the same shape as the VR path for robustness (#2308 review).
        while (viewportScratch.size <= viewIndex) {
            viewportScratch.add(null)
        }
        var arr = viewportScratch[viewIndex]
        if (arr == null) {
            arr = js("[]")
            arr.push(viewport.x, viewport.y, viewport.width, viewport.height)
            viewportScratch[viewIndex] = arr
        } else {
            arr[0] = viewport.x
            arr[1] = viewport.y
            arr[2] = viewport.width
            arr[3] = viewport.height
        }
        sceneView.view.setViewport(arr)

        sceneView.renderer.renderView(sceneView.view)
    }
}

/**
 * VR SceneView for Web — WebXR-based virtual reality in the browser.
 *
 * Uses the WebXR Device API for immersive VR on headsets (Quest, Vive, etc.)
 * combined with Filament.js for 3D rendering.
 *
 * Usage:
 * ```kotlin
 * VRSceneView.checkSupport { supported ->
 *     if (supported) {
 *         VRSceneView.create(canvas) { vrSession ->
 *             vrSession.sceneView.loadModel("models/room.glb")
 *             vrSession.onInputSelect = { source, pose ->
 *                 // Handle controller trigger
 *             }
 *             vrSession.start()
 *         }
 *     }
 * }
 * ```
 */
class VRSceneView private constructor(
    val sceneView: SceneView,
    val session: XRSession,
    val referenceSpace: XRReferenceSpace,
    private val glLayer: XRWebGLLayer
) {
    /** Called each frame with the viewer pose. */
    var onFrame: ((XRFrame, XRViewerPose?) -> Unit)? = null

    /** Called when the user performs a primary select action (trigger press). */
    var onInputSelect: ((XRInputSource, XRPose?) -> Unit)? = null

    /** Called when a squeeze action is performed (grip button). */
    var onInputSqueeze: ((XRInputSource, XRPose?) -> Unit)? = null

    /** Called when session ends. */
    var onSessionEnd: (() -> Unit)? = null

    private var isRunning = false

    /**
     * Reusable per-XRView viewport scratch arrays, keyed by the eye's index in
     * `pose.views` (#2274). A stereo headset reports two views; each gets its own
     * cached `[x, y, w, h]` array, mutated in place instead of re-allocated every
     * frame. `setViewport` reads synchronously, so reuse is safe; we also skip the
     * call when the viewport is unchanged.
     */
    private val viewportScratch = mutableListOf<dynamic>()

    /**
     * Idempotency guard for [destroySceneView] — `stop()` and the `onend`
     * handler both tear the Filament [SceneView] down (#2045).
     */
    private var sceneViewDestroyed = false

    companion object {

        /** Check if VR is supported. */
        fun checkSupport(callback: (Boolean) -> Unit) {
            WebXRSession.checkSupport(XRSessionMode.IMMERSIVE_VR, callback)
        }

        /**
         * Create a VR session with Filament rendering.
         *
         * Must be called from a user gesture.
         *
         * @param canvas The HTML canvas for rendering
         * @param features Optional features to request
         * @param referenceSpaceType Reference space — defaults to "local-floor" for room-scale
         * @param onError Called if session creation fails
         * @param onReady Callback with the configured VRSceneView
         */
        fun create(
            canvas: HTMLCanvasElement,
            features: WebXRSession.Features = WebXRSession.Features(
                optional = arrayOf(XRFeature.HAND_TRACKING)
            ),
            referenceSpaceType: String = XRReferenceSpaceType.LOCAL_FLOOR,
            onError: ((String) -> Unit)? = null,
            onReady: (VRSceneView) -> Unit
        ) {
            val xr = Navigator.xr
            if (xr == null) {
                onError?.invoke("WebXR not supported in this browser")
                return
            }

            val options = js("{}")
            if (features.required.isNotEmpty()) {
                options.requiredFeatures = features.required
            }
            if (features.optional.isNotEmpty()) {
                options.optionalFeatures = features.optional
            }

            xr.requestSession(XRSessionMode.IMMERSIVE_VR, options).then { session: XRSession ->
                SceneView.create(
                    canvas = canvas,
                    configure = {
                        cameraControls(false)
                    },
                    // Surface a Filament init failure through this flow's onError
                    // instead of letting create() swallow it into console.error.
                    onError = { error -> onError?.invoke("Failed to initialize Filament for VR: ${error.message}") },
                    onReady = { sceneView ->
                        val gl = canvas.asDynamic().getContext("webgl2", js("{xrCompatible: true}"))
                        val xrLayer = XRWebGLLayer(session, gl)

                        val renderStateInit = js("{}")
                        renderStateInit.baseLayer = xrLayer
                        session.updateRenderState(renderStateInit)

                        session.requestReferenceSpace(referenceSpaceType).then { refSpace: XRReferenceSpace ->
                            val vrView = VRSceneView(sceneView, session, refSpace, xrLayer)

                            session.onselect = { event ->
                                val inputSource = event.asDynamic().inputSource as? XRInputSource
                                if (inputSource != null) {
                                    val frame = event.asDynamic().frame as? XRFrame
                                    val pose = frame?.getPose(inputSource.targetRaySpace, refSpace)
                                    vrView.onInputSelect?.invoke(inputSource, pose)
                                }
                            }

                            session.onsqueeze = { event ->
                                val inputSource = event.asDynamic().inputSource as? XRInputSource
                                if (inputSource != null) {
                                    val frame = event.asDynamic().frame as? XRFrame
                                    val pose = frame?.getPose(inputSource.targetRaySpace, refSpace)
                                    vrView.onInputSqueeze?.invoke(inputSource, pose)
                                }
                            }

                            session.onend = {
                                vrView.isRunning = false
                                vrView.onSessionEnd?.invoke()
                                // #2045: free the Filament SceneView when the
                                // session ends from the headset menu, not stop().
                                vrView.destroySceneView()
                            }

                            onReady(vrView)
                        }
                    }
                )
            }.catch { error: dynamic ->
                val message = error?.message?.toString() ?: "Failed to start VR session"
                onError?.invoke(message)
                console.error("VRSceneView: Failed to start VR session:", error)
            }
        }
    }

    /** Start the VR render loop. */
    fun start() {
        isRunning = true
        session.requestAnimationFrame(::renderFrame)
    }

    /** Stop the VR session. */
    fun stop() {
        isRunning = false
        session.end()
        // #2045: destroy the Filament SceneView the session allocated so the
        // engine + WebGL2 context are freed. Idempotent with the `onend` path.
        destroySceneView()
    }

    /** Load a 3D model into the VR scene. */
    fun loadModel(url: String, onLoaded: ((FilamentAsset) -> Unit)? = null) {
        sceneView.loadModel(url, onLoaded)
    }

    /**
     * Tear down the Filament [SceneView] — engine, renderer, WebGL2 context and
     * GPU resources. Idempotent so `stop()` and `onend` cannot double-free
     * (#2045).
     */
    private fun destroySceneView() {
        if (sceneViewDestroyed) return
        sceneViewDestroyed = true
        sceneView.destroy()
    }

    private fun renderFrame(timestamp: Double, frame: XRFrame) {
        if (!isRunning) return

        val pose = frame.getViewerPose(referenceSpace)

        // Dispatch frame callback
        onFrame?.invoke(frame, pose)

        // Process pending Filament async operations
        sceneView.engine.execute()

        // Render BOTH eyes (#2046). A stereo headset reports two XRViews; the
        // previous code rendered only views[0], leaving the other eye on the
        // clear colour. Each eye gets its own pose, device projection and
        // viewport, then a dedicated renderView pass — Filament.js has no
        // single-pass stereo binding here, so two passes is the honest result.
        if (pose != null) {
            val views = pose.views
            if (views.isNotEmpty()) {
                if (sceneView.renderer.beginFrame(sceneView.swapChain)) {
                    for (i in views.indices) {
                        renderView(views[i], i)
                    }
                    sceneView.renderer.endFrame()
                }
            }
        }

        session.requestAnimationFrame(::renderFrame)
    }

    /**
     * Render a single [XRView] / eye (#2046): apply the eye pose and the
     * device-supplied projection matrix, clip the Filament viewport to the
     * eye's half of the `XRWebGLLayer` framebuffer, then draw the scene.
     *
     * @param viewIndex the eye's position in `pose.views`, used to key its
     *   reusable viewport scratch array (#2274).
     */
    private fun renderView(view: XRView, viewIndex: Int) {
        sceneView.camera.setModelMatrix(view.transform.matrix)
        sceneView.camera.setCustomProjection(
            view.projectionMatrix,
            sceneView.camera.getNear(),
            sceneView.camera.getCullingFar(),
        )

        val viewport = glLayer.getViewport(view)
        // Reuse the per-eye scratch array (#2274): mutate in place, allocate only the
        // first time this eye index is seen. We ALWAYS call setViewport — the Filament
        // `view` viewport is a single shared piece of state set across both eyes each
        // frame, so a per-eye "skip when unchanged" would leave the other eye's viewport
        // in place and render this eye into the wrong half (#2308 review). The #2274 win
        // is the zero allocation, not skipping the call.
        while (viewportScratch.size <= viewIndex) {
            viewportScratch.add(null)
        }
        var arr = viewportScratch[viewIndex]
        if (arr == null) {
            arr = js("[]")
            arr.push(viewport.x, viewport.y, viewport.width, viewport.height)
            viewportScratch[viewIndex] = arr
        } else {
            arr[0] = viewport.x
            arr[1] = viewport.y
            arr[2] = viewport.width
            arr[3] = viewport.height
        }
        sceneView.view.setViewport(arr)

        sceneView.renderer.renderView(sceneView.view)
    }
}
