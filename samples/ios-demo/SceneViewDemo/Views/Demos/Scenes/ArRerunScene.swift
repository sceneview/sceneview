// @sceneId     ar-rerun
// @title       Rerun AR Replay
// @subtitle    Watch a real AR session rebuild itself in 3D
// @category    ar
// @section     devTools
// @available   true
// @icon        point.3.connected.trianglepath.dotted
// @iosOnly     true
// @order       20
// @tags        ar,rerun,replay,record,export,point cloud,plane,pose,usdz,glb,ply
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
