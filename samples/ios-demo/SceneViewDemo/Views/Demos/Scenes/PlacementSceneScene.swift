// @sceneId     placement-scene
// @title       Placement Scene
// @subtitle    One-line tap-to-place AR — the simplest possible placement flow
// @category    ar
// @available   false
// @icon        mappin.and.ellipse
// @iosOnly     true
import SwiftUI

/// Coming-soon placeholder — Android's `placement-scene` demo (the simplest
/// possible tap-to-place AR flow) has no iOS port yet (Phase 2 Wave A,
/// #2798). Distinct from the already-working `ar-placement` id
/// (`ArPlacementScene`). Was a hand-maintained residual id in
/// `DemoDeepLinkRegistry` before this Scene file existed (#2804 Job C).
enum PlacementSceneScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
