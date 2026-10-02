// @sceneId     ar-image
// @title       Image Tracking
// @subtitle    Detect and track reference images
// @category    ar
// @section     understand
// @available   true
// @icon        photo.artframe
// @iosOnly     true
// @order       46
// @tags        ar,image,tracking,augmented-image,marker
// @addedIn     4.15.2
import SwiftUI

enum ArImageTrackingScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(ARImageTrackingDemo())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
