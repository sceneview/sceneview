// @sceneId     geometry
// @title       Geometry Primitives
// @subtitle    Cube, sphere, cylinder, cone, plane
// @category    basics3D
// @section     create
// @available   true
// @icon        cube.fill
// @order       29
// @tags        geometry,cube,sphere,cylinder,plane,primitive
import SwiftUI

enum GeometryScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(GeometryDemo()) }
}
