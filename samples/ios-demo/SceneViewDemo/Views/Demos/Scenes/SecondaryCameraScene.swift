// @sceneId     secondary-camera
// @title       Secondary Camera (PiP)
// @subtitle    Picture-in-picture camera view
// @category    advanced
// @available   false
// @icon        pip.fill
import SwiftUI

enum SecondaryCameraScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(EmptyView()) }
}
