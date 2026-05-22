// @sceneId     spatial-audio
// @title       Spatial Audio
// @subtitle    Positional 3D audio with distance falloff
// @category    advanced
// @available   true
// @icon        speaker.wave.3.fill
import SwiftUI

enum SpatialAudioScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(SpatialAudioDemo()) }
}
