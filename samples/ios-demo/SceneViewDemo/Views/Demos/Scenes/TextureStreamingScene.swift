// @sceneId     texture-streaming
// @title       Material presets
// @subtitle    Swap PBR material presets on a loaded model, no geometry rebuild
// @category    content
// @section     create
// @available   true
// @icon        circle.dotted.and.circle
// @order       22
import SwiftUI

enum TextureStreamingScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(TextureStreamingDemo()) }
}
