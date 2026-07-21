// @sceneId     ar-depth-collider
// @title       Depth Collider
// @subtitle    Virtual balls bounce off the real floor / table (depth-driven physics)
// @category    ar
// @available   false
// @icon        circle.grid.cross.fill
// @iosOnly     true
import SwiftUI

/// Coming-soon placeholder — Android's `ar-depth-collider` demo (depth-driven
/// physics collision, Android itself is `KnownIssue`) has no iOS port yet.
/// Was a hand-maintained residual id in `DemoDeepLinkRegistry` before this
/// Scene file existed (#2804 Job C).
enum ArDepthColliderScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
