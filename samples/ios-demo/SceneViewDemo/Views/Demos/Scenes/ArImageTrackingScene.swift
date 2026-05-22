// @sceneId     ar-image
// @title       Image Tracking
// @subtitle    Detect and track reference images
// @category    ar
// @available   true
// @icon        viewfinder.circle.fill
// @iosOnly     true
import SwiftUI

enum ArImageTrackingScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(ARImageTrackingDemo()) }
}
