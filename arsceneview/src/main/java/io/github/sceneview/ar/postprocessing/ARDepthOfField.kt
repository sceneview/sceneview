package io.github.sceneview.ar.postprocessing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.google.android.filament.View
import com.google.ar.core.Frame
import io.github.sceneview.ar.arcore.hitTestDepth
import io.github.sceneview.components.CameraComponent

/**
 * Opt-in AR depth-of-field configuration.
 *
 * Drives Filament's native depth-of-field post-pass ([View.setDepthOfFieldOptions]) using ARCore
 * environment depth: tapping a near object throws the far background out of focus and vice-versa,
 * because the [io.github.sceneview.ar.camera.ARCameraStream] depth-occlusion material already
 * writes the real-world depth into Filament's z-buffer (`gl_FragDepth` in
 * `camera_stream_depth.mat`). Filament's DoF samples that same z-buffer, so the bokeh blur kicks
 * in for both the virtual scene and the camera background without any extra render passes.
 *
 * **Why this works** (verified against upstream Filament — search `colorPassOutput.depth` in
 * [`filament/src/details/Renderer.cpp`](https://github.com/google/filament/blob/main/filament/src/details/Renderer.cpp)):
 * the DoF pass receives the *geometric-pass depth attachment* — exactly the buffer
 * `camera_stream_depth.mat` writes `gl_FragDepth` into. The separate "Structure Pass" linear-depth
 * target is reserved for SSAO and contact shadows, not DoF. `dofCoc.mat`'s `getCOC()` consumes the
 * raw NDC reverse-Z value and rescales it via `cocParams` derived from the active projection
 * matrix, so the AR camera's projection is the single source of truth for both focus and CoC.
 *
 * **Hard requirements** (callers must satisfy both, otherwise the effect degrades silently):
 *
 *  1. [com.google.ar.core.Config.depthMode] is either
 *     [com.google.ar.core.Config.DepthMode.AUTOMATIC] or
 *     [com.google.ar.core.Config.DepthMode.RAW_DEPTH_ONLY] — without it ARCore never produces a
 *     depth image and the z-buffer holds only virtual content, so the background never blurs.
 *  2. [io.github.sceneview.ar.camera.ARCameraStream.isDepthOcclusionEnabled] is `true` — that's
 *     the toggle that swaps in the depth-aware camera material that writes `gl_FragDepth`.
 *
 * **Device-QA caveats** (the static reasoning checks out, but these need verification on real
 * hardware before claiming the effect is correct end-to-end):
 *
 *  - **Reverse-Z + early-Z.** Filament uses reverse-Z (`1.0` = near, `0.0` = far).
 *    `camera_stream_depth.mat` sets `clip.z = 0.9999f` in the vertex shader and overwrites
 *    `gl_FragDepth` per fragment. Depth-occlusion already ships against this convention, so the
 *    rasterizer path is known good — but DoF sampling at non-occluded background pixels is a new
 *    code path and should be eyeballed on a Pixel-class device.
 *  - **MSAA resolve.** When MSAA is on, Filament inserts a "Resolved Depth Buffer" min-reduce
 *    before the DoF pass; `gl_FragDepth` values survive but may be filtered. Test with MSAA off
 *    first to isolate any resolve-time artefact.
 *  - **CoC calibration.** Filament's `cocParams` is derived from the active camera projection.
 *    If the AR camera node's near/far diverge from the projection used to compute `ndc_depth`
 *    from `depth_mm` in `camera_stream_depth.mat`, [focusDepth] will not land at exactly the
 *    requested meters in screen space.
 *
 * **Off by default.** Apps opt in by passing this struct to [applyARDepthOfField] (or wiring it
 * via the [arDepthOfField] composable). When [enabled] is `false`, [applyARDepthOfField] clears
 * Filament's DoF flag and the post-pass is skipped entirely — there is no measurable runtime
 * cost on disabled frames.
 *
 * @property focusDepth    Distance from the camera, in meters, at which the scene is in perfect
 *                         focus. Pair with [Frame.hitTestDepth] to drive a tap-to-focus loop.
 *                         Must be `> 0` (Filament's `Camera.setFocusDistance` requires positive
 *                         meters); values are clamped on application.
 * @property blurStrength  Multiplier on the circle-of-confusion radius. `0` ≈ effectively off,
 *                         `1` ≈ Filament's stock cinematic strength, higher values produce a
 *                         stronger bokeh blur. Clamped to `[0, 8]` on application to keep the
 *                         CoC inside Filament's `maxForegroundCOC`/`maxBackgroundCOC` defaults.
 * @property enabled       Master switch. When `false` the DoF post-pass is fully disabled and
 *                         carries zero per-frame cost.
 */
data class ARDepthOfFieldOptions(
    val focusDepth: Float,
    val blurStrength: Float = 1.0f,
    val enabled: Boolean = true,
) {
    init {
        require(focusDepth.isFinite()) { "focusDepth must be finite, was $focusDepth" }
        require(blurStrength.isFinite()) { "blurStrength must be finite, was $blurStrength" }
    }
}

/** Minimum focus distance accepted by Filament's `Camera.setFocusDistance` in practice. */
internal const val MIN_FOCUS_DEPTH_METERS = 0.01f

