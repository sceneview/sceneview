// @sceneId     lighting
// @title       Light Types
// @subtitle    Directional, point, and spot lights
// @category    lighting
// @available   true
// @icon        lightbulb.fill
import SwiftUI

enum LightTypesScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(LightingDemo()) }
}
