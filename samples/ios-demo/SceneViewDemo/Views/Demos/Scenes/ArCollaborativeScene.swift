// @sceneId     ar-collaborative
// @title       Collaborative AR
// @subtitle    Multi-user session sync over a pluggable transport
// @category    ar
// @available   false
// @icon        person.3.fill
// @iosOnly     true
import SwiftUI

/// Coming-soon placeholder — Android's `ar-collaborative` demo (multi-user
/// AR session sync) has no iOS port yet (Phase 2 Wave B, #2798). Was a
/// hand-maintained residual id in `DemoDeepLinkRegistry` before this Scene
/// file existed (#2804 Job C).
enum ArCollaborativeScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
