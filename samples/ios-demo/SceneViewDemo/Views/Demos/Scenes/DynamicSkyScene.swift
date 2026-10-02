// @sceneId     dynamic-sky
// @title       Dynamic Sky
// @subtitle    Time-of-day sun simulation
// @category    lighting
// @section     create
// @available   true
// @icon        sun.horizon.fill
// @order       3
// @tags        light,hdr,ibl,skybox,environment,reflection,bloom,post-fx
// @addedIn     4.0.0
// @updatedIn   4.26.0
import SwiftUI

enum DynamicSkyScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(DynamicSkyDemo()) }
}
