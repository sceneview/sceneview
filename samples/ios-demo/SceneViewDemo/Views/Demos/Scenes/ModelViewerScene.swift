// @sceneId     model-viewer
// @title       Models
// @subtitle    Explore a model in 3D or in your room
// @category    basics3D
// @section     view3d
// @available   true
// @icon        rotate.3d
// @order       33
// @tags        gltf,glb,hdr,ibl,orbit,ar,viewer
// @addedIn     4.4.0
// @updatedIn   4.49.0
import SwiftUI

enum ModelViewerScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ModelViewerDemo()) }
}
