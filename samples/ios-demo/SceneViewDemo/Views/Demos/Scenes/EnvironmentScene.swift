// @sceneId     environment
// @title       HDR Environment
// @subtitle    Switch between bundled HDR environments
// @category    lighting
// @section     create
// @available   true
// @icon        sun.haze.fill
// @order       5
// @addedIn     4.15.2
import SwiftUI

enum EnvironmentScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(EnvironmentDemo()) }
}
