// @sceneId     ar-rerun
// @title       Room Scan
// @subtitle    Scan your room, then replay it in 3D
// @category    ar
// @section     devTools
// @available   true
// @icon        point.3.connected.trianglepath.dotted
// @iosOnly     true
// @order       20
// @tags        ar,rerun,replay,record,export,point cloud,plane,pose,usdz,glb,ply
// @addedIn     4.15.2
// @updatedIn   4.46.0
import SwiftUI

enum ArRerunScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(RerunShowcaseDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
