// @sceneId     scene-gallery
// @title       Scene Gallery
// @subtitle    Themed Sketchfab bundles streamed on demand
// @category    basics3D
// @section     view3d
// @available   true
// @icon        square.grid.3x3.fill
// @order       35
// @addedIn     4.0.0
// @updatedIn   4.32.0
import SwiftUI

enum SceneGalleryScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(SceneGalleryDemo()) }
}
