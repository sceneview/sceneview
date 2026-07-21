// @sceneId     ar-depth-of-field
// @title       AR Depth of Field
// @subtitle    Tap to focus — real-world bokeh blur
// @category    ar
// @available   false
// @icon        camera.aperture
// @iosOnly     true
import SwiftUI

/// Coming-soon placeholder — Android's `ar-depth-of-field` demo
/// (depth-of-field post-process) has no iOS port yet (Phase 2 Wave B,
/// #2798). Was a hand-maintained residual id in `DemoDeepLinkRegistry`
/// before this Scene file existed (#2804 Job C).
enum ArDepthOfFieldScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
