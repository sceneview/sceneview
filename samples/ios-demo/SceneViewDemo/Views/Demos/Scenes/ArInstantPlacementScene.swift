// @sceneId     ar-instant-placement
// @title       Instant Placement
// @subtitle    Place models before plane detection converges
// @category    ar
// @available   true
// @icon        bolt.fill
// @iosOnly     true
// @order       23
// @tags        ar,instant-placement,plane,anchor,arcore
import SwiftUI

enum ArInstantPlacementScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARInstantPlacementDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
