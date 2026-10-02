// @sceneId     splat-preview
// @title       Real-World Scan
// @subtitle    A tree stump and its raccoons, scanned with a phone
// @category    content
// @section     view3d
// @available   true
// @icon        camera.metering.matrix
// @order       31
// @tags        splat,point-cloud,scan,spz
// @addedIn     4.25.0
import SwiftUI

enum SplatPreviewScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(SplatPreviewDemo()) }
}
