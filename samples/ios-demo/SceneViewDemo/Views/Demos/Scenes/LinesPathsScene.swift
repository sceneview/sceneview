// @sceneId     lines-paths
// @title       Lines & Paths
// @subtitle    Polylines, helix, grids, and circles
// @category    content
// @available   true
// @icon        point.topleft.down.to.point.bottomright.curvepath
// @order       12
// @tags        line,polyline,path,helix,grid,circle
import SwiftUI

enum LinesPathsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(LinesPathsDemo()) }
}
