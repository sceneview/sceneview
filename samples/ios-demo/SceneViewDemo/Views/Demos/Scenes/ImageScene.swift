// @sceneId     image
// @title       Image Planes
// @subtitle    Image planes in 3D space
// @category    content
// @section     create
// @available   true
// @icon        photo.fill
// @order       35
import SwiftUI

enum ImageScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ImageDemo()) }
}
