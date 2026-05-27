// @sceneId     ar-image-stabilization
// @title       Image Stabilization (EIS)
// @subtitle    EIS for smoother AR camera feed
// @category    ar
// @available   false
// @icon        camera.metering.matrix
// @iosOnly     true
import SwiftUI

enum ArImageStabilizationScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
