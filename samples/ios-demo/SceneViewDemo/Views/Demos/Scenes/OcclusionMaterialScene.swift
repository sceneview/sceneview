// @sceneId     occlusion-material
// @title       Occlusion Material
// @subtitle    Invisible geometry that hides objects behind it
// @category    advanced
// @section     create
// @available   true
// @icon        circle.lefthalf.filled
// @order       10
// @addedIn     4.15.2
// @updatedIn   4.35.0
import SwiftUI

enum OcclusionMaterialScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(OcclusionMaterialDemo()) }
}
