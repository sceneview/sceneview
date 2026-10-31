// @sceneId     geometry
// @title       Geometry Primitives
// @subtitle    Show, hide and restyle the seven built-in shapes
// @category    basics3D
// @section     create
// @available   true
// @icon        cube.fill
// @order       19
// @tags        geometry,cube,sphere,cylinder,cone,torus,capsule,plane,primitive,wireframe
// @addedIn     4.0.0
// @updatedIn   4.63.0
import SwiftUI

enum GeometryScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(GeometryDemo()) }
}
