// @sceneId     secondary-camera
// @title       Secondary Camera (PiP)
// @subtitle    Add an independent camera inset to a 3D viewer
// @category    advanced
// @section     devTools
// @available   true
// @icon        pip
// @order       22
// @tags        camera,pip,multi-view,render-target
// @addedIn     4.52.0
import SwiftUI

enum SecondaryCameraScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(SecondaryCameraDemo()) }
}
