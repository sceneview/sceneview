// @sceneId     dynamic-sky
// @title       Dynamic Sky
// @subtitle    Time-of-day sun simulation
// @category    lighting
// @available   true
// @icon        sun.horizon.fill
// @order       7
// @tags        light,hdr,ibl,skybox,environment,reflection,bloom,post-fx
import SwiftUI

enum DynamicSkyScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(DynamicSkyDemo()) }
}
