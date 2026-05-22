// @sceneId     image
// @title       Image Planes
// @subtitle    Image planes in 3D space
// @category    content
// @available   true
// @icon        photo.fill
import SwiftUI

enum ImageScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ImageDemo()) }
}
