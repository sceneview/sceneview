package io.github.sceneview.web

import io.github.sceneview.core.threemf.ThreeMfLoader
import kotlinx.browser.document
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
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

    // #3482 — 3MF, the format ChatGPT and every slicer emit for a printable model.
    // `loadModel` / `modelViewer` already accept a `.3mf` URL with no extra call; these two
    // are for a page that holds the *bytes* (a dropped file, a fetch it made itself) and
    // wants the GLB — e.g. to hand it to another glTF viewer, or to a Blob URL.
    api["isThreeMf"] = ::jsIsThreeMf
    api["threeMfToGlb"] = ::jsThreeMfToGlb

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

/**
 * `true` when [bytes] is a 3MF package — ZIP magic, then a `3D/3dmodel.model` part (#3482).
 *
 * Accepts an `ArrayBuffer` or any `ArrayBufferView` (`Uint8Array`, the `Int8Array` a
 * `File.arrayBuffer()` gives you once viewed, …). Returns `false` for anything else, never throws.
 */
fun jsIsThreeMf(bytes: dynamic): Boolean =
    asArrayBuffer(bytes)?.let { ThreeMfConverter.isThreeMf(it) } ?: false

/**
 * Converts a 3MF to a self-contained GLB — metres, Y-up, flat-shaded, one material per 3MF colour
 * — and returns it as a `Uint8Array` (#3482).
 *
 * Accepts an `ArrayBuffer` or any `ArrayBufferView`. **Throws** when [bytes] is not a readable 3MF,
 * so a caller can tell "not a 3MF" from "a broken 3MF"; use [jsIsThreeMf] first to branch without
 * an exception.
 */
fun jsThreeMfToGlb(bytes: dynamic): Uint8Array {
    val buffer = asArrayBuffer(bytes)
        ?: throw IllegalArgumentException(
            "threeMfToGlb expects an ArrayBuffer or an ArrayBufferView (Uint8Array, …)",
        )
    val glb = ThreeMfLoader.toGlb(Int8Array(buffer).unsafeCast<ByteArray>())
    return Uint8Array(glb.toArrayBuffer())
}

/**
 * The `ArrayBuffer` behind a JS value, or `null` when it is neither a buffer nor a view of one.
 *
 * A view is narrowed to the bytes it actually spans: `new Uint8Array(buf, 8, 4)` must convert those
 * four bytes, not the whole buffer.
 */
private fun asArrayBuffer(value: dynamic): ArrayBuffer? = when {
    value == null || value == undefined -> null
    value is ArrayBuffer -> value
    js("ArrayBuffer.isView")(value) as Boolean ->
        (value.buffer as ArrayBuffer).slice(
            value.byteOffset as Int,
            (value.byteOffset as Int) + (value.byteLength as Int),
        )
    else -> null
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
    // Directional key-light intensity, in lux — read under the physically-based
    // default exposure (see CameraConfig). Lowered from 50_000: the previous
    // value paired with the broken `setExposureDirect(1.1)` blew models out to a
    // white blob (the /view Duck bug). Calibrated on the Khronos Duck.
    lightIntensity: Double = 15_000.0
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
                // Init runs async inside Filament's `init` callback, so a crash
                // there can't reach the `try/catch` below. Reject the Promise on
                // failure so `createViewer(...).then(...)` callers get a rejected
                // Promise instead of an infinite hang on a blank canvas.
                onError = { error -> reject(error) },
                onReady = { sceneView ->
                    sceneView.startRendering()

                    // #2048: no `window` resize listener here. SceneView's
                    // render loop already auto-resizes when the canvas CSS
                    // size changes (`autoResize = true`) and does it correctly
                    // — it also updates the Filament viewport + projection,
                    // which a bare `canvas.width/height` mutation does not.
                    // A `window`-scoped listener would also leak: it captures
                    // `canvas` and is untracked, so `dispose()` cannot detach
                    // it (same class of bug as #1698).

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
