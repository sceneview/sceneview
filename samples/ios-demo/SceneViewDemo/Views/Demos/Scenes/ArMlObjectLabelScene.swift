// @sceneId     ar-ml-object-label
// @title       ML Object Labels
// @subtitle    On-device object detection with 3D labels anchored on real-world hits
// @category    ar
// @available   false
// @icon        tag.fill
// @iosOnly     true
import SwiftUI

/// Coming-soon placeholder — Android's `ar-ml-object-label` demo (ML Kit
/// object detection) has no iOS port yet; a port would use Apple's
/// Vision/CoreML frameworks instead of ML Kit (Phase 3, #2798). Was a
/// hand-maintained residual id in `DemoDeepLinkRegistry` before this Scene
/// file existed (#2804 Job C).
enum ArMlObjectLabelScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
