// @sceneId     video
// @title       Video Texture
// @subtitle    Video playback on a 3D surface
// @category    content
// @available   true
// @icon        video.fill
// @order       67
import SwiftUI

enum VideoTextureScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(VideoTextureDemo()) }
}
