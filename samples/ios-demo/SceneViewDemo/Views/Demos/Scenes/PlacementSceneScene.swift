// @sceneId     placement-scene
// @title       One-Call AR
// @subtitle    The one-call automatic placement bundle
// @category    ar
// @available   true
// @icon        mappin.and.ellipse
// @iosOnly     true
// @status      working
// @order       21
// @tags        ar,plane,automatic-placement,anchor
import SwiftUI

/// Additive AutoPlacementScene showcase. Legacy PlacementScene remains manual.
enum PlacementSceneScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARPlacementExperience())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
