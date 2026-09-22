// @sceneId     wall-placement
// @title       Wall Placement
// @subtitle    Place a TV automatically on the first usable wall
// @category    ar
// @available   true
// @icon        tv.fill
// @iosOnly     true
// @status      inReview
// @order       51
// @tags        ar,wall,vertical-plane,placement,tv
import SwiftUI

/// Uses the shared automatic-placement experience with vertical-only detection.
/// No floor prerequisite; all gestures and recovery remain SDK-owned.
enum WallPlacementScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARPlacementExperience(wallTV: true))
        #else
        return AnyView(EmptyView())
        #endif
    }
}
