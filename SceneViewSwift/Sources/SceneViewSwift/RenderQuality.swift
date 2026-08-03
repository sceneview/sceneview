#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit

/// Rendering quality preset for a `SceneView`.
///
/// Mirrors SceneView Android's `RenderQuality` enum (`sceneview/src/main/java/io/github/sceneview/RenderQuality.kt`).
/// Pass to `SceneView { … }.renderQuality(...)` to apply a coherent set of defaults in one
/// line, instead of tuning every render option individually.
///
/// Choose the preset based on what the user is doing in the scene:
/// - ``Cinematic`` for hero shots, product showcases, single-model viewers where the GPU
///   budget can afford the full bells and whistles.
/// - ``Default`` for general use — matches the out-of-the-box `SceneView` defaults.
/// - ``Performance`` for low-end devices, AR camera-feed backgrounds, or anywhere the GPU
///   is constrained — disables shadows and dims environmental lighting.
///
/// ## iOS RealityKit vs Android Filament divergence
///
/// RealityKit's public render-options API is narrower than Filament's. The Swift port honours
/// what RealityKit exposes and documents the gaps:
///
/// | Knob | Android (Filament) | iOS (RealityKit) |
/// |---|---|---|
/// | Shadows on/off | ✅ via `View.setShadowingEnabled` | ✅ per-light via `DirectionalLightComponent.Shadow` |
/// | SSAO on/off | ✅ via `View.ambientOcclusionOptions` | ⚠️ no public toggle — RealityKit auto-applies AO approximations |
/// | Bloom on/off | ✅ via `View.bloomOptions` | ❌ no public toggle |
/// | MSAA | ✅ via `View.multiSampleAntiAliasingOptions` | ❌ not user-controllable |
/// | HDR color buffer | ✅ `QualityLevel.HIGH/MEDIUM/LOW` | ❌ not exposed |
/// | Dynamic resolution | ✅ `View.dynamicResolutionOptions` | ❌ not exposed |
/// | Environmental IBL intensity | via `EnvironmentLoader` HDR | ✅ via `ImageBasedLightComponent.intensityExponent` |
/// | Person occlusion (AR) | (n/a) | ✅ via `ARView.renderOptions` (AR only, not on `RealityView`) |
///
/// The preset still picks the *best available* RealityKit defaults for each tier, so calling
/// `.renderQuality(.cinematic)` reliably produces a richer scene than `.renderQuality(.performance)`
/// — just not by the same number of dimensions Android tunes.
public enum RenderQuality: Sendable {

    /// Maximum visual fidelity — appropriate for product viewers, hero shots, and single-model
    /// showcases on capable devices.
    ///
    /// On iOS:
    /// - **Shadows**: on for all directional lights (lights without an existing `Shadow` get
    ///   one with `maximumDistance: 12, depthBias: 5.0`).
    /// - **IBL intensity exponent**: clamped to a minimum of `1.0` (full environmental light).
    ///
    /// Cannot influence on iOS today (RealityKit limitations): SSAO quality, MSAA sample count,
    /// HDR buffer quality, bloom strength.
    case cinematic

    /// Balanced quality / performance — the out-of-the-box `SceneView` defaults.
    ///
    /// On iOS:
    /// - **Shadows**: on for main / fill lights with default tuning (`maximumDistance: 8, depthBias: 5.0`).
    /// - **IBL intensity exponent**: preserved (no change).
    case `default`

    /// Minimal post-processing for low-end devices or AR camera-feed scenes where the GPU
    /// budget is constrained.
    ///
    /// On iOS:
    /// - **Shadows**: removed from every directional light in the scene.
    /// - **IBL intensity exponent**: dimmed to `0.5` (half environmental light) to reduce
    ///   indirect-shading cost.
    case performance
}

// MARK: - Internal application

/// Applies a `RenderQuality` preset to the given RealityKit scene root.
///
/// Walks the scene's directional lights and toggles their `Shadow` per the preset. Also
/// adjusts the IBL receiver's intensity if one is present at the receiver entity.
///
/// Returns the new IBL intensity exponent if it changed — caller may want to re-apply it to
/// a freshly-loaded `ImageBasedLightComponent`. Returns `nil` when the preset leaves IBL alone.
@MainActor
@discardableResult
internal func applyRenderQuality(
    _ quality: RenderQuality,
    to root: Entity,
    iblReceiver: Entity? = nil
) -> Float? {
    // 1. Walk all directional lights and apply per-preset shadow behaviour.
    walkDirectionalLights(root) { directional in
        switch quality {
        case .cinematic:
            // Ensure a shadow with cinematic-grade distance.
            if directional.shadow == nil {
                directional.shadow = DirectionalLightComponent.Shadow(
                    maximumDistance: 12,
                    depthBias: 5.0
                )
            }
        case .default:
            // Ensure a shadow with balanced-grade distance, but don't override existing
            // shadow configs (caller may have tuned them via `LightNode.shadowMaximumDistance`).
            if directional.shadow == nil {
                directional.shadow = DirectionalLightComponent.Shadow(
                    maximumDistance: 8,
                    depthBias: 5.0
                )
            }
        case .performance:
            // Drop all shadows to free up the shadow render pass entirely.
            directional.shadow = nil
        }
    }

    // 2. Adjust IBL intensity exponent on the receiver entity (if one exists in this scene).
    //
    // ⚠️ The literals below are RAW EXPONENTS, not the linear multipliers
    // `SceneEnvironment.intensity` speaks since #2897 — `1.0` here means ×2.0 and
    // `0.5` means ×1.41, which no longer dims anything now that the authored
    // default maps to exponent 0. That is currently latent, not a bug: this
    // function's only caller (`SceneView.setupScene`) runs synchronously BEFORE the
    // async `loadEnvironment` sets the `ImageBasedLightComponent`, so both branches
    // below find no component and no-op. Fix the units here if that ordering ever
    // changes, or these values will invert their own documented intent (#2897).
    guard let iblReceiver else { return nil }
    let newExponent: Float?
    switch quality {
    case .cinematic:
        // Bump IBL up to at least 1.0 (full); leave higher values alone.
        if let existing = iblReceiver.components[ImageBasedLightComponent.self]?.intensityExponent,
           existing < 1.0 {
            newExponent = 1.0
        } else {
            newExponent = nil
        }
    case .default:
        // Preserve whatever the scene loaded.
        newExponent = nil
    case .performance:
        // Dim IBL to halve indirect-shading cost.
        newExponent = 0.5
    }
    if let newExponent,
       var ibl = iblReceiver.components[ImageBasedLightComponent.self] {
        ibl.intensityExponent = newExponent
        iblReceiver.components.set(ibl)
        return newExponent
    }
    return nil
}

@MainActor
private func walkDirectionalLights(_ entity: Entity, _ visit: (DirectionalLight) -> Void) {
    if let dl = entity as? DirectionalLight {
        visit(dl)
    }
    for child in entity.children {
        walkDirectionalLights(child, visit)
    }
}
#endif
