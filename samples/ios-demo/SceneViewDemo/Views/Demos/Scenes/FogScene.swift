// @sceneId     fog
// @title       Fog
// @subtitle    Linear and exponential fog
// @category    lighting
// @available   true
// @icon        cloud.fog.fill
import SwiftUI

enum FogScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(FogDemo()) }
}
