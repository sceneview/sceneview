// @sceneId     ar-point-cloud
// @title       AR Point Cloud
// @subtitle    Live ARKit tracking feature points
// @category    ar
// @available   true
// @icon        camera.metering.spot
// @iosOnly     true
import SwiftUI

enum ArPointCloudScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARPointCloudDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
