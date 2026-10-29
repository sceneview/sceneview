// @sceneId     debug-overlay
// @title       Performance Test
// @subtitle    Find the node budget for smooth 3D rendering on your device
// @category    advanced
// @section     devTools
// @available   true
// @icon        gauge.with.needle.fill
// @order       23
// @tags        debug,fps,stats,performance,overlay
// @addedIn     4.15.2
// @updatedIn   4.63.0
import SwiftUI

enum DebugOverlayScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(DebugOverlayDemo()) }
}
