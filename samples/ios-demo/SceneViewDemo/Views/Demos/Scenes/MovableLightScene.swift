// @sceneId     movable-light
// @title       Movable Light
// @subtitle    Drag to orbit the light around the model
// @category    lighting
// @available   true
// @icon        sun.dust.fill
import SwiftUI

enum MovableLightScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(MovableLightDemo()) }
}
