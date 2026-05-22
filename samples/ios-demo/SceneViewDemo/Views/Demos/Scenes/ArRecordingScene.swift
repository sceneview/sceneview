// @sceneId     ar-recording
// @title       AR Recording
// @subtitle    Capture the AR session as a screen video (record-only on iOS)
// @category    ar
// @available   true
// @icon        record.circle
// @iosOnly     true
import SwiftUI

enum ArRecordingScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARRecorderDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
