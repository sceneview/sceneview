// @sceneId     ar-placement
// @title       AR Placement
// @subtitle    One object on the first usable surface
// @category    ar
// @available   true
// @icon        arkit
// @iosOnly     true
// @order       6
// @tags        ar,plane,automatic-placement,anchor,gltf,model
import SwiftUI

enum ArPlacementScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARPlacementDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
