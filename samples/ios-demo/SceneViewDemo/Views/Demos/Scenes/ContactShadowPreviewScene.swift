// @sceneId     contact-shadow-preview
// @title       Contact Shadow Preview
// @subtitle    Grounded vs floating, side by side — two identical bouncing boxes (one with the procedural contact shadow, one without) and a wall-mounted TV no shadow map can serve. Non-AR (Android: in review)
// @category    lighting
// @available   false
// @icon        circle.bottomhalf.filled
// @iosOnly     true
import SwiftUI

/// Coming-soon placeholder — Android's `contact-shadow-preview` demo (#2817,
/// contact-shadow sub-task C of #2740; Android itself is `InReview`) has no
/// iOS port yet; added to Android after iOS stopped tracking the catalog
/// (#2804 Job B).
enum ContactShadowPreviewScene: DemoScene {
    @MainActor static var destination: AnyView {
        #if os(iOS)
        return AnyView(EmptyView())
        #else
        return AnyView(EmptyView())
        #endif
    }
}
