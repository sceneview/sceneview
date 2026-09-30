// @sceneId     materials
// @title       Materials
// @subtitle    Compare metal, glass, fabric and glow
// @category    advanced
// @section     create
// @available   true
// @icon        paintpalette.fill
// @order       4
// @tags        pbr,material,metallic,roughness,clearcoat,sheen,emissive
// @updatedIn   4.35.0
import SwiftUI

enum MaterialsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(MaterialsDemo()) }
}
