// @sceneId     ar-xr-face
// @title       Face Tracking (visionOS)
// @subtitle    Face mesh on visionOS headsets
// @category    ar
// @available   false
// @icon        visionpro
import SwiftUI

/// Coming-soon placeholder — Android's `ar-xr-face` demo targets Android XR
/// headsets (Jetpack XR); Android itself is `ComingSoon` there too. The
/// iOS/Apple equivalent would be visionOS face-tracking, not yet
/// implemented — hence no `@iosOnly` guard (unlike the phone-camera AR
/// placeholders): this is a roadmap card for visionOS specifically, so it
/// stays visible on every Apple platform build rather than hiding on the one
/// platform it actually targets. Was a hand-maintained residual id in
/// `DemoDeepLinkRegistry` before this Scene file existed (#2804 Job C).
enum ArXrFaceScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(EmptyView()) }
}
