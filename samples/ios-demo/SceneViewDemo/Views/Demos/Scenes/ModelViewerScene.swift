// @sceneId     model-viewer
// @title       Model Viewer
// @subtitle    Load and display 3D models
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
