// @sceneId     ar-scene-semantics
// @title       Scene Semantics
// @subtitle    12-class outdoor scene labeling — HUD shows top-3 labels in view
// @category    ar
// @available   false
// @icon        leaf.fill
// @iosOnly     true
import SwiftUI

/// Coming-soon placeholder — Android's `ar-scene-semantics` demo (12-class
/// outdoor scene labeling) has no iOS port yet (Phase 3, #2798). Was a
/// hand-maintained residual id in `DemoDeepLinkRegistry` before this Scene
/// file existed (#2804 Job C).
enum ArSceneSemanticsScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
