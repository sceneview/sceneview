// @sceneId     animation
// @title       Animation
// @subtitle    Play, pause, and control animations
// @category    basics3D
// @section     view3d
// @available   true
// @icon        figure.run
// @order       31
// @tags        animation,skeletal,physics,rigid-body,collision,gltf
// @updatedIn   4.48.0
import SwiftUI

enum AnimationScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(AnimationDemo()) }
}
