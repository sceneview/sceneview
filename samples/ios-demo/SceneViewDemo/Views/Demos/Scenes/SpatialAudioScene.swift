// @sceneId     spatial-audio
// @title       Spatial Audio
// @subtitle    Positional 3D audio with distance falloff
// @category    advanced
// @section     create
// @available   true
// @icon        speaker.wave.3.fill
// @order       12
// @tags        audio,sound,spatial,3d-audio,orbit
// @addedIn     4.12.0
// @updatedIn   4.51.0
import SwiftUI

enum SpatialAudioScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(SpatialAudioDemo()) }
}
