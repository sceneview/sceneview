import Foundation

/// One searchable row of the home grid — a `DemoItem` reduced to plain
/// values, so `filterDemos` stays a pure function that is unit-testable with
/// no SwiftUI and no registry. The iOS twin of Android's `HomeFilter.kt`.
struct HomeSearchEntry: Equatable {
    let id: String
    let title: String
    let subtitle: String
    let category: DemoCategory
    let tags: [String]
    let order: Int

    init(id: String, title: String, subtitle: String, category: DemoCategory, tags: [String] = [], order: Int = 999) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.category = category
        self.tags = tags
        self.order = order
    }

    init(_ item: DemoItem) {
        self.init(id: item.sceneId, title: item.title, subtitle: item.subtitle,
                  category: item.category, tags: item.tags, order: item.order)
    }
}

/// Pure filter behind the home screen's category chips and search field.
///
/// - `category` `nil` means "All"; otherwise only entries of that category survive.
/// - `query` is trimmed and split on whitespace; every word must match
///   (case-insensitively) somewhere in title, subtitle, category label or tags.
///   A blank query matches everything.
/// - The result is in editorial `order` (ties broken by title) — the same
///   sequence Android's `filterDemos` returns.
func filterDemos(_ entries: [HomeSearchEntry], category: DemoCategory?, query: String) -> [HomeSearchEntry] {
    let words = query.lowercased()
        .split(whereSeparator: { $0.isWhitespace })
        .map(String.init)
    return entries
        .filter { category == nil || $0.category == category }
        .filter { entry in words.allSatisfy { entry.matches($0) } }
        .sorted { ($0.order, $0.title) < ($1.order, $1.title) }
}

private extension HomeSearchEntry {
    func matches(_ word: String) -> Bool {
        title.lowercased().contains(word)
            || subtitle.lowercased().contains(word)
            || category.rawValue.lowercased().contains(word)
            || category.shortLabel.lowercased().contains(word)
            || tags.contains { $0.lowercased().contains(word) }
    }
}

extension DemoCategory {
    /// Chip label on the Showcase home — the short names Android's
    /// `category_short_*` strings use.
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
