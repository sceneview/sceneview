// @sceneId     multi-model
// @title       Multi-Model Scene
// @subtitle    Multiple models in one scene
// @category    basics3D
// @section     view3d
// @available   true
// @icon        tree.fill
// @order       2
import SwiftUI

enum MultiModelScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(MultiModelDemo()) }
}
