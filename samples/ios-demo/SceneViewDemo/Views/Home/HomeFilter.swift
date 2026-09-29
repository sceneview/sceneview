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
/// scene (#3907): the "Featured" group and the demos kept off the home list.
enum HomeCatalogue {
    /// The "Featured" group under the hero, in priority order — Android's
    /// `FEATURED_SECTION_IDS` (`HomeScreen.kt`) reduced to the demos that have
    /// an iOS screen. Android features `ar-splat-room` too; it does not exist on
    /// iOS yet (#4075), so the group skips it rather than showing a placeholder.
    /// `splat-preview` opens the same capture drawn as a point cloud (no splat
    /// renderer on iOS yet, #2646). `animation` is the iOS half of Android's
    /// `animation-physics`.
    static let featuredIds: [String] = [
        "ar-rerun",
        "splat-preview",
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
    ]

    /// Demos that stream their subject from Sketchfab and have nothing but the
    /// "Offline placeholder" to show without an API key. They open the Create
    /// section of a keyed build (every store and CI build is keyed) — where
    /// Android shows them — and stay off the home of a keyless local build.
    /// Empty since Materials became a procedural sphere wall, as on Android.
    static let hiddenWithoutSketchfabKey: [String: String] = [:]

    /// Previews anchored at their leading edge rather than centred when the
    /// landscape capture is cropped to a home row's square thumb — Android's
    /// `FEATURED_MEDIA_ALIGNMENT` (#4144, #4186). `ar-rerun`'s preview is a
    /// capture of the demo whose top-right corner holds its own "Camera"
    /// picture-in-picture: centred, the crop kept half of it and it read as a
    /// second picture stuck on the first. Anchored leading, the crop keeps the
    /// camera path and the room.
    static let leadingAnchored: Set<String> = ["ar-rerun"]

    static func isOnHome(_ sceneId: String,
                         hasSketchfabKey: Bool = SketchfabConfig.apiKey != nil) -> Bool {
        if hiddenFromHome[sceneId] != nil { return false }
        if !hasSketchfabKey, hiddenWithoutSketchfabKey[sceneId] != nil { return false }
        return true
    }
}

/// "New" / "Updated" verdict of a demo card — the iOS port of Android's
/// `DemoFreshness.kt`, same rule and same inputs: each scene declares the
/// version it shipped in (`// @sinceVersion`) and its last notable rework
/// (`// @updatedIn`), mirrored from the Android fragment of the same demo,
/// and the verdict is computed against the running build's version. Nothing
/// is hardcoded as "new": a declaration ages out on its own two minors later.
enum DemoFreshness: Equatable {
    case new
    case updated
    case none

    /// Minors a declaration stays fresh for — Android's `FRESHNESS_WINDOW_MINORS`.
    /// `1`: a 4.48 build flags what landed in 4.47 or 4.48.
    static let windowMinors = 1

    /// Chip text, verbatim from Android's `samples_chip_new` / `_updated`.
    var label: String? {
        switch self {
        case .new: return "New"
        case .updated: return "Updated"
        case .none: return nil
        }
    }

    /// `sinceVersion` wins over `updatedIn`: a demo that is new is not also
    /// "updated".
    static func of(sinceVersion: String?, updatedIn: String?,
                   buildVersion: String, window: Int = windowMinors) -> DemoFreshness {
        if isRecent(sinceVersion, buildVersion: buildVersion, window: window) { return .new }
        if isRecent(updatedIn, buildVersion: buildVersion, window: window) { return .updated }
        return .none
    }

    static func of(_ item: DemoItem, buildVersion: String = appVersion) -> DemoFreshness {
        of(sinceVersion: item.sinceVersion, updatedIn: item.updatedIn, buildVersion: buildVersion)
    }

    /// `true` when `version` is within `window` minors of `buildVersion`, or
    /// ahead of it. Across a major bump only a newer major counts. Pre-release
    /// and build suffixes (`-rc1`, `+sha`) are ignored; an unparseable version
    /// is never fresh.
    static func isRecent(_ version: String?, buildVersion: String, window: Int = windowMinors) -> Bool {
        guard let declared = majorMinor(version), let build = majorMinor(buildVersion) else { return false }
        if declared.major != build.major { return declared.major > build.major }
        return declared.minor >= build.minor - window
    }

    private static func majorMinor(_ version: String?) -> (major: Int, minor: Int)? {
        guard let version else { return nil }
        let base = version.prefix { $0 != "-" && $0 != "+" }.trimmingCharacters(in: .whitespaces)
        let parts = base.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count >= 2, let major = Int(parts[0]), let minor = Int(parts[1]) else { return nil }
        return (major, minor)
    }

    /// The running build's marketing version (`MARKETING_VERSION`, kept equal
    /// to Android's `VERSION_NAME` by the release pipeline).
    static let appVersion: String =
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.0"
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
