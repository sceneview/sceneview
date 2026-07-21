// @sceneId     point-and-ask
// @title       Point & Ask
// @subtitle    Drop 3D props, tap the scene — an on-device model (Apple Foundation Models) explains what it sees
// @category    ar
// @available   false
// @icon        brain.head.profile
// @iosOnly     true
import SwiftUI

/// Coming-soon placeholder — Android's `point-and-ask` demo (on-device
/// Gemini Nano Q&A over an AR scene, #2648) has no iOS port yet. A port
/// would use Apple's Foundation Models instead of Gemini Nano (#2804 Job B).
enum PointAndAskScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
