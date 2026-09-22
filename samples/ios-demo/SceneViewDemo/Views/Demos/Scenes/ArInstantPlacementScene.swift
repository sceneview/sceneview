// @sceneId     ar-instant-placement
// @title       Automatic Placement
// @subtitle    Place immediately when a surface is usable
// @category    ar
// @available   true
// @icon        bolt.fill
// @iosOnly     true
// @order       23
// @tags        ar,placement,detected-plane,raycast,anchor
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
