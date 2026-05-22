// @sceneId     text
// @title       3D Text
// @subtitle    Extruded text with styles and sizes
// @category    content
// @available   true
// @icon        textformat
import SwiftUI

enum TextScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(TextDemo()) }
}
