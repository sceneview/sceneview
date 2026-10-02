// @sceneId     ar-placement
// @title       AR Placement
// @subtitle    One object on the first usable surface
// @category    ar
// @section     placeAR
// @available   true
// @icon        arkit
// @iosOnly     true
// @order       23
// @tags        ar,plane,automatic-placement,anchor,gltf,model
// @addedIn     4.4.0
// @updatedIn   4.35.0
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
