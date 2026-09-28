// @sceneId     movable-light
// @title       Movable Light
// @subtitle    Drag to orbit the light around the model
// @category    lighting
// @section     create
// @available   true
// @icon        sun.dust.fill
// @order       27
import SwiftUI

enum MovableLightScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(MovableLightDemo()) }
}
