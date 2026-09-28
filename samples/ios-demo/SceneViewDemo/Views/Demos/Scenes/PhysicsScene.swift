// @sceneId     physics
// @title       Physics
// @subtitle    Gravity, collisions, and rigid bodies
// @category    advanced
// @section     view3d
// @available   true
// @icon        figure.walk
// @order       5
import SwiftUI

enum PhysicsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(PhysicsDemo()) }
}
