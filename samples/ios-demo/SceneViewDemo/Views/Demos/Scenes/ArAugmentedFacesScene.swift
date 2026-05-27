// @sceneId     ar-face
// @title       Augmented Faces
// @subtitle    Face mesh tracking and overlays
// @category    ar
// @available   true
// @icon        face.smiling.inverse
// @iosOnly     true
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
