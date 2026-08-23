// @sceneId     debug-overlay
// @title       Debug Overlay
// @subtitle    Real-time FPS stats — sphere stress test
// @category    advanced
// @available   true
// @icon        chart.line.uptrend.xyaxis
// @order       19
// @tags        debug,fps,stats,performance,overlay
import SwiftUI

enum DebugOverlayScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(DebugOverlayDemo()) }
}
