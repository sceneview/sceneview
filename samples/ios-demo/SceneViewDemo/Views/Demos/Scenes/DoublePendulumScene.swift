// @sceneId     double-pendulum
// @title       Double Pendulum
// @subtitle    Chaotic two-link physics, shared KMP simulation
// @category    advanced
// @section     view3d
// @available   true
// @icon        waveform.path
// @order       38
// @tags        physics,pendulum,chaos,simulation,kmp
// @addedIn     4.4.0
// @updatedIn   4.10.0
import SwiftUI

enum DoublePendulumScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(DoublePendulumDemo()) }
}
