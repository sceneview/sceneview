// @sceneId     ar-record-playback
// @title       AR Recording
// @subtitle    Capture the AR session as a screen video (record-only on iOS)
// @category    ar
// @section     devTools
// @available   true
// @icon        record.circle
// @iosOnly     true
// @order       21
// @tags        ar,recording,playback,session,mp4,replay
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
