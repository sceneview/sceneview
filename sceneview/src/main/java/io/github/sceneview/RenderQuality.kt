package io.github.sceneview

import com.google.android.filament.Renderer
import com.google.android.filament.View
import com.google.android.filament.View.AntiAliasing
import com.google.android.filament.View.QualityLevel

/**
 * Rendering quality preset for a [SceneView].
 *
 * Pass to `SceneView(renderQuality = ...)` to apply a coherent set of Filament defaults in one
 * line, instead of tuning [View.bloomOptions], [View.ambientOcclusionOptions], shadows, MSAA,
 * dynamic resolution, etc. individually.
 *
 * Choose the preset based on what the user is doing in the scene:
 * - [Cinematic] for hero shots, product showcases, single-model viewers where the GPU budget
 *   can afford the full bells and whistles.
 * - [Default] for general use — matches the out-of-the-box `SceneView` defaults (shadows on,
 *   SSAO on, subtle bloom, Filmic tone mapping, MSAA 4× + FXAA, and dynamic resolution acting
 *   as a safety valve rather than a fixed 1× resolution).
 * - [Performance] for low-end Android devices, AR camera-feed backgrounds, or anywhere the GPU
 *   is constrained — disables shadows, AO, bloom and MSAA, and lets dynamic resolution scale
 *   further down than [Default] allows.
 *
 * The preset can be combined with finer-grained tweaks: explicit calls to
 * `view.colorGrading = ...` or `view.bloomOptions = bloomOptions.apply { strength = 0.2f }`
 * after the preset is applied will override the preset's settings.
 *
 * **A preset has two halves, on two Filament objects.** Everything above is [View] state and is
 * applied by [View.applyRenderQuality]. The tuning of the dynamic-resolution controller —
 * `Renderer.frameRateOptions`, how fast the valve reacts — is [Renderer] state and is applied by
 * [Renderer.applyRenderQuality]. `SceneView` calls both, so a caller who passes neither a `view`
 * nor a `renderer` gets the whole preset; a caller who supplies their own [Renderer] gets it too,
 * because the call is keyed on the renderer that is actually in use. Reaching for
 * `view.applyRenderQuality(...)` alone, outside the composable, applies only the first half.
 *
 * **AR does not take any of this by default.** `ARSceneView(renderQuality = null)` is the default
 * and leaves the camera-feed-tuned `createARView` settings alone — see [Default] for what passing
 * one explicitly turns on over a live camera feed.
 */
enum class RenderQuality {

    /**
     * Maximum visual fidelity — appropriate for product viewers, hero shots, and single-model
     * showcases on flagship devices.
     *
     * - Shadows: on, high quality (MSAA 4× on shadow map)
     * - SSAO: on, high quality (BILATERAL upsampling)
     * - Bloom: on, strength 0.15 (slightly stronger than [Default])
     * - Anti-aliasing: MSAA 4× + FXAA (cumulative, on purpose — see [Default])
     * - HDR color buffer: HIGH
     * - Dynamic resolution: off, so the controller tuning below is inert
     * - Dynamic-resolution controller: Filament's stock `scaleRate = 1/15`
     * - Tone mapping: Filmic (unchanged — users can opt into ACES via [View.colorGrading])
     */
    Cinematic,

    /**
     * Balanced quality / performance — the out-of-the-box `SceneView` defaults.
     *
     * - Shadows: on, default quality
     * - SSAO: on, MEDIUM quality
     * - Bloom: on, strength 0.10
     * - Anti-aliasing: MSAA 4× + FXAA
     * - HDR color buffer: MEDIUM
     * - Dynamic resolution: on, MEDIUM, homogeneous, never below 0.75× per axis
     * - Dynamic-resolution controller: `scaleRate = 1/8` (Filament's stock is 1/15)
     * - Tone mapping: Filmic
     *
     * The dynamic resolution here is a safety valve, not a quality knob: the preset asks for
     * more than every mid-range GPU can always afford, and Filament is given the authority to
     * take some of it back on the frames where it would otherwise cost stutter. The 0.75 floor
     * bounds that authority — softer, never broken.
     *
     * MSAA 4× **and** FXAA run together, deliberately: MSAA resolves geometric edges, FXAA the
     * shading and specular aliasing MSAA cannot see. It is a real cumulative cost, stated here
     * rather than left to be discovered in a profiler.
     *
     * Matches the settings applied by [createView] and [createRenderer] when no preset is
     * specified.
     *
     * **On an AR view this is a different trade.** `ARSceneView` defaults to `renderQuality =
     * null` and does *not* inherit this preset: `createARView` deliberately leaves MSAA, SSAO and
     * bloom off so nothing post-processes the live camera feed. Passing `RenderQuality.Default`
     * to `ARSceneView` explicitly turns MSAA 4× **and** dynamic resolution on over a render that
     * contains the camera background — which is a legitimate choice, but an opt-in one.
     */
    Default,

    /**
     * Minimal post-processing for low-end devices or AR camera-feed scenes where the GPU budget
     * is constrained.
     *
     * - Shadows: off (`view.isShadowingEnabled = false`)
     * - SSAO: off
     * - Bloom: off
     * - Anti-aliasing: FXAA (cheaper than MSAA)
     * - HDR color buffer: LOW
     * - Dynamic resolution: on (auto-scales rendering resolution to keep frame rate), no floor
     * - Dynamic-resolution controller: `scaleRate = 1/8`, as [Default] — the valve this preset
     *   leans on hardest is also the one that should react fastest
     * - Tone mapping: Filmic (unchanged)
     */
    Performance,
}

