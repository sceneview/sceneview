// @sceneId     geometry
// @title       Geometry Primitives
// @subtitle    Cube, sphere, cylinder, cone, plane
// @category    basics3D
// @available   true
// @icon        cube.fill
import SwiftUI

enum GeometryScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(GeometryDemo()) }
}
