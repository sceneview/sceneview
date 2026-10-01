// @sceneId     rolling-balls
// @title       Rolling Balls
// @subtitle    Drop, tilt and knock over a tray of balls
// @category    advanced
// @section     view3d
// @available   true
// @icon        circle.hexagongrid.fill
// @order       36
// @tags        physics,rigid-body,collision,simulation,tilt,balls
// @sinceVersion 4.48.0
import SwiftUI

enum RollingBallsScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(RollingBallsDemo()) }
}
