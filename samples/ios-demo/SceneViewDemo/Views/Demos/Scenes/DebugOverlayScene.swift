// @sceneId     debug-overlay
// @title       Debug Overlay
// @subtitle    Real-time FPS stats — sphere stress test
// @category    advanced
// @section     devTools
// @available   true
// @icon        gauge.with.needle.fill
// @order       22
// @tags        debug,fps,stats,performance,overlay
// @addedIn     4.15.2
// @updatedIn   4.50.0
import SwiftUI

enum DebugOverlayScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(DebugOverlayDemo()) }
}
