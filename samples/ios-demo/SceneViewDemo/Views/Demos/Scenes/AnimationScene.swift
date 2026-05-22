// @sceneId     animation
// @title       Animation
// @subtitle    Play, pause, and control animations
// @category    basics3D
// @available   true
// @icon        figure.run
import SwiftUI

enum AnimationScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(AnimationDemo()) }
}
