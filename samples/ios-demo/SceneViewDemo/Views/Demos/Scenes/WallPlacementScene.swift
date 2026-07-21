// @sceneId     wall-placement
// @title       Wall Placement
// @subtitle    Mount a TV on a wall — floor↔wall edge alignment, Amazon AR-View style (Android: in review)
// @category    ar
// @available   false
// @icon        tv.fill
// @iosOnly     true
import SwiftUI

/// Coming-soon placeholder — Android's `wall-placement` demo (#2740; Android
/// itself is `InReview`) has no iOS port yet; added to Android after iOS
/// stopped tracking the catalog (#2804 Job B).
enum WallPlacementScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
