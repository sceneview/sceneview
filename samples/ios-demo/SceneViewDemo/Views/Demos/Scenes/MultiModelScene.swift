// @sceneId     multi-model
// @title       Park Scene
// @subtitle    Multiple models in one scene
// @category    basics3D
// @section     view3d
// @available   true
// @icon        tree.fill
// @order       35
// @addedIn     4.4.0
// @updatedIn   4.32.0
import SwiftUI

enum MultiModelScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(MultiModelDemo()) }
}
