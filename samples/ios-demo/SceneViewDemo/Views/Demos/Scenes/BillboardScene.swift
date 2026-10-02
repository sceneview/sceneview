// @sceneId     billboard
// @title       Billboard
// @subtitle    Labels that face the camera
// @category    content
// @section     create
// @available   true
// @icon        signpost.right.fill
// @order       14
// @addedIn     4.0.0
// @updatedIn   4.38.0
import SwiftUI

enum BillboardScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(BillboardDemo()) }
}