/**
 * Applies a [RenderQuality] preset to this Filament [View].
 *
 * This mutates the view in place. Call once after creating the view, or whenever the preset
 * changes. Safe to call repeatedly — Filament settings are idempotent.
 *
 * Individual settings can still be overridden after the call (e.g. set a custom
 * [View.colorGrading] or tweak [View.bloomOptions.strength] to a non-preset value).
 *
 * **Important — re-application semantics**: [io.github.sceneview.SceneView] wires this
 * call into a `LaunchedEffect(view, renderQuality)` (#1078) so the preset is reapplied
 * ONLY when `renderQuality` changes. Tweaks made AFTER the preset are preserved across
 * recompositions — but a renderQuality change (e.g. `Default → Cinematic` toggle) WILL
 * clobber them, since the new preset writes the full set of fields. To keep custom
 * tweaks across preset changes, re-apply them in a `LaunchedEffect(view, renderQuality)`
 * that runs after this one.
 */
fun View.applyRenderQuality(quality: RenderQuality) {
    when (quality) {
        RenderQuality.Cinematic -> applyCinematic()
        RenderQuality.Default -> applyDefault()
        RenderQuality.Performance -> applyPerformance()
    }
}

/**
 * Applies the [Renderer] half of a [RenderQuality] preset — the tuning of the dynamic-resolution
 * controller.
 *
 * A preset is not only [View] state. `Renderer.frameRateOptions` is what decides *how fast* the
 * dynamic resolution the [View] half enables reacts to a frame-time spike, and it lives on the
 * [Renderer]. Splitting them was a real hole: the tuning shipped only inside [createRenderer], so
 * a caller who passed their own `Renderer` to `SceneView` — a public parameter — got the new
 * `View` settings paired with Filament's stock controller, i.e. half the preset and a valve
 * reacting at a quarter of the intended speed.
 *
 * Filament ignores these options entirely when dynamic resolution is off, so applying this to a
 * [RenderQuality.Cinematic] renderer is a no-op in effect — it is written anyway, so that a view
 * switched from [RenderQuality.Default] back to [RenderQuality.Cinematic] does not silently keep
 * the previous preset's controller.
 *
 * `headRoomRatio` and `history` are set to Filament's own defaults on purpose: writing them down
 * states what the preset depends on, so a future Filament default change shows up as a diff here
 * rather than as a silent behaviour change in every `SceneView`.
 */
fun Renderer.applyRenderQuality(quality: RenderQuality) {
    frameRateOptions = frameRateOptions.apply {
        headRoomRatio = 0.0f
        // Raised from Filament's stock 1/15 to 1/8 wherever the valve is actually open, so it
        // reacts within a few frames of a spike instead of a quarter second. It is the pair of
        // the 0.75 `minScale` floor: a loop that cannot go far may as well go quickly, and the
        // two together turn a stutter into a brief softening the user is unlikely to catch.
        scaleRate = when (quality) {
            RenderQuality.Cinematic -> STOCK_SCALE_RATE
            RenderQuality.Default, RenderQuality.Performance -> RESPONSIVE_SCALE_RATE
        }
        history = 15
    }
}

/** Filament's own `frameRateOptions.scaleRate`, 1/15 — used where dynamic resolution is off. */
private const val STOCK_SCALE_RATE = 1f / 15f

/** 1/8: the dynamic-resolution valve reacts in a few frames rather than a quarter second. */
private const val RESPONSIVE_SCALE_RATE = 0.125f

private fun View.applyCinematic() {
    renderQuality = renderQuality.apply {
        hdrColorBuffer = QualityLevel.HIGH
    }
    dynamicResolutionOptions = dynamicResolutionOptions.apply {
        enabled = false
    }
    multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
        enabled = true
        sampleCount = 4
    }
    antiAliasing = AntiAliasing.FXAA
    ambientOcclusionOptions = ambientOcclusionOptions.apply {
        enabled = true
        upsampling = QualityLevel.HIGH
        quality = QualityLevel.HIGH
    }
    bloomOptions = bloomOptions.apply {
        enabled = true
        strength = 0.15f
    }
    setShadowingEnabled(true)
}

private fun View.applyDefault() {
    renderQuality = renderQuality.apply {
        hdrColorBuffer = QualityLevel.MEDIUM
    }
    // Kept in step with `createView` — this preset is what an unconfigured SceneView already is,
    // so the two must not drift. See the KDoc on [RenderQuality.Default] for why dynamic
    // resolution is on here: it is the safety valve that pays for the MSAA below.
    dynamicResolutionOptions = dynamicResolutionOptions.apply {
        enabled = true
        homogeneousScaling = true
        quality = QualityLevel.MEDIUM
        minScale = 0.75f
    }
    multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
        enabled = true
        sampleCount = 4
    }
    antiAliasing = AntiAliasing.FXAA
    ambientOcclusionOptions = ambientOcclusionOptions.apply {
        enabled = true
    }
    bloomOptions = bloomOptions.apply {
        enabled = true
        strength = 0.1f
    }
    setShadowingEnabled(true)
}

private fun View.applyPerformance() {
    renderQuality = renderQuality.apply {
        hdrColorBuffer = QualityLevel.LOW
    }
    dynamicResolutionOptions = dynamicResolutionOptions.apply {
        enabled = true
        homogeneousScaling = true
        quality = QualityLevel.MEDIUM
    }
    multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
        enabled = false
    }
    antiAliasing = AntiAliasing.FXAA
    ambientOcclusionOptions = ambientOcclusionOptions.apply {
        enabled = false
    }
    bloomOptions = bloomOptions.apply {
        enabled = false
    }
    setShadowingEnabled(false)
}
