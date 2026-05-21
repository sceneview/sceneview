package io.github.sceneview.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import kotlin.js.Promise

/**
 * Entry point for the SceneView Web library.
 *
 * Registers the SceneView API on `window.sceneview` so it can be used
 * from plain JavaScript after loading via `<script>` tag.
 *
 * Usage:
 * ```html
 * <script src="sceneview-web.js"></script>
 * <script>
 *   sceneview.createViewer("canvas").then(sv => sv.loadModel("model.glb"));
 * </script>
 * ```
 */
fun main() {
    // Register the API on the global window object
    val api: dynamic = js("{}")

    api["version"] = SCENEVIEW_VERSION
    api["createViewer"] = ::jsCreateViewer
    api["createViewerAutoRotate"] = ::jsCreateViewerAutoRotate
    api["createViewerFull"] = ::jsCreateViewerFull
    api["modelViewer"] = ::jsModelViewer
    api["modelViewerAutoRotate"] = ::jsModelViewerAutoRotate

    // Cross-platform haptic facade (mirrors Android `SceneViewHaptic` and iOS
    // `SceneViewSwift.SceneViewHaptic`) — exposed as `sceneview.haptic.*` so
    // plain JS callsites match the Kotlin / Swift APIs 1:1.
    api["haptic"] = io.github.sceneview.web.haptic.SceneViewHaptic()

    js("window")["sceneview"] = api

    console.log("SceneView Web v$SCENEVIEW_VERSION loaded")
}

// --- JS-callable bridge functions ---
// These use explicit types that map cleanly to JavaScript

fun jsCreateViewer(canvasId: String): Promise<SceneViewJS> {
    return createViewerImpl(canvasId, autoRotate = true, cameraControls = true)
}

fun jsCreateViewerAutoRotate(canvasId: String, autoRotate: Boolean): Promise<SceneViewJS> {
    return createViewerImpl(canvasId, autoRotate = autoRotate, cameraControls = true)
}

fun jsCreateViewerFull(
    canvasId: String,
    autoRotate: Boolean,
    cameraControls: Boolean,
    cameraX: Double,
    cameraY: Double,
    cameraZ: Double,
    fov: Double,
    lightIntensity: Double
): Promise<SceneViewJS> {
    return createViewerImpl(canvasId, autoRotate, cameraControls, cameraX, cameraY, cameraZ, fov, lightIntensity)
}

fun jsModelViewer(canvasId: String, modelUrl: String): Promise<SceneViewJS> {
    return createViewerImpl(canvasId, autoRotate = true, cameraControls = true).then { viewer ->
        viewer.loadModel(modelUrl)
        viewer
    }
}

fun jsModelViewerAutoRotate(canvasId: String, modelUrl: String, autoRotate: Boolean): Promise<SceneViewJS> {
    return createViewerImpl(canvasId, autoRotate = autoRotate, cameraControls = true).then { viewer ->
        viewer.loadModel(modelUrl)
        viewer
    }
}

// --- Internal implementation ---

internal fun createViewerImpl(
    canvasId: String,
    autoRotate: Boolean,
    cameraControls: Boolean,
    cameraX: Double = 0.0,
    cameraY: Double = 1.5,
    cameraZ: Double = 5.0,
    fov: Double = 45.0,
    lightIntensity: Double = 50_000.0
): Promise<SceneViewJS> {
    val canvas = document.getElementById(canvasId) as? HTMLCanvasElement
        ?: return Promise.reject(Throwable("Canvas element '$canvasId' not found"))

    // Ensure canvas has physical pixel dimensions
    if (canvas.width == 0 || canvas.height == 0) {
        canvas.width = canvas.clientWidth
        canvas.height = canvas.clientHeight
    }

    return Promise { resolve, reject ->
        try {
            SceneView.create(
                canvas = canvas,
                configure = {
                    camera {
                        eye(cameraX, cameraY, cameraZ)
                        target(0.0, 0.0, 0.0)
                        fov(fov)
                    }
                    light {
                        directional()
                        intensity(lightIntensity)
                        direction(0.6f, -1.0f, -0.8f)
                    }
                    autoRotate(autoRotate)
                    cameraControls(cameraControls)
                },
                onReady = { sceneView ->
                    sceneView.startRendering()

                    // Auto-resize on window resize
                    window.addEventListener("resize", {
                        canvas.width = canvas.clientWidth
                        canvas.height = canvas.clientHeight
                    })

                    val viewer = SceneViewJS()
                    viewer.attach(sceneView)
                    resolve(viewer)
                }
            )
        } catch (e: Throwable) {
            reject(e)
        }
    }
}
