// @sceneId     ar-lighting
// @title       AR Lighting
// @subtitle    Key and fill light presets on one model
// @category    ar
// @available   true
// @icon        lightbulb.max.fill
// @iosOnly     true
// @order       73
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
