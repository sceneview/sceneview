// @sceneId     movable-light
// @title       Movable Light
// @subtitle    Drag to orbit the light around the model
// @category    lighting
// @section     create
// @available   true
// @icon        sun.dust.fill
// @order       17
// @addedIn     4.1.0
// @updatedIn   4.3.2
import SwiftUI

enum MovableLightScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(MovableLightDemo()) }
}
