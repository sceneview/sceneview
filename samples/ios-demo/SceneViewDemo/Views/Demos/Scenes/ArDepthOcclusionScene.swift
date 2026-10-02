// @sceneId     ar-depth-occlusion
// @title       Depth Occlusion
// @subtitle    Real-world depth masks virtual objects
// @category    ar
// @section     understand
// @available   true
// @icon        square.3.layers.3d.down.right
// @iosOnly     true
// @order       43
// @tags        ar,depth,occlusion,arcore
// @addedIn     4.15.2
// @updatedIn   4.40.0
import SwiftUI

enum ArDepthOcclusionScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARDepthOcclusionDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
