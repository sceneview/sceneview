#if os(iOS)
import SwiftUI

/// Preserves the existing route while using detected-plane automatic placement.
/// There is no estimated-plane toggle: placement always requires usable geometry.
struct ARInstantPlacementDemo: View {
    var body: some View { ARPlacementExperience() }
}
#endif
