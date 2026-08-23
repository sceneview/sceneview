// @sceneId     spatial-audio
// @title       Spatial Audio
// @subtitle    Positional 3D audio with distance falloff
// @category    advanced
// @available   true
// @icon        speaker.wave.3.fill
// @order       14
// @tags        audio,sound,spatial,3d-audio,orbit
import SwiftUI

enum SpatialAudioScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(SpatialAudioDemo()) }
}
