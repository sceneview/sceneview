// @sceneId     lighting-lab
// @title       Lighting Lab
// @subtitle    See how light and reflections shape a model
// @category    lighting
// @section     create
// @available   true
// @icon        sun.max.fill
// @order       25
// @tags        light,hdr,ibl,skybox,environment,reflection,rotation
// @updatedIn   4.35.0
import SwiftUI

enum LightingLabScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(LightingLabDemo()) }
}
