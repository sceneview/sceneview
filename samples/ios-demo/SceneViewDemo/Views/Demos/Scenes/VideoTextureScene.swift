// @sceneId     video
// @title       Video Texture
// @subtitle    Video playback on a 3D surface
// @category    content
// @section     create
// @available   true
// @icon        video.fill
// @order       11
// @addedIn     4.15.2
import SwiftUI

enum VideoTextureScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(VideoTextureDemo()) }
}
