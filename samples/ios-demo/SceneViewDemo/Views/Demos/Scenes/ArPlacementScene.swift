// @sceneId     ar-placement
// @title       AR Placement
// @subtitle    One object on the first usable surface
// @category    ar
// @section     placeAR
// @available   true
// @icon        arkit
// @iosOnly     true
// @order       24
// @tags        ar,plane,automatic-placement,anchor,gltf,model,wall,vertical-plane,tv,pose,transform,gesture,light-estimation
// @addedIn     4.4.0
// @updatedIn   4.35.0
import SwiftUI

/// The placement card (samples audit, step 0) absorbs the three AR cards that
/// were placement with one twist: `wall-placement`, `ar-pose` and
/// `ar-lighting`. Each is now a mode of this card, reachable by its old id
/// (`DemoDeepLinkRegistry.legacyAliases` + `aliasModes`) or by `?tab=`.
///
/// `place` / `wall` keep Android's indices (`ALIAS_INITIAL_TAB`:
/// `wall-placement` → 1). The Light mode is the former `ar-lighting` screen as
/// it shipped; the audit's target is a light-estimation switch inside the
/// placement flow, which waits for the step 1 redesign of this card.
enum ArPlacementScene: DemoScene {
    /// The card's modes, in pill order — none off iOS, where ARKit does not exist.
    /// Also read by `DemoRegistryGuardTests`, which checks every
    /// `DemoDeepLinkRegistry.aliasModes` token lands on one of them.
    @MainActor static var modes: [DemoMode] {
        #if os(iOS)
        return [
            DemoMode("place", title: "Place", aliases: ["0"]) {
                ARPlacementDemo()
            },
            DemoMode("wall", title: "Wall", aliases: ["1", "wall-placement"]) {
                ARPlacementExperience(wallTV: true, title: "AR Placement")
            },
            DemoMode("free-pose", title: "Free pose", aliases: ["ar-pose"]) {
                ARPoseDemo()
            },
            DemoMode("light", title: "Light", aliases: ["ar-lighting", "light-estimation"]) {
                ARLightingDemo()
            },
        ]
        #else
        return []
        #endif
    }

    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(DemoModeHost(demoId: "ar-placement", modes: modes))
        #else
        return AnyView(EmptyView())
        #endif
    }
}
