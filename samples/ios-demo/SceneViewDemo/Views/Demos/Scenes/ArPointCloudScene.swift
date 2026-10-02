// @sceneId     ar-point-cloud
// @title       AR Point Cloud
// @subtitle    Live ARKit tracking feature points
// @category    ar
// @section     understand
// @available   true
// @icon        aqi.medium
// @iosOnly     true
// @order       47
// @tags        ar,point-cloud,feature-points,tracking
// @addedIn     4.15.2
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
