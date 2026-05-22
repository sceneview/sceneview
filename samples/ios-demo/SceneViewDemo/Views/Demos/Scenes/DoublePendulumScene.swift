// @sceneId     double-pendulum
// @title       Double Pendulum
// @subtitle    Chaotic two-link physics, shared KMP simulation
// @category    advanced
// @available   true
// @icon        waveform.path
import SwiftUI

enum DoublePendulumScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(DoublePendulumDemo()) }
}
