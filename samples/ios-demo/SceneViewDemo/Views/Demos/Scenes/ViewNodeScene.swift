// @sceneId     view-node
// @title       ViewNode
// @subtitle    Native UI embedded in 3D space
// @category    interaction
// @available   false
// @icon        rectangle.stack.fill
import SwiftUI

enum ViewNodeScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(EmptyView()) }
}
