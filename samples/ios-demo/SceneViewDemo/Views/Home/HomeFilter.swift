import Foundation

/// One searchable row of the home grid — a `DemoItem` reduced to plain
/// values, so `filterDemos` stays a pure function that is unit-testable with
/// no SwiftUI and no registry. The iOS twin of Android's `HomeFilter.kt`.
struct HomeSearchEntry: Equatable {
    let id: String
    let title: String
    let subtitle: String
    let section: DemoSection
    let category: DemoCategory
    let tags: [String]
    let order: Int

    init(
        id: String,
        title: String,
        subtitle: String,
        section: DemoSection,
        category: DemoCategory,
        tags: [String] = [],
        order: Int = 999
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.section = section
        self.category = category
        self.tags = tags
        self.order = order
    }

    init(_ item: DemoItem) {
        self.init(id: item.sceneId, title: item.title, subtitle: item.subtitle,
                  section: item.section, category: item.category, tags: item.tags, order: item.order)
    }
}

/// Pure filter behind the home screen's section chips and search field.
///
/// - `section` `nil` means "All"; otherwise only entries of that section survive.
/// - `query` is trimmed and split on whitespace; every word must match
///   (case-insensitively) somewhere in title, subtitle, section or category
///   label, or tags. A blank query matches everything.
/// - The result is in editorial `order` (ties broken by title) — the same
///   sequence Android's `filterDemos` returns. `@order` is section-contiguous,
///   so the result also reads section by section.
func filterDemos(_ entries: [HomeSearchEntry], section: DemoSection?, query: String) -> [HomeSearchEntry] {
    let words = query.lowercased()
        .split(whereSeparator: { $0.isWhitespace })
        .map(String.init)
    return entries
        .filter { section == nil || $0.section == section }
        .filter { entry in words.allSatisfy { entry.matches($0) } }
        .sorted { ($0.order, $0.title) < ($1.order, $1.title) }
}

private extension HomeSearchEntry {
    func matches(_ word: String) -> Bool {
        title.lowercased().contains(word)
            || subtitle.lowercased().contains(word)
            || section.title.lowercased().contains(word)
            || category.rawValue.lowercased().contains(word)
            || category.shortLabel.lowercased().contains(word)
            || tags.contains { $0.lowercased().contains(word) }
    }
}

/// Editorial choices of the Showcase home that are not a property of any one
/// scene (#3907): the Featured shelf and the demos kept off the home grid.
enum HomeCatalogue {
    /// The Featured shelf under the hero, in priority order — Android's
    /// `FEATURED_SECTION_IDS` (`HomeScreen.kt`) reduced to the demos that have
    /// an iOS screen. Android features `splat-preview` and `ar-splat-room`
    /// too; neither exists on iOS yet (#2646, #4075), so the shelf skips them rather
    /// than showing a placeholder. `animation` is the iOS half of Android's
    /// `animation-physics`.
    static let featuredIds: [String] = [
        "ar-rerun",
        "animation",
        "ar-placement",
        "ar-record-playback",
    ]

    /// Demos kept off the home grid until they work, with the reason. The
    /// scene files and the deep links (`sceneview://demo/<id>`) are untouched — only
    /// the home stops advertising a demo that is broken on first open.
    /// Remove an entry in the PR that fixes the demo.
    static let hiddenFromHome: [String: String] = [
        "double-pendulum": "Pivot jumps around, untextured arms, camera too close (#3907)",
        "movable-light": "Moving the light barely changes the scene (#3907)",
        "scene-gallery": "Shows the \"Offline placeholder\" in keyless builds (#3907)",
        "multi-model": "Shows the \"Offline placeholder\" in keyless builds (#3907)",
        "materials": "Shows the \"Offline placeholder\" in keyless builds (#3907)",
    ]

    static func isOnHome(_ sceneId: String) -> Bool {
        hiddenFromHome[sceneId] == nil
    }
}

extension DemoCategory {
    /// Short subsystem label, kept searchable ("ar", "lighting") now that the
    /// chips filter by `DemoSection`.
    var shortLabel: String {
        switch self {
        case .basics3D: return "3D"
        case .lighting: return "Lighting"
        case .content: return "Content"
        case .interaction: return "Interaction"
        case .advanced: return "Advanced"
        case .ar: return "AR"
        }
    }
}
