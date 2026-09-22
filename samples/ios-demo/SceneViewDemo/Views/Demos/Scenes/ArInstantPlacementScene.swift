// @sceneId     ar-instant-placement
// @title       Estimated-plane placement
// @subtitle    Tap to place before plane geometry converges
// @category    ar
// @available   true
// @icon        bolt.fill
// @iosOnly     true
// @order       23
// @tags        ar,placement,estimated-plane,raycast,anchor
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
