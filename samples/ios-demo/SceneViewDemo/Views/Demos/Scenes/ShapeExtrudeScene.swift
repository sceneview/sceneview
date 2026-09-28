// @sceneId     shape
// @title       Shape Extrude
// @subtitle    Extrude 2D polygons into 3D meshes
// @category    advanced
// @section     create
// @available   true
// @icon        scribble.variable
// @order       31
import SwiftUI

enum ShapeExtrudeScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ShapeExtrudeDemo()) }
}
