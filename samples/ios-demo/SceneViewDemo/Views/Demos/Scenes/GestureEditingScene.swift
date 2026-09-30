// @sceneId     gesture-editing
// @title       Gesture Editing
// @subtitle    Move, scale, and rotate with gestures
// @category    interaction
// @section     view3d
// @available   true
// @icon        hand.pinch.fill
// @order       36
import SwiftUI

enum GestureEditingScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(GestureEditingDemo()) }
}
