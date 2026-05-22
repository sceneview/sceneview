// @sceneId     dynamic-sky
// @title       Dynamic Sky
// @subtitle    Time-of-day sun simulation
// @category    lighting
// @available   true
// @icon        sun.horizon.fill
import SwiftUI

enum DynamicSkyScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(DynamicSkyDemo()) }
}
