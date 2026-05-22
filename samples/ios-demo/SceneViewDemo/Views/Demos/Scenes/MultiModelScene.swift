// @sceneId     multi-model
// @title       Multi-Model Scene
// @subtitle    Multiple models in one scene
// @category    basics3D
// @available   true
// @icon        tree.fill
import SwiftUI

enum MultiModelScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(MultiModelDemo()) }
}
