// @sceneId     image
// @title       Image Planes
// @subtitle    Image planes in 3D space
// @category    content
// @section     create
// @available   true
// @icon        photo.fill
// @order       13
// @addedIn     4.0.0
// @updatedIn   4.49.0
import SwiftUI

enum ImageScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ImageDemo()) }
}
