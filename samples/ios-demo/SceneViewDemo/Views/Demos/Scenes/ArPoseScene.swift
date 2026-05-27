// @sceneId     ar-pose
// @title       Pose Placement
// @subtitle    Free pose positioning
// @category    ar
// @available   false
// @icon        move.3d
// @iosOnly     true
import SwiftUI

enum ArPoseScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
