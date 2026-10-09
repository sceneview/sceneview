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
    /// "New" / "Updated" verdict — what the "What's new" chip keeps.
    let freshness: DemoFreshness

    init(
        id: String,
        title: String,
        subtitle: String,
        section: DemoSection,
        category: DemoCategory,
        tags: [String] = [],
        order: Int = 999,
        freshness: DemoFreshness = .none
    ) {
        self.id = id
        self.title = title
        self.subtitle = subtitle
        self.section = section
        self.category = category
        self.tags = tags
        self.order = order
        self.freshness = freshness
    }

    init(_ item: DemoItem, buildVersion: String = DemoFreshness.appVersion) {
        self.init(id: item.sceneId, title: item.title, subtitle: item.subtitle,
                  section: item.section, category: item.category, tags: item.tags, order: item.order,
                  freshness: DemoFreshness.of(item, buildVersion: buildVersion))
    }
}

/// Pure filter behind the home screen's section chips and search field.
///
/// - `section` `nil` means "All"; otherwise only entries of that section survive.
/// - `whatsNew` keeps only the entries marked "New" or "Updated" — the home's
///   "What's new" chip.
/// - `query` is trimmed and split on whitespace; every word must match
///   (case-insensitively) somewhere in title, subtitle, section or category
///   label, or tags. A blank query matches everything.
/// - The result is in editorial `order` (ties broken by title) — the same
///   sequence Android's `filterDemos` returns. `@order` is section-contiguous,
///   so the result also reads section by section.
func filterDemos(_ entries: [HomeSearchEntry], section: DemoSection?, query: String,
                 whatsNew: Bool = false) -> [HomeSearchEntry] {
    let words = query.lowercased()
        .split(whereSeparator: { $0.isWhitespace })
        .map(String.init)
    return entries
        .filter { section == nil || $0.section == section }
        .filter { !whatsNew || $0.freshness != .none }
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
    /// The "Featured" group under the hero, in priority order — the one list
    /// both platforms share since the samples audit (step 0, § 5): only cards
    /// present and current on Android and iOS. Cosmos is the hero, Placement
    /// the single AR entry, then Models, Rerun and Materials.
    static let featuredIds: [String] = [
        "cosmos",
        "ar-placement",
        "model-viewer",
        "ar-rerun",
        "materials",
    ]

    /// Demos kept off the home grid until they work, with the reason. The
    /// scene files and the deep links (`sceneview://demo/<id>`) are untouched — only
    /// the home stops advertising a demo that is broken on first open.
    /// Remove an entry in the PR that fixes the demo.
    ///
    /// Empty since the samples audit (step 0): the four cards it held
    /// (`double-pendulum`, `movable-light`, `scene-gallery`, `multi-model`, #3907)
    /// are modes of Rolling Balls, Lighting and Models now, or gone.
    static let hiddenFromHome: [String: String] = [:]

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
/// release it first shipped in on iOS (`// @addedIn`, required) and its last
/// notable rework (`// @updatedIn`), both read from this platform's history,
/// and the verdict is computed against the running build's version. Nothing
/// is hardcoded as "new": a declaration ages out on its own two minors
/// later (`windowMinors`). Declare the release the change will ship in, including
/// the next version for work merged between releases. A demo that is not
/// available on iOS ("Coming soon") is never marked: there is nothing new to try.
enum DemoFreshness: Equatable {
    case new
    case updated
    case none

    /// Minors a declaration stays fresh for — Android's `FRESHNESS_WINDOW_MINORS`.
    /// `2`: a 4.51 build flags what landed in 4.49, 4.50 or 4.51. At `1`, with
    /// several minors a week, almost nothing still carried a chip by the time
    /// anyone opened the app (02/10).
    static let windowMinors = 2

    /// Chip text, verbatim from Android's `samples_chip_new` / `_updated`.
    var label: String? {
        switch self {
        case .new: return "New"
        case .updated: return "Updated"
        case .none: return nil
        }
    }

    /// `addedIn` wins over `updatedIn`: a demo that is new is not also
    /// "updated".
    static func of(addedIn: String?, updatedIn: String?,
                   buildVersion: String, window: Int = windowMinors) -> DemoFreshness {
        if isRecent(addedIn, buildVersion: buildVersion, window: window) { return .new }
        if isRecent(updatedIn, buildVersion: buildVersion, window: window) { return .updated }
        return .none
    }

    static func of(_ item: DemoItem, buildVersion: String = appVersion) -> DemoFreshness {
        guard item.status.isAvailable else { return .none }
        return of(addedIn: item.addedIn, updatedIn: item.updatedIn, buildVersion: buildVersion)
    }

    /// `true` when `version` parses and is not ahead of `buildVersion`.
    /// This validates the iOS registry against its `MARKETING_VERSION`; the
    /// freshness comparison itself still treats a future version as recent.
    static func isDeclarable(_ version: String?, buildVersion: String) -> Bool {
        guard let declared = semVer(version), let build = semVer(buildVersion) else { return false }
        return declared.lexicographicallyPrecedes(build) || declared == build
    }

    private static func semVer(_ version: String?) -> [Int]? {
        guard let version else { return nil }
        let base = version.prefix { $0 != "-" && $0 != "+" }.trimmingCharacters(in: .whitespaces)
        let parts = base.split(separator: ".", omittingEmptySubsequences: false).map { Int($0) }
        guard (2...3).contains(parts.count), parts.allSatisfy({ $0 != nil }) else { return nil }
        let numbers = parts.compactMap { $0 }
        return numbers + Array(repeating: 0, count: 3 - numbers.count)
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

    /// The oldest release still inside the window, as `major.minor` — "4.49"
    /// for a 4.51 build. Android's `freshnessWindowStart`; the What's new row
    /// says "since" it. An unparseable build version comes back as is.
    static func windowStart(buildVersion: String, window: Int = windowMinors) -> String {
        guard let build = majorMinor(buildVersion) else { return buildVersion }
        return "\(build.major).\(max(build.minor - window, 0))"
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
