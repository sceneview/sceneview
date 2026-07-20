// @sceneId     ar-rooftop
// @title       Rooftop Anchors
// @subtitle    Anchor models on geospatial rooftops
// @category    ar
// @available   false
// @icon        house.fill
// @iosOnly     true
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
