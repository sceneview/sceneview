// @sceneId     ar-orbital
// @title       Orbital AR
// @subtitle    Models orbit around you in a personal solar system
// @category    ar
// @section     placeAR
// @available   true
// @icon        globe.europe.africa.fill
// @iosOnly     true
// @order       24
// @tags        ar,orbit,animation,model,anchor
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
