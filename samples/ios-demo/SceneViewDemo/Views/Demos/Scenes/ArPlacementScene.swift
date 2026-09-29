// @sceneId     ar-placement
// @title       AR Placement
// @subtitle    One object on the first usable surface
// @category    ar
// @section     placeAR
// @available   true
// @icon        arkit
// @iosOnly     true
// @order       10
// @tags        ar,plane,automatic-placement,anchor,gltf,model
// @updatedIn   4.39.0
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
