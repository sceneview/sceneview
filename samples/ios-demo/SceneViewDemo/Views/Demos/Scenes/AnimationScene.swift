// @sceneId     animation
// @title       Animation
// @subtitle    Play, pause, and control animations
// @category    basics3D
// @available   true
// @icon        figure.run
// @order       4
// @tags        animation,skeletal,physics,rigid-body,collision,gltf
import SwiftUI

enum AnimationScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(AnimationDemo()) }
}
