// @sceneId     ar-orbital
// @title       Orbital AR
// @subtitle    Models orbit around you in a personal solar system
// @category    ar
// @available   true
// @icon        circle.dotted
// @iosOnly     true
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
