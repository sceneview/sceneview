// @sceneId     camera-controls
// @title       Camera Controls
// @subtitle    Orbit, pan, look-around, and native Apple modes
// @category    interaction
// @section     view3d
// @available   true
// @icon        camera.fill
// @order       41
// @tags        camera,orbit,gesture,pan,zoom,manipulator,edit
// @addedIn     4.0.0
// @updatedIn   4.31.0
import SwiftUI

enum CameraControlsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(CameraControlsDemo()) }
}
