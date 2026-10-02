// @sceneId     ar-orbital
// @title       Orbital AR
// @subtitle    Models orbit around you in a personal solar system
// @category    ar
// @section     placeAR
// @available   true
// @icon        globe.europe.africa.fill
// @iosOnly     true
// @order       25
// @tags        ar,orbit,animation,model,anchor
// @addedIn     4.1.0
// @updatedIn   4.15.1
import SwiftUI

enum ArOrbitalScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(OrbitalARDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
