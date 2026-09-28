// @sceneId     collision
// @title       Collision & Hit Test
// @subtitle    Hit testing and collision detection
// @category    interaction
// @section     view3d
// @available   true
// @icon        capsule.fill
// @order       8
// @tags        picking,hit-test,collision,ray,viewnode,overlay
import SwiftUI

enum CollisionHitTestScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(CollisionHitTestDemo()) }
}
