// @sceneId     environment
// @title       HDR Environment
// @subtitle    Switch between bundled HDR environments
// @category    lighting
// @section     create
// @available   true
// @icon        sun.haze.fill
// @order       26
import SwiftUI

enum EnvironmentScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(EnvironmentDemo()) }
}
