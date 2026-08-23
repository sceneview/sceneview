// @sceneId     custom-mesh
// @title       Custom Mesh
// @subtitle    Custom vertex and index buffers
// @category    advanced
// @available   true
// @icon        diamond.fill
// @order       10
// @tags        geometry,mesh,extrusion,composite,procedural
import SwiftUI

enum CustomMeshScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(CustomMeshDemo()) }
}
