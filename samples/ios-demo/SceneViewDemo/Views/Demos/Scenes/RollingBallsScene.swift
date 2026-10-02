// @sceneId     rolling-balls
// @title       Rolling Balls
// @subtitle    Drop, tilt and knock over a tray of balls
// @category    advanced
// @section     view3d
// @available   true
// @icon        circle.hexagongrid.fill
// @order       37
// @tags        physics,rigid-body,collision,simulation,tilt,balls
// @addedIn     4.48.0
// @updatedIn   4.51.0
import SwiftUI

enum RollingBallsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(RollingBallsDemo()) }
}
