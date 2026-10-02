// @sceneId     lines-paths
// @title       Lines & Paths
// @subtitle    Polylines, helix, grids, and circles
// @category    content
// @section     create
// @available   true
// @icon        point.topleft.down.to.point.bottomright.curvepath
// @order       9
// @tags        line,polyline,path,helix,grid,circle
// @addedIn     4.0.0
import SwiftUI

enum LinesPathsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(LinesPathsDemo()) }
}
