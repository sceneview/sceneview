// @sceneId     ar-scene-mesh
// @title       Scene Mesh
// @subtitle    LiDAR real-time polygonal mesh reconstruction
// @category    ar
// @section     understand
// @available   true
// @icon        grid
// @iosOnly     true
// @order       47
// @tags        ar,geospatial,streetscape,mesh,terrain,building
// @updatedIn   4.35.0
import SwiftUI

enum ArSceneMeshScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARSceneMeshDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
