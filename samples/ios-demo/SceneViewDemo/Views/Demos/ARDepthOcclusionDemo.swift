#if os(iOS)
import SwiftUI

/// One bundled 0.3 m preview subject; toggling occlusion changes only the live renderer.
/// Capability and camera permission are checked by ARExperienceContainer before mounting.
struct ARDepthOcclusionDemo: View {
    var body: some View {
        ARPlacementExperience(initialModel: ARPlacementExperience.comparisonModel,
                              title: "Depth Occlusion", occlusion: .depth)
    }
}
#endif
