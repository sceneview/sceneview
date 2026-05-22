// @sceneId     physics
// @title       Physics
// @subtitle    Gravity, collisions, and rigid bodies
// @category    advanced
// @available   true
// @icon        figure.walk
import SwiftUI

enum PhysicsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(PhysicsDemo()) }
}
