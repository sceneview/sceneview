// @sceneId     texture-streaming
// @title       Texture Streaming
// @subtitle    Swap PBR materials at runtime without rebuilding geometry
// @category    content
// @available   true
// @icon        circle.dotted.and.circle
import SwiftUI

enum TextureStreamingScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(TextureStreamingDemo()) }
}
