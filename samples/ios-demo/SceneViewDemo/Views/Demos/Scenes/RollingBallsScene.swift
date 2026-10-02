// @sceneId     rolling-balls
// @title       Rolling Balls
// @subtitle    Drop, tilt and knock over a tray of balls
// @category    advanced
// @section     view3d
// @available   true
// @icon        circle.hexagongrid.fill
// @order       37
// @tags        physics,rigid-body,collision,simulation,tilt,balls,pendulum,chaos,kmp
// @addedIn     4.48.0
// @updatedIn   4.51.0
import SwiftUI

/// Rolling Balls (samples audit, step 0) absorbs `double-pendulum`, now its
/// Pendulum mode: the two physics toys of the catalogue in one card.
enum RollingBallsScene: DemoScene {
    /// The card's modes, in pill order. Also read by `DemoRegistryGuardTests`, which
    /// checks every `DemoDeepLinkRegistry.aliasModes` token lands on one of them.
    @MainActor static var modes: [DemoMode] {
        [
            DemoMode("balls", title: "Balls", aliases: ["0"]) {
                RollingBallsDemo()
            },
            DemoMode("pendulum", title: "Pendulum", aliases: ["1", "double-pendulum"]) {
                DoublePendulumDemo()
            },
        ]
    }

    @MainActor static var destination: AnyView {
        AnyView(DemoModeHost(demoId: "rolling-balls", modes: modes))
    }
}
