// @sceneId     ar-fog
// @title       AR Fog
// @subtitle    Distance fog over real and virtual geometry
// @category    ar
// @available   false
// @icon        cloud.fog.fill
// @iosOnly     true
import SwiftUI

/// Coming-soon placeholder — Android's `ar-fog` demo (issue #1717; distance
/// fog blended with the live AR camera feed) has no iOS port yet. Distinct
/// from the non-AR `fog` id (`FogScene`, already working on iOS). Was a
/// hand-maintained residual id in `DemoDeepLinkRegistry` before this Scene
/// file existed (#2804 Job C).
enum ArFogScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
