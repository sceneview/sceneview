// @sceneId     occlusion-material
// @title       Occlusion Material
// @subtitle    Invisible geometry that hides objects behind it
// @category    advanced
// @available   true
// @icon        circle.lefthalf.filled
// @order       69
import SwiftUI

enum OcclusionMaterialScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(OcclusionMaterialDemo()) }
}
