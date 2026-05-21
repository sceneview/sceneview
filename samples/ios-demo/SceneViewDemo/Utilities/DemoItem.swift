import SwiftUI

/// Availability status for a demo on iOS.
///
/// Demos that are present on Android but not yet ported to iOS appear in the list with a
/// "Coming soon" badge and route to ``ComingSoonScreen`` instead of crashing or hiding.
enum DemoStatus: Equatable {
    case available
    case comingSoon

    var isAvailable: Bool {
        if case .available = self { return true }
        return false
    }

    var isComingSoon: Bool {
        if case .comingSoon = self { return true }
        return false
    }
}

/// Represents a single scene entry in the Scenes tab.
struct DemoItem: Identifiable {
    let id = UUID()
    let title: String
    let icon: String
    let subtitle: String
    let category: DemoCategory
    let status: DemoStatus
    let destination: AnyView

    /// Available demo with a real destination view.
    init<V: View>(
        title: String,
        icon: String,
        subtitle: String,
        category: DemoCategory,
        @ViewBuilder destination: () -> V
    ) {
        self.title = title
        self.icon = icon
        self.subtitle = subtitle
        self.category = category
        self.status = .available
        self.destination = AnyView(destination())
    }

    /// Coming-soon demo — tap routes to ``ComingSoonScreen`` instead of a real destination.
    ///
    /// Mirrors an Android demo that is not yet ported to iOS. The item stays visible in the list
    /// (with a "Coming soon" badge) so users see the roadmap rather than discovering gaps.
    init(
        comingSoonTitle title: String,
        icon: String,
        subtitle: String,
        category: DemoCategory
    ) {
        self.title = title
        self.icon = icon
        self.subtitle = subtitle
        self.category = category
        self.status = .comingSoon
        self.destination = AnyView(EmptyView())
    }
}

/// Scene categories for grouping.
///
/// The category set and display names mirror Android's `DemoCategory`
/// (`samples/android-demo/.../DemoRegistry.kt`) verbatim — the iOS demo must
/// read as the same product as the Android demo (see #1377).
enum DemoCategory: String, CaseIterable, Comparable {
    case basics3D = "3D Basics"
    case lighting = "Lighting & Environment"
    case content = "Content"
    case interaction = "Interaction"
    case advanced = "Advanced"
    case ar = "Augmented Reality"

    static func < (lhs: DemoCategory, rhs: DemoCategory) -> Bool {
        let order: [DemoCategory] = [.basics3D, .lighting, .content, .interaction, .advanced, .ar]
        let lhsIndex = order.firstIndex(of: lhs) ?? 0
        let rhsIndex = order.firstIndex(of: rhs) ?? 0
        return lhsIndex < rhsIndex
    }
}
