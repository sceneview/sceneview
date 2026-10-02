// @sceneId     lighting-lab
// @title       Lighting Lab
// @subtitle    See how light and reflections shape a model
// @category    lighting
// @section     create
// @available   true
// @icon        sun.max.fill
// @order       2
// @tags        light,hdr,ibl,skybox,environment,reflection
// @addedIn     4.51.0
import SwiftUI

enum LightingLabScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(LightingLabDemo()) }
}
