// @sceneId     model-viewer
// @title       Models
// @subtitle    Explore a model in 3D or in your room
// @category    basics3D
// @section     view3d
// @available   true
// @icon        rotate.3d
// @order       34
// @tags        gltf,glb,hdr,ibl,orbit,ar,viewer,park,scene,multi-model,gallery
// @addedIn     4.4.0
// @updatedIn   4.49.0
import SwiftUI

/// Models (samples audit, step 0) absorbs `multi-model` — the Park scene, now
/// its Park mode, at Android's index 1 — and `scene-gallery`, whose streamed
/// Sketchfab bundles the model picker already covers.
enum ModelViewerScene: DemoScene {
    /// The card's modes, in pill order. Also read by `DemoRegistryGuardTests`, which
    /// checks every `DemoDeepLinkRegistry.aliasModes` token lands on one of them.
    @MainActor static var modes: [DemoMode] {
        [
            DemoMode("single_model", title: "Models",
                     aliases: ["0", "single", "models", "scene-gallery"]) {
                ModelViewerDemo()
            },
            DemoMode("multi_model", title: "Park", aliases: ["1", "multi-model", "park"]) {
                MultiModelDemo()
            },
        ]
    }

    @MainActor static var destination: AnyView {
        AnyView(DemoModeHost(demoId: "model-viewer", modes: modes))
    }
}
