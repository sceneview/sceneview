// @sceneId     camera-controls
// @title       Camera Controls
// @subtitle    Orbit, pan, look-around, and native Apple modes
// @category    interaction
// @section     view3d
// @available   true
// @icon        camera.fill
// @order       6
// @tags        camera,orbit,gesture,pan,zoom,manipulator,edit
// @updatedIn   4.35.0
import SwiftUI

enum CameraControlsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(CameraControlsDemo()) }
}
