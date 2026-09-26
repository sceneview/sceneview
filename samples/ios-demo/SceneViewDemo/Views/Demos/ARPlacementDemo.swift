#if os(iOS)
import SwiftUI
import SceneViewSwift

/// Route adapter retaining viewer asset identity and authored-unit handoff.
/// Permission and capability entry is supplied by ARExperienceContainer at the host.
struct ARPlacementDemo: View {
    var initialModel: String? = nil
    var initialModelURL: URL? = nil
    var initialModelUnit: ModelUnit? = nil

    var body: some View {
        ARPlacementExperience(
            initialModel: initialModel,
            initialModelURL: initialModelURL,
            initialModelUnit: initialModelUnit
        )
    }
}
#endif
