// @sceneId     debug-overlay
// @title       Debug Overlay
// @subtitle    Real-time FPS stats — sphere stress test
// @category    advanced
// @section     devTools
// @available   true
// @icon        chart.line.uptrend.xyaxis
// @order       20
// @tags        debug,fps,stats,performance,overlay
import SwiftUI

enum DebugOverlayScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(DebugOverlayDemo()) }
}
