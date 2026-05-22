// @sceneId     ar-plane-node
// @title       AR Plane Node
// @subtitle    Detect and visualise planes with marker cubes
// @category    ar
// @available   true
// @icon        rectangle.3.group
// @iosOnly     true
import SwiftUI

enum ArPlaneNodeScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ARPlaneNodeDemo()) }
}
