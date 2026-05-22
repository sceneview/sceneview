// @sceneId     ar-body-tracker
// @title       Body Tracker
// @subtitle    Real-time 91-joint skeleton tracking
// @category    ar
// @available   true
// @icon        figure.walk.motion
// @iosOnly     true
import SwiftUI

enum ArBodyTrackerScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARBodyTrackerDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
