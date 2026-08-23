// @sceneId     shape
// @title       Shape Extrude
// @subtitle    Extrude 2D polygons into 3D meshes
// @category    advanced
// @available   true
// @icon        scribble.variable
// @order       72
import SwiftUI

enum ShapeExtrudeScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ShapeExtrudeDemo()) }
}
