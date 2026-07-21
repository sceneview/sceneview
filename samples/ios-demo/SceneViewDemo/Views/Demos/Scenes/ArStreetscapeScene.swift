// @sceneId     ar-streetscape
// @title       Streetscape Geometry
// @subtitle    Geospatial building and terrain meshes
// @category    ar
// @available   false
// @icon        map.fill
// @iosOnly     true
// @androidOnlyReason  ARCore Streetscape Geometry (Geospatial/VPS) is a Google-backend service with no ARKit equivalent — not planned for iOS.
import SwiftUI

enum ArStreetscapeScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
