// @sceneId     geometry
// @title       Geometry Primitives
// @subtitle    Cube, sphere, cylinder, cone, plane
// @category    basics3D
// @available   true
// @icon        cube.fill
// @order       5
// @tags        geometry,cube,sphere,cylinder,plane,primitive
import SwiftUI

enum GeometryScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(GeometryDemo()) }
}
