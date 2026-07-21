// @sceneId     splat-preview
// @title       Gaussian Splatting
// @subtitle    Render a 3D Gaussian Splat radiance-field cloud
// @category    advanced
// @available   false
// @icon        circle.grid.3x3.fill
import SwiftUI

/// Coming-soon placeholder — Android's `splat-preview` demo (3D Gaussian
/// Splatting, #2646) has no iOS port yet; blocked on an iOS SplatNode
/// renderer (#2768, #2804 Job B).
enum SplatPreviewScene: DemoScene {
    @MainActor static var destination: AnyView { AnyView(EmptyView()) }
}
