// @sceneId     ar-raw-depth-point-cloud
// @title       Raw Depth Point Cloud
// @subtitle    Confidence-filtered point cloud from raw depth
// @category    ar
// @available   false
// @icon        circle.grid.2x2.fill
// @iosOnly     true
import SwiftUI

/// Coming-soon placeholder — Android's `ar-raw-depth-point-cloud` demo has no
/// iOS port yet (Phase 2 Wave B, #2798). Was a hand-maintained residual id
/// in `DemoDeepLinkRegistry` before this Scene file existed (#2804 Job C).
enum ArRawDepthPointCloudScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
