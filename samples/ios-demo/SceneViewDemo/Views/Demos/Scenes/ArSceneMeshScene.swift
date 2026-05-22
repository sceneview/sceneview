// @sceneId     ar-scene-mesh
// @title       Scene Mesh
// @subtitle    LiDAR real-time polygonal mesh reconstruction
// @category    ar
// @available   true
// @icon        grid
// @iosOnly     true
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
