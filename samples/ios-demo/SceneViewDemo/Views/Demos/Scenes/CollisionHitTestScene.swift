// @sceneId     collision
// @title       Collision & Hit Test
// @subtitle    Hit testing and collision detection
// @category    interaction
// @available   true
// @icon        capsule.fill
import SwiftUI

enum CollisionHitTestScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(CollisionHitTestDemo()) }
}
