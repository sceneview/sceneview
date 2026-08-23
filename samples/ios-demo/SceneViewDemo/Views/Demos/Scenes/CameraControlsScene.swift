// @sceneId     camera-controls
// @title       Camera Controls
// @subtitle    Orbit, pan, look-around, and native Apple modes
// @category    interaction
// @available   true
// @icon        camera.fill
// @order       8
// @tags        camera,orbit,gesture,pan,zoom,manipulator,edit
import SwiftUI

enum CameraControlsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(CameraControlsDemo()) }
}
