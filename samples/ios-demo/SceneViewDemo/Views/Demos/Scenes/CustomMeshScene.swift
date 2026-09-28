// @sceneId     custom-mesh
// @title       Custom Mesh
// @subtitle    Custom vertex and index buffers
// @category    advanced
// @section     create
// @available   true
// @icon        diamond.fill
// @order       30
// @tags        geometry,mesh,extrusion,composite,procedural
import SwiftUI

enum CustomMeshScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(CustomMeshDemo()) }
}
