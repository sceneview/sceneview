package io.github.sceneview

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
 * - [Default] for general use — matches the out-of-the-box `SceneView` defaults shipped in
 *   (shadows on, SSAO on, subtle bloom, PBR Neutral tone mapping, 1× resolution).
 * - [Performance] for low-end Android devices, AR camera-feed backgrounds, or anywhere the GPU
 *   is constrained — disables shadows, AO and bloom, enables dynamic resolution.
 *
 * The preset can be combined with finer-grained tweaks: explicit calls to
 * `view.colorGrading = ...` or `view.bloomOptions = bloomOptions.apply { strength = 0.2f }`
 * after the preset is applied will override the preset's settings.
 */
enum class RenderQuality {

    /**
     * Maximum visual fidelity — appropriate for product viewers, hero shots, and single-model
     * showcases on flagship devices.
     *
     * - Shadows: on, high quality (MSAA 4× on shadow map)
     * - SSAO: on, high quality (BILATERAL upsampling)
     * - Bloom: on, strength 0.15 (slightly stronger than [Default])
     * - Anti-aliasing: MSAA 4× + FXAA
     * - HDR color buffer: HIGH
     * - Dynamic resolution: off
     * - Tone mapping: unchanged — PBR Neutral from [createView], Filmic from `createARView`
     *   (any other tone mapper via [View.colorGrading])
     */
    Cinematic,

    /**
     * Balanced quality / performance — the out-of-the-box `SceneView` defaults.
     *
     * - Shadows: on, default quality
     * - SSAO: on, MEDIUM quality
     * - Bloom: on, strength 0.10
     * - Anti-aliasing: FXAA
     * - HDR color buffer: MEDIUM
     * - Dynamic resolution: off
     * - Tone mapping: PBR Neutral (Filmic in AR)
     *
     * Matches the settings applied by [createView] when no preset is specified.
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
     * - Dynamic resolution: on (auto-scales rendering resolution to keep frame rate)
     * - Tone mapping: unchanged
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
    dynamicResolutionOptions = dynamicResolutionOptions.apply {
        enabled = false
    }
    multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
        enabled = false
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
