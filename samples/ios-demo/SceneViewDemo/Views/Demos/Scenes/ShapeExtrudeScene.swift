// @sceneId     shape
// @title       Shape Extrude
// @subtitle    Extrude 2D polygons into 3D meshes
// @category    advanced
// @section     create
// @available   true
// @icon        scribble.variable
// @order       16
// @addedIn     4.15.2
// @updatedIn   4.40.0
import SwiftUI

enum ShapeExtrudeScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ShapeExtrudeDemo()) }
}
