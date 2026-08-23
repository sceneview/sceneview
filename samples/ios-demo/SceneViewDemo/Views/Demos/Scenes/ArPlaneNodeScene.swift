// @sceneId     ar-plane-node
// @title       AR Plane Node
// @subtitle    Detect and visualise planes with marker cubes
// @category    ar
// @available   true
// @icon        rectangle.3.group
// @iosOnly     true
// @order       28
// @tags        ar,plane,planenode,lifecycle,callback
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
