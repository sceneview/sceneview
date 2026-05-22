// @sceneId     debug-overlay
// @title       Debug Overlay
// @subtitle    Real-time FPS stats — sphere stress test
// @category    advanced
// @available   true
// @icon        chart.line.uptrend.xyaxis
import SwiftUI

enum DebugOverlayScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(DebugOverlayDemo()) }
}
