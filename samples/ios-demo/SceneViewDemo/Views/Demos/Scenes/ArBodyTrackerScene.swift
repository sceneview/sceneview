// @sceneId     ar-body-tracker
// @title       Body anchor tracking
// @subtitle    Follow a detected body anchor in real time
// @category    ar
// @section     understand
// @available   true
// @icon        figure.arms.open
// @iosOnly     true
// @order       44
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
