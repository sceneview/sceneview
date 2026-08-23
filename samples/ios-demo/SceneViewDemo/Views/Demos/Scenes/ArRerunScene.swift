// @sceneId     ar-rerun
// @title       Rerun Debug
// @subtitle    Stream camera pose and planes to the Rerun viewer
// @category    ar
// @available   true
// @icon        antenna.radiowaves.left.and.right
// @iosOnly     true
// @order       49
// @tags        ar,rerun,streaming,pose,plane,debug
import SwiftUI

enum ArRerunScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(RerunDebugDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
