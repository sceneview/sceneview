// @sceneId     ar-placement
// @title       Tap to Place
// @subtitle    Tap a detected plane to place a model
// @category    ar
// @available   true
// @icon        arkit
// @iosOnly     true
import SwiftUI

enum ArPlacementScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARPlacementDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
