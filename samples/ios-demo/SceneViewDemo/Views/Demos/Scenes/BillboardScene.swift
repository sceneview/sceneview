// @sceneId     billboard
// @title       Billboard
// @subtitle    Labels that face the camera
// @category    content
// @available   true
// @icon        person.fill.viewfinder
import SwiftUI

enum BillboardScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(BillboardDemo()) }
}
