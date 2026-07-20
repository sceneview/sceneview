// @sceneId     ar-cloud-anchor
// @title       Cloud Anchors
// @subtitle    Persistent multi-user anchors
// @category    ar
// @available   false
// @icon        icloud.fill
// @iosOnly     true
import SwiftUI

enum ArCloudAnchorsScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
