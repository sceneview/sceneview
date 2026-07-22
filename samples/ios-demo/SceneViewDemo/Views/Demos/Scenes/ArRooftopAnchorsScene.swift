// @sceneId     ar-rooftop
// @title       Rooftop Anchors
// @subtitle    Anchor models on geospatial rooftops
// @category    ar
// @available   false
// @icon        house.fill
// @iosOnly     true
// @androidOnlyReason  ARCore Geospatial rooftop anchors are a Google-backend service with no ARKit equivalent — not planned for iOS.
import SwiftUI

enum ArRooftopAnchorsScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
