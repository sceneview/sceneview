// @sceneId     ar-depth-occlusion
// @title       Depth Occlusion
// @subtitle    Real-world depth masks virtual objects
// @category    ar
// @section     understand
// @available   true
// @icon        square.3.layers.3d.down.right
// @iosOnly     true
// @order       41
// @tags        ar,depth,occlusion,arcore
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
