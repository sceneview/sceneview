// @sceneId     materials
// @title       Materials
// @subtitle    Compare metal, glass, fabric and glow
// @category    advanced
// @section     create
// @available   true
// @icon        paintpalette.fill
// @order       4
// @tags        pbr,material,metallic,roughness,clearcoat,sheen,emissive,texture,streaming,occlusion
// @addedIn     4.0.0
// @updatedIn   4.32.0
import SwiftUI

/// Materials (samples audit, step 0) absorbs `texture-streaming` — the same
/// preset swap on a loaded model, already the Materials stage — and
/// `occlusion-material`, now its Occlusion mode.
///
/// Tokens follow Android's analytics slugs where the modes match: the combined
/// materials stage reports `gallery`, while `2` and `occlusion-material` open
/// the shared `occlusion` mode.
enum MaterialsScene: DemoScene {
    /// The card's modes, in pill order. Also read by `DemoRegistryGuardTests`, which
    /// checks every `DemoDeepLinkRegistry.aliasModes` token lands on one of them.
    @MainActor static var modes: [DemoMode] {
        [
            DemoMode("gallery", title: "Materials",
                     aliases: ["0", "materials", "pbr", "1", "streaming", "texture-streaming"]) {
                MaterialsDemo()
            },
            DemoMode("occlusion", title: "Occlusion", aliases: ["2", "occlusion-material"]) {
                OcclusionMaterialDemo()
            },
        ]
    }

    @MainActor static var destination: AnyView {
        AnyView(DemoModeHost(demoId: "materials", modes: modes))
    }
}
