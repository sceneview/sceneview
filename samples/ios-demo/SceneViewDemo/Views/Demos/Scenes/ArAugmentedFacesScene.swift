// @sceneId     ar-face
// @title       Face anchor accessories
// @subtitle    Pin accessories to a tracked face anchor
// @category    ar
// @available   true
// @icon        face.smiling.inverse
// @iosOnly     true
// @order       25
// @tags        ar,face,anchor,tracking,accessories
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
