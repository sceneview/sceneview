// @sceneId     ar-depth-visualization
// @title       Depth Visualization
// @subtitle    False-color depth map with camera↔depth blend
// @category    ar
// @available   false
// @icon        paintpalette.fill
// @iosOnly     true
import SwiftUI

/// Coming-soon placeholder — Android's `ar-depth-visualization` demo
/// (false-color depth map) has no iOS port yet (Phase 2 Wave B, #2798). Was
/// a hand-maintained residual id in `DemoDeepLinkRegistry` before this Scene
/// file existed (#2804 Job C).
enum ArDepthVisualizationScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
