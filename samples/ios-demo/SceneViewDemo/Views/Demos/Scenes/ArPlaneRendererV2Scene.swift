// @sceneId     ar-plane-renderer-v2
// @title       Plane Renderer V2
// @subtitle    Depth + PBR + HDR + scan-in, with live V1 ↔ V2 toggle
// @category    ar
// @available   false
// @icon        square.grid.3x3.fill
// @iosOnly     true
import SwiftUI

/// Coming-soon placeholder — Android's `ar-plane-renderer-v2` demo (depth +
/// PBR + HDR plane rendering with a live V1 ↔ V2 toggle) has no iOS port yet;
/// added to Android after iOS stopped tracking the catalog (#2804 Job B).
enum ArPlaneRendererV2Scene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
