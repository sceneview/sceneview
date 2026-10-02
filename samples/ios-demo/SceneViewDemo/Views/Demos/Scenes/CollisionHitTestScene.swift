// @sceneId     collision
// @title       Collision & Hit Test
// @subtitle    Hit testing and collision detection
// @category    interaction
// @section     view3d
// @available   true
// @icon        hand.tap.fill
// @order       39
// @tags        picking,hit-test,collision,ray,viewnode,overlay
// @addedIn     4.15.2
// @updatedIn   4.40.0
import SwiftUI

enum CollisionHitTestScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(CollisionHitTestDemo()) }
}
