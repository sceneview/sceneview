// @sceneId     lighting
// @title       Lighting
// @subtitle    Studio light, daylight and reflections
// @category    lighting
// @section     create
// @available   true
// @icon        lightbulb.fill
// @order       6
// @tags        light,ibl,environment,studio,key,fill,rim,sun,shadow,chrome,pbr,hdr,skybox,reflection,probe
// @addedIn     4.0.0
// @updatedIn   4.35.0
import SwiftUI

// Title and subtitle mirror Android's `demo_lighting_title` /
// `demo_lighting_subtitle` (`strings_demo_lighting.xml`) since #3496 rebuilt
// that screen from "pick a LightManager.Type" into three rigs. The iOS screen
// followed in #3587; the file name is unchanged only to keep the diff on the
// demo itself. Since #4603 it also carries what the separate Lighting Lab card
// did (environment intensity, sky, reflection probe), so that card is gone and
// its id deep-links here. Android's subtitle adds fog and shading; RealityKit has neither.
enum LightTypesScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(LightingDemo()) }
}
