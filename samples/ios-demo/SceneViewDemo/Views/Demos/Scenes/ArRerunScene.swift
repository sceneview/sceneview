// @sceneId     ar-rerun
// @title       Rerun Debug
// @subtitle    Stream camera pose and planes to the Rerun viewer
// @category    ar
// @available   true
// @icon        antenna.radiowaves.left.and.right
// @iosOnly     true
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
