// @sceneId     ar-body-tracker
// @title       Body anchor tracking
// @subtitle    Follow a detected body anchor in real time
// @category    ar
// @available   true
// @icon        figure.walk.motion
// @iosOnly     true
// @order       38
// @tags        ar,body,pose,anchor,skeleton
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
