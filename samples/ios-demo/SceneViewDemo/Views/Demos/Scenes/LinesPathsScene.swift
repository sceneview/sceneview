// @sceneId     lines-paths
// @title       Lines & Paths
// @subtitle    Follow animated curves and paths
// @category    content
// @section     create
// @available   true
// @icon        point.topleft.down.to.point.bottomright.curvepath
// @order       9
// @tags        line,polyline,path,spline,tube,curve
// @addedIn     4.0.0
// @updatedIn   4.63.0
import SwiftUI

enum LinesPathsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(LinesPathsDemo()) }
}
