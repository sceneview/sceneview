package io.github.sceneview

import androidx.annotation.RestrictTo
import com.google.android.filament.View
import com.google.android.filament.View.AntiAliasing
import com.google.android.filament.View.QualityLevel
import com.google.android.filament.View.ShadowType

/**
 * Rendering quality preset for a [SceneView].
 *
 * Pass to `SceneView(renderQuality = ...)` to apply a coherent set of Filament defaults in one
 * line, instead of tuning [View.bloomOptions], [View.ambientOcclusionOptions], shadows, MSAA,
 * dynamic resolution, etc. individually.
 *
 * Choose the preset based on what the user is doing in the scene:
 * - [Default] — the out-of-the-box look: a single product model reads as a studio product shot
 *   (soft shadows, tuned SSAO, subtle bloom, PBR Neutral tone mapping) at a cost a mid-range
 *   phone (Adreno 618 class) holds at 60 fps.
 * - [Cinematic] for hero shots and marketing captures on flagship GPUs: 4× MSAA, high-quality
 *   SSAO, contact-hardening shadows, a HIGH HDR buffer.
 * - [Performance] for low-end Android devices, many-model scenes or battery-sensitive flows —
 *   disables shadows, AO and bloom, enables dynamic resolution.
 *
 * In AR usage, every preset keeps SSAO and bloom **off** so they never darken or tint the live
 * camera background (#657), and the Filmic tone mapper is never touched (#1434). Everything else
 * in the preset applies. `ARSceneView` supplies the AR context explicitly,
 * including when its [View] was created by the caller.
 *
 * The preset can be combined with finer-grained tweaks: explicit calls to
 * `view.colorGrading = ...` or `view.bloomOptions = bloomOptions.apply { strength = 0.2f }`
 * after the preset is applied will override the preset's settings.
 */
enum class RenderQuality {

    /**
     * Maximum visual fidelity — hero shots and single-model showcases on flagship devices.
     *
     * - Shadows: on, contact-hardening soft shadows (`ShadowType.PCSS`)
     * - SSAO: on, HIGH quality, HIGH (bilateral) upsampling
     * - Bloom: on, strength 0.15
     * - Anti-aliasing: MSAA 4× + FXAA
     * - HDR color buffer: HIGH
     * - Dynamic resolution: off
     * - Tone mapping: unchanged — PBR Neutral from [createView], Filmic from `createARView`
     *   (any other tone mapper via [View.colorGrading])
     */
    Cinematic,

    /**
     * The out-of-the-box `SceneView` look: a premium product shot at mid-range cost.
     *
     * - Shadows: on, soft (`ShadowType.DPCF`) from a high key light
     *   ([DEFAULT_MAIN_LIGHT_DIRECTION])
     * - SSAO: on, MEDIUM quality, MEDIUM (bilateral) upsampling
     * - Bloom: on, strength 0.10
     * - Anti-aliasing: FXAA (no MSAA: it would multisample the HDR target)
     * - HDR color buffer: MEDIUM
     * - Dynamic resolution: off
     * - Tone mapping: PBR Neutral (Filmic in AR)
     *
     * Matches the settings applied by [createView] when no preset is specified. In AR, the same
     * minus SSAO and bloom — what `createARView` applies.
     */
    Default,

