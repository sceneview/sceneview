// @sceneId     lines-paths
// @title       Lines & Paths
// @subtitle    Polylines, helix, grids, and circles
// @category    content
// @available   true
// @icon        point.topleft.down.to.point.bottomright.curvepath
import SwiftUI

enum LinesPathsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(LinesPathsDemo()) }
}
