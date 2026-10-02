// @sceneId     text
// @title       3D Text
// @subtitle    Extruded text with styles and sizes
// @category    content
// @section     create
// @available   true
// @icon        textformat
// @order       15
// @tags        2d,text,image,video,billboard,quad,viewnode
// @addedIn     4.0.0
// @updatedIn   4.38.0
import SwiftUI

enum TextScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(TextDemo()) }
}
