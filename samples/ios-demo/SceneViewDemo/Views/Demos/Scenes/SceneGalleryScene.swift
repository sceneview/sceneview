// @sceneId     scene-gallery
// @title       Scene Gallery
// @subtitle    Themed Sketchfab bundles streamed on demand
// @category    basics3D
// @available   true
// @icon        square.grid.3x3.fill
import SwiftUI

enum SceneGalleryScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(SceneGalleryDemo()) }
}
