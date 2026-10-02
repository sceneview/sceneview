// @sceneId     ar-lighting
// @title       AR Lighting
// @subtitle    Key and fill light presets on one model
// @category    ar
// @section     placeAR
// @available   true
// @icon        lightbulb.max.fill
// @iosOnly     true
// @order       26
// @addedIn     4.3.4
// @updatedIn   4.15.1
import SwiftUI

enum ArLightingScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARLightingDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
