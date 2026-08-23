// @sceneId     ar-face
// @title       Augmented Faces
// @subtitle    Face mesh tracking and overlays
// @category    ar
// @available   true
// @icon        face.smiling.inverse
// @iosOnly     true
// @order       25
// @tags        ar,face,mesh,tracking,augmented-faces
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
