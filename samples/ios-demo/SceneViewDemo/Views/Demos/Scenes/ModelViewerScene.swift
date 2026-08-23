// @sceneId     model-viewer
// @title       Model Viewer
// @subtitle    Load and display 3D models
// @category    basics3D
// @available   true
// @icon        cube.transparent.fill
// @order       1
// @tags        gltf,glb,hdr,ibl,orbit,ar,viewer
import SwiftUI

enum ModelViewerScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ModelViewerDemo()) }
}
