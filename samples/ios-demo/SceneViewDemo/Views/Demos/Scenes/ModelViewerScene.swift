// @sceneId     model-viewer
// @title       Models
// @subtitle    Explore a model in 3D or in your room
// @category    basics3D
// @section     view3d
// @available   true
// @icon        rotate.3d
// @order       32
// @tags        gltf,glb,hdr,ibl,orbit,ar,viewer
// @updatedIn   4.35.0
import SwiftUI

enum ModelViewerScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ModelViewerDemo()) }
}
