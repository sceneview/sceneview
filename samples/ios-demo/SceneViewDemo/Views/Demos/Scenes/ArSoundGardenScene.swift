// @sceneId     ar-sound-garden
// @title       Sound Garden
// @subtitle    Four glowing orbs play one song. Walk between them to remix it
// @category    ar
// @section     placeAR
// @available   true
// @icon        headphones
// @iosOnly     true
// @order       17
// @tags        ar,audio,spatial audio,binaural,headphones,music,anchor,plane
// @sinceVersion 4.51.0
import SwiftUI

enum ArSoundGardenScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARSoundGardenDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
