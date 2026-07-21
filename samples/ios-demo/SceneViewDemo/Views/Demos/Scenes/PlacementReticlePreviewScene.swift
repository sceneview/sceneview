// @sceneId     placement-reticle-preview
// @title       AR Placement Reticle Preview
// @subtitle    Non-AR preview of AR placement — reticle (searching/ready, ring/disc) and a placed model with a contact shadow
// @category    ar
// @available   false
// @icon        scope
// @iosOnly     true
import SwiftUI

/// Coming-soon placeholder — Android's `placement-reticle-preview` demo has
/// no iOS port yet; added to Android after iOS stopped tracking the catalog
/// (#2804 Job B).
enum PlacementReticlePreviewScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