    /**
     * Minimal post-processing for low-end devices or GPU-constrained scenes.
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
 * This one-argument API retains its creation-based AR inference for compatibility: a [View]
 * created by `createARView` gets the AR-safe preset. `ARSceneView` passes
 * its AR usage explicitly, so caller-managed views are AR-safe too.
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
    applyRenderQuality(quality, isArView = this in filmicViews)
}

/**
 * Library-group bridge that applies [quality] with the rendering usage supplied explicitly.
 *
 * AR callers must pass `true` even for a caller-managed [View], because SSAO and bloom would
 * otherwise darken or tint the live camera feed (#657).
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
fun View.applyRenderQuality(quality: RenderQuality, isArView: Boolean) {
    applyRenderQualitySpec(quality.spec(isArView = isArView))
}

/**
 * Everything a [RenderQuality] preset writes to a [View], as plain values so the presets can be
 * compared and pinned without a Filament engine (`RenderQualitySpecTest`).
 */
internal data class RenderQualitySpec(
    val hdrColorBuffer: QualityLevel,
    val dynamicResolution: Boolean,
    val msaaSampleCount: Int,
    val ssao: Boolean,
    val ssaoQuality: QualityLevel,
    val ssaoUpsampling: QualityLevel,
    val bloom: Boolean,
    val bloomStrength: Float,
    val shadows: Boolean,
    val shadowType: ShadowType,
)

/**
 * The values [quality] writes. [isArView] is `true` when the view is used for AR, regardless of
 * where it was created: SSAO and bloom then stay off whatever the preset, because both would
 * darken and tint the live camera background drawn under the virtual content (#657). Everything
 * else is free in AR.
 */
internal fun RenderQuality.spec(isArView: Boolean = false): RenderQualitySpec {
    val base = when (this) {
        RenderQuality.Cinematic -> CINEMATIC_SPEC
        RenderQuality.Default -> DEFAULT_SPEC
        RenderQuality.Performance -> PERFORMANCE_SPEC
    }
    return if (isArView) base.copy(ssao = false, bloom = false) else base
}

private val CINEMATIC_SPEC = RenderQualitySpec(
    hdrColorBuffer = QualityLevel.HIGH,
    dynamicResolution = false,
    msaaSampleCount = 4,
    ssao = true,
    ssaoQuality = QualityLevel.HIGH,
    ssaoUpsampling = QualityLevel.HIGH,
    bloom = true,
    bloomStrength = 0.15f,
    shadows = true,
    // Contact-hardening soft shadows: sharp where the object touches, wide further away.
    // The most expensive filter Filament has — flagship budget only.
    shadowType = ShadowType.PCSS,
)

private val DEFAULT_SPEC = RenderQualitySpec(
    hdrColorBuffer = QualityLevel.MEDIUM,
    dynamicResolution = false,
    // No MSAA: with post-processing on, Filament renders into a multisampled HDR target
    // that has to be resolved every frame; FXAA alone keeps the frame cheap on Adreno 6xx.
    msaaSampleCount = 1,
    ssao = true,
    // MEDIUM samples + bilateral upsampling: Filament's LOW default leaves a blotchy
    // half-resolution halo along silhouettes; HIGH doubles the sample count again.
    ssaoQuality = QualityLevel.MEDIUM,
    ssaoUpsampling = QualityLevel.MEDIUM,
    bloom = true,
    bloomStrength = 0.1f,
    shadows = true,
    // Soft shadows from one blocker search over the same shadow map: the edge of a cast
    // shadow reads as studio light instead of a pixel-sharp cut-out, for a few taps more
    // than the default PCF.
    shadowType = ShadowType.DPCF,
)

private val PERFORMANCE_SPEC = RenderQualitySpec(
    hdrColorBuffer = QualityLevel.LOW,
    dynamicResolution = true,
    msaaSampleCount = 1,
    ssao = false,
    ssaoQuality = QualityLevel.LOW,
    ssaoUpsampling = QualityLevel.LOW,
    bloom = false,
    bloomStrength = 0f,
    shadows = false,
    shadowType = ShadowType.PCF,
)

internal fun View.applyRenderQualitySpec(spec: RenderQualitySpec) {
    renderQuality = renderQuality.apply {
        hdrColorBuffer = spec.hdrColorBuffer
    }
    dynamicResolutionOptions = dynamicResolutionOptions.apply {
        enabled = spec.dynamicResolution
        homogeneousScaling = true
        quality = QualityLevel.MEDIUM
    }
    multiSampleAntiAliasingOptions = multiSampleAntiAliasingOptions.apply {
        enabled = spec.msaaSampleCount > 1
        if (spec.msaaSampleCount > 1) sampleCount = spec.msaaSampleCount
    }
    antiAliasing = AntiAliasing.FXAA
    ambientOcclusionOptions = ambientOcclusionOptions.apply {
        enabled = spec.ssao
        quality = spec.ssaoQuality
        upsampling = spec.ssaoUpsampling
    }
    bloomOptions = bloomOptions.apply {
        enabled = spec.bloom
        if (spec.bloom) strength = spec.bloomStrength
    }
    setShadowType(spec.shadowType)
    setShadowingEnabled(spec.shadows)
}
