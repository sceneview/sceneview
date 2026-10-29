// @sceneId     camera-path
// @title       Camera Path
// @subtitle    Play, pause and scrub a keyframed camera orbit
// @category    basics3D
// @section     view3d
// @available   true
// @icon        video.fill
// @order       31
// @tags        camera,keyframes,path,orbit,cinematic,timeline,travelling,dolly
// @addedIn     4.63.0
import SwiftUI

enum CameraPathScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(CameraPathDemo()) }
}
