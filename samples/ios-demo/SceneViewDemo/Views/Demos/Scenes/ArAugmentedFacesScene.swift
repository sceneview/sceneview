// @sceneId     ar-face
// @title       Face anchor accessories
// @subtitle    Pin accessories to a tracked face anchor
// @category    ar
// @section     understand
// @available   true
// @icon        face.smiling.inverse
// @iosOnly     true
// @order       42
// @tags        ar,face,anchor,tracking,accessories
// @addedIn     4.15.2
// @updatedIn   4.45.0
import SwiftUI

enum ArAugmentedFacesScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARAugmentedFacesDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
