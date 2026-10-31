// @sceneId     custom-mesh
// @title       Custom Geometry
// @subtitle    A knot generated vertex by vertex
// @category    advanced
// @section     create
// @available   true
// @icon        hexagon.fill
// @order       8
// @tags        geometry,mesh,procedural,torus-knot,wireframe,vertices
// @addedIn     4.0.0
// @updatedIn   4.63.0
import SwiftUI

enum CustomMeshScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(CustomMeshDemo()) }
}
