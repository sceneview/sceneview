// @sceneId     environment
// @title       HDR Environment
// @subtitle    Switch between bundled HDR environments
// @category    lighting
// @available   true
// @icon        sun.haze.fill
// @order       62
import SwiftUI

enum EnvironmentScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(EnvironmentDemo()) }
}
