// @sceneId     model-viewer
// @title       Model Viewer
// @subtitle    Load and display 3D models
// @category    basics3D
// @available   true
// @icon        cube.transparent.fill
import SwiftUI

enum ModelViewerScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ModelViewerDemo()) }
}
