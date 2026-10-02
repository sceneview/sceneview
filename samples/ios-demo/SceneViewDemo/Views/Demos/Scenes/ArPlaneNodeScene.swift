// @sceneId     ar-plane-node
// @title       AR Plane Node
// @subtitle    Detect and visualise planes with marker cubes
// @category    ar
// @section     placeAR
// @available   true
// @icon        rectangle.3.group
// @iosOnly     true
// @order       30
// @tags        ar,plane,planenode,lifecycle,callback
// @addedIn     4.15.2
// @updatedIn   4.15.3
import SwiftUI

enum ArPlaneNodeScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARPlaneNodeDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
