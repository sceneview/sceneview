// @sceneId     video-recording
// @title       Video Recording
// @subtitle    Record the scene to MP4 in-app — no MediaProjection
// @category    advanced
// @available   false
// @icon        record.circle.fill
import SwiftUI

/// Coming-soon placeholder — Android's `video-recording` demo (in-app MP4
/// recording via `SurfaceMirrorer`) has no iOS port yet; added to Android
/// after iOS stopped tracking the catalog (#2804 Job B).
enum VideoRecordingScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(EmptyView()) }
}
