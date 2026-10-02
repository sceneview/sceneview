// @sceneId     geometry
// @title       Geometry Primitives
// @subtitle    Cube, sphere, cylinder, cone, plane
// @category    basics3D
// @section     create
// @available   true
// @icon        cube.fill
// @order       19
// @tags        geometry,cube,sphere,cylinder,plane,primitive
// @addedIn     4.0.0
// @updatedIn   4.40.0
import SwiftUI

enum GeometryScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(GeometryDemo()) }
}
