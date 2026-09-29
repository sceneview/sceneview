// @sceneId     model-viewer
// @title       Models
// @subtitle    Explore a model in 3D or in your room
// @category    basics3D
// @section     view3d
// @available   true
// @icon        cube.transparent.fill
// @order       1
// @tags        gltf,glb,hdr,ibl,orbit,ar,viewer
// @updatedIn   4.35.0
import SwiftUI

enum ModelViewerScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ModelViewerDemo()) }
}
