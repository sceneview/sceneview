// @sceneId     animation-physics
// @title       Animation
// @subtitle    Play, pause, and control animations
// @category    basics3D
// @section     view3d
// @available   true
// @icon        figure.run
// @order       33
// @tags        animation,skeletal,physics,rigid-body,collision,gltf
// @addedIn     4.4.0
// @updatedIn   4.52.0
// The id is Android's (`animation-physics`) since the samples audit, step 0;
// `animation`, the former iOS id, is a legacy alias.
import SwiftUI

enum AnimationPhysicsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(AnimationDemo()) }
}
