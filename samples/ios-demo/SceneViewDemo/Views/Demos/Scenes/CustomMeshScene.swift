// @sceneId     custom-mesh
// @title       Custom Mesh
// @subtitle    Custom vertex and index buffers
// @category    advanced
// @available   true
// @icon        diamond.fill
import SwiftUI

enum CustomMeshScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(CustomMeshDemo()) }
}
