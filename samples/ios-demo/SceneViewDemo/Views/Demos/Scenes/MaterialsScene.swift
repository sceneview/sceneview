// @sceneId     materials
// @title       PBR Materials
// @subtitle    PBR metallic and roughness spectrum
// @category    advanced
// @section     create
// @available   true
// @icon        paintpalette.fill
// @order       21
// @tags        pbr,material,clearcoat,sheen,transmission,occlusion,streaming
// @updatedIn   4.35.0
import SwiftUI

enum MaterialsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(MaterialsDemo()) }
}
