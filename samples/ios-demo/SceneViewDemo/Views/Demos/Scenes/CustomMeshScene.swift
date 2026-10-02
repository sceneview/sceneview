// @sceneId     custom-mesh
// @title       Custom Mesh
// @subtitle    Custom vertex and index buffers
// @category    advanced
// @section     create
// @available   true
// @icon        hexagon.fill
// @order       8
// @tags        geometry,mesh,extrusion,composite,procedural
// @addedIn     4.0.0
// @updatedIn   4.40.0
import SwiftUI

enum CustomMeshScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(CustomMeshDemo()) }
}
