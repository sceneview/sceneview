// @sceneId     camera-gestures
// @title       Camera & Gestures
// @subtitle    Orbit, pan and look around, then move a model by hand
// @category    interaction
// @section     view3d
// @available   true
// @icon        camera.fill
// @order       41
// @tags        camera,orbit,gesture,pan,zoom,manipulator,edit,move,scale,rotate
// @addedIn     4.0.0
// @updatedIn   4.52.0
import SwiftUI

/// Camera & Gestures (samples audit, step 0): the iOS `camera-controls` and
/// `gesture-editing` cards become the one card Android ships as
/// `camera-gestures`, under the same id and title.
///
/// The modes carry no index token: Android's second mode will be PiP
/// (`secondary-camera`, step 2), so `?tab=1` must not mean Gestures here.
enum CameraGesturesScene: DemoScene {
    /// The card's modes, in pill order. Also read by `DemoRegistryGuardTests`, which
    /// checks every `DemoDeepLinkRegistry.aliasModes` token lands on one of them.
    @MainActor static var modes: [DemoMode] {
        [
            DemoMode("camera", title: "Camera", aliases: ["camera-controls"]) {
                CameraControlsDemo()
            },
            DemoMode("gestures", title: "Gestures", aliases: ["gesture-editing"]) {
                GestureEditingDemo()
            },
        ]
    }

    @MainActor static var destination: AnyView {
        AnyView(DemoModeHost(demoId: "camera-gestures", modes: modes))
    }
}