/**
 * Upper bound on [ARDepthOfFieldOptions.blurStrength] — beyond this value Filament's stock
 * `maxForegroundCOC`/`maxBackgroundCOC` (32 px each by default) start clipping the bokeh disc and
 * the effect just looks broken rather than stronger.
 */
internal const val MAX_BLUR_STRENGTH = 8.0f

/**
 * Translates an [ARDepthOfFieldOptions.blurStrength] into Filament's
 * [View.DepthOfFieldOptions.cocScale]. `cocScale` defaults to `1.0` in Filament — the identity
 * scaling — so we use the user-facing knob as a direct multiplier and just clamp the range.
 *
 * `internal` so the math can be exercised by a JVM unit test without booting Filament.
 */
internal fun cocScaleForBlurStrength(blurStrength: Float): Float =
    blurStrength.coerceIn(0f, MAX_BLUR_STRENGTH)

/**
 * Clamps a depth value to a positive, finite focus distance that Filament's
 * [com.google.android.filament.Camera.setFocusDistance] accepts.
 *
 * `internal` so the math can be exercised by a JVM unit test without booting Filament.
 */
internal fun sanitizedFocusDepth(focusDepth: Float): Float =
    if (focusDepth.isFinite() && focusDepth > MIN_FOCUS_DEPTH_METERS) focusDepth
    else MIN_FOCUS_DEPTH_METERS

/**
 * Applies an [ARDepthOfFieldOptions] to a Filament [View] + [CameraComponent].
 *
 * Implementation:
 *  - When [ARDepthOfFieldOptions.enabled] is `true`, sets the Filament camera's focus distance
 *    to the (clamped) [ARDepthOfFieldOptions.focusDepth] and turns Filament's DoF post-pass on,
 *    scaling the circle-of-confusion by [cocScaleForBlurStrength].
 *  - When disabled, only the `enabled` flag on Filament's DoF options is flipped off — the
 *    camera's focus distance is left untouched so a re-enable picks up exactly where the caller
 *    left it.
 *
 * Threading: must run on the GL/main thread like any other Filament mutation.
 */
fun View.applyARDepthOfField(
    camera: CameraComponent,
    options: ARDepthOfFieldOptions
) {
    if (options.enabled) {
        camera.camera.setFocusDistance(sanitizedFocusDepth(options.focusDepth))
        depthOfFieldOptions = depthOfFieldOptions.apply {
            enabled = true
            cocScale = cocScaleForBlurStrength(options.blurStrength)
        }
    } else {
        depthOfFieldOptions = depthOfFieldOptions.apply { enabled = false }
    }
}

/**
 * Opt-in side effect that wires Filament's depth-of-field post-pass to ARCore environment depth.
 *
 * Place this inside an `ARSceneView { … }` content block (or alongside the `ARSceneView`
 * declaration in the parent composable, passing the `view` and `cameraNode` you already own).
 * The effect runs as a [SideEffect] so toggles and slider drags take effect on the next rendered
 * frame, mirroring how `PostProcessingDemo` configures SSAO/FXAA/dithering.
 *
 * Typical wiring:
 *
 * ```kotlin
 * var focusDepth by remember { mutableStateOf(1.0f) }
 * var blurStrength by remember { mutableStateOf(2.0f) }
 *
 * val view = rememberARView(engine)
 * val cameraNode = rememberARCameraNode(engine)
 *
 * arDepthOfField(
 *     view = view,
 *     camera = cameraNode,
 *     options = ARDepthOfFieldOptions(focusDepth, blurStrength),
 * )
 *
 * ARSceneView(
 *     view = view,
 *     cameraNode = cameraNode,
 *     sessionConfiguration = { _, config ->
 *         config.depthMode = Config.DepthMode.AUTOMATIC
 *     },
 *     // …
 *     onGestureListener = rememberOnGestureListener(
 *         onSingleTapConfirmed = { event, _ ->
 *             latestFrame?.hitTestDepth(event.x, event.y)?.let {
 *                 focusDepth = it.distance
 *             }
 *         }
 *     )
 * )
 * ```
 *
 * Don't forget to flip the camera-stream's `isDepthOcclusionEnabled` on too — the effect needs
 * the depth-aware camera material to populate Filament's z-buffer with real-world depth. See the
 * KDoc on [ARDepthOfFieldOptions] for the full requirements.
 */
@Composable
fun arDepthOfField(
    view: View,
    camera: CameraComponent,
    options: ARDepthOfFieldOptions,
) {
    SideEffect {
        view.applyARDepthOfField(camera, options)
    }
}

/**
 * Convenience: resolves a tap location to a focus depth using the ARCore depth image.
 *
 * Returns the real-world distance from the camera to the tapped pixel, suitable for plugging
 * directly into [ARDepthOfFieldOptions.focusDepth]. Returns `null` when depth is unavailable —
 * fall back to the previous focus depth in that case so the picture doesn't pop.
 *
 * Requires the same ARCore depth-mode configuration as [Frame.hitTestDepth].
 */
fun Frame.depthFocusDistance(xPx: Float, yPx: Float): Float? =
    hitTestDepth(xPx, yPx)?.distance
