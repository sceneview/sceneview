// @sceneId     ar-terrain-anchors
// @title       Terrain Anchors
// @subtitle    Anchor models on geospatial terrain
// @category    ar
// @available   false
// @icon        mountain.2.fill
// @iosOnly     true
import SwiftUI

enum ArTerrainAnchorsScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
