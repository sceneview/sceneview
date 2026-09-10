// @sceneId     lighting
// @title       Lighting
// @subtitle    Three rigs on one stage: HDR, studio, sun
// @category    lighting
// @available   true
// @icon        lightbulb.fill
// @order       2
// @tags        light,ibl,environment,studio,key,fill,rim,sun,shadow,chrome,pbr
import SwiftUI

// Title and subtitle mirror Android's `demo_lighting_title` /
// `demo_lighting_subtitle` (`strings_demo_lighting.xml`) since #3496 rebuilt
// that screen from "pick a LightManager.Type" into three rigs. The iOS screen
// followed in #3587; the file name is unchanged only to keep the diff on the
// demo itself.
enum LightTypesScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(LightingDemo()) }
}
