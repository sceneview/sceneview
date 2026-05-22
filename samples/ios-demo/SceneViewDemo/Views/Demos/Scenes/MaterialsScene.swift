// @sceneId     materials
// @title       PBR Materials
// @subtitle    PBR metallic and roughness spectrum
// @category    advanced
// @available   true
// @icon        paintpalette.fill
import SwiftUI

enum MaterialsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(MaterialsDemo()) }
}
