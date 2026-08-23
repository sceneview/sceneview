import SwiftUI

/// Maturity status for a demo on iOS — mirrors Android's `DemoStatus`
/// (`samples/android-demo/.../DemoRegistry.kt:13-29`) so both platforms can
/// express the same four states about a demo's audit status:
///
/// | iOS case      | Android case | Badge (`badgeLabel`) | Has a real destination? |
/// |---------------|--------------|-----------------------|--------------------------|
/// | `.working`    | `Working`    | none                  | yes                      |
/// | `.knownIssue` | `KnownIssue` | "Preview"             | yes                      |
/// | `.inReview`   | `InReview`   | "In review"           | yes                      |
/// | `.comingSoon` | `ComingSoon` | "Soon"                | no                       |
///
/// **Documented asymmetry with Android:** Android's `ComingSoon` fragments
/// still ship a real (if partial) `Screen()` — e.g. `ArHandTrackingFragment`
/// renders a static reference skeleton because live hand tracking needs
/// hardware the audit matrix doesn't have. iOS's `DemoScene` contract is
/// binary instead (`@available true|false` gates whether a Scene provides a
/// real `destination` at all — see `DemoScene.swift`), so on iOS
/// `.comingSoon` always means "no destination yet", never "a partial one". A
/// demo that IS implemented on iOS but has a known rendering/interaction bug
/// is `.knownIssue`, not `.comingSoon` — see `collate-ios-demos.sh`'s
/// `@status`/`@available` cross-validation.
///
/// Demos present on Android but not yet ported to iOS appear in the list with a
/// "Soon" badge and route to ``ComingSoonScreen`` instead of crashing or hiding.
enum DemoStatus: Equatable {
    /// Verified working — no badge (the common case shouldn't be visually flagged).
    case working

    /// Has a real destination but a known visual/interaction regression on the
    /// audited device matrix — surfaced with a "Preview" badge so users have
    /// honest expectations without the card reading as broken. Mirrors
    /// Android's `KnownIssue`.
    case knownIssue

    /// Newly shipped, awaiting on-device review sign-off — surfaced with an
    /// "In review" badge so testers know exactly which demos to exercise on
    /// the next store build. Flip to `.working` once the review pass
    /// validates it. Mirrors Android's `InReview`.
    case inReview

    /// Not yet implemented on iOS — no real destination; routes to
    /// ``ComingSoonScreen`` instead. Mirrors Android's `ComingSoon` in
    /// spirit (see the documented asymmetry above).
    case comingSoon

    /// `true` for any status with a real, tappable destination
    /// (`.working`, `.knownIssue`, `.inReview`) — only `.comingSoon` has none.
    var isAvailable: Bool { self != .comingSoon }

    /// `true` only for `.comingSoon`. Kept as a named accessor (existing call
    /// sites already read naturally as `scene.status.isComingSoon`).
    var isComingSoon: Bool { self == .comingSoon }

    /// Badge text shown on the demo card in `SamplesTab`, or `nil` for
    /// `.working` (no badge rendered). Mirrors Android's `StatusChip` label
    /// strings (`DemoListScreen.kt:322-328` / `strings.xml`'s
    /// `samples_chip_*` entries) so the same four states read the same way
    /// on both platforms.
    var badgeLabel: String? {
        switch self {
        case .working: return nil
        case .knownIssue: return "Preview"
        case .inReview: return "In review"
        case .comingSoon: return "Soon"
        }
    }
}

/// Represents a single scene entry in the Scenes tab.
struct DemoItem: Identifiable {
    let id = UUID()
    /// Stable deep-link slug (`// @sceneId`), also the key of the home-card
    /// preview image (`preview_<sceneId with underscores>` in the asset catalog).
    let sceneId: String
    /// Editorial position on the Showcase home grid — mirrors Android's
    /// `DemoEntry.order`. `999` when a Scene file declares no `// @order`.
    let order: Int
    /// Search keywords (`// @tags`) — mirrors Android's `DemoEntry.tags`.
    let tags: [String]
    let title: String
    let icon: String
    let subtitle: String
    let category: DemoCategory
    let status: DemoStatus
    let destination: AnyView

    /// One-line, user-defensible reason this demo is **permanently**
    /// Android-only (no ARKit/RealityKit equivalent exists — e.g. ARCore
    /// Geospatial/VPS, a Google-backend service), as opposed to merely not
    /// ported yet. `nil` for the common "coming soon, might land later" case.
    ///
    /// Always `nil` for an available item (`status.isAvailable`) — only a
    /// `.comingSoon` item can be platform-locked. When non-nil,
    /// ``ComingSoonScreen`` swaps its "Coming soon" pill and footer for an
    /// honest "Android-only" treatment instead of implying a port is coming
    /// (#2804 Job C — "not planned for iOS" must never read as "coming soon").
    let androidOnlyReason: String?

    /// Demo with a real destination view. `status` must be one of the three
    /// "available" cases (`.working`, `.knownIssue`, `.inReview`) — enforced
    /// with a precondition since `.comingSoon` has no destination by
    /// definition and must go through the `comingSoonTitle:` initializer
    /// below instead.
    init<V: View>(
        sceneId: String,
        title: String,
        icon: String,
        subtitle: String,
        category: DemoCategory,
        status: DemoStatus = .working,
        order: Int = 999,
        tags: [String] = [],
        @ViewBuilder destination: () -> V
    ) {
        precondition(
            status.isAvailable,
            "DemoItem(title:...) requires an available status (.working/.knownIssue/.inReview) " +
            "— use the comingSoonTitle: initializer for .comingSoon"
        )
        self.sceneId = sceneId
        self.order = order
        self.tags = tags
        self.title = title
        self.icon = icon
        self.subtitle = subtitle
        self.category = category
        self.status = status
        self.destination = AnyView(destination())
        self.androidOnlyReason = nil
    }

    /// Coming-soon demo — tap routes to ``ComingSoonScreen`` instead of a real destination.
    ///
    /// Mirrors an Android demo that is not yet ported to iOS. The item stays visible in the list
    /// (with a "Soon" badge) so users see the roadmap rather than discovering gaps. Status is
    /// always `.comingSoon` — there is no destination to attach any other status to.
    ///
    /// - Parameter androidOnlyReason: set only for a **permanently** Android-only capability
    ///   (no ARKit/RealityKit equivalent) — see the field doc above. `nil` (default) for the
    ///   ordinary "not ported yet" case.
    init(
        sceneId: String,
        comingSoonTitle title: String,
        icon: String,
        subtitle: String,
        order: Int = 999,
        tags: [String] = [],
        category: DemoCategory,
        androidOnlyReason: String? = nil
    ) {
        self.sceneId = sceneId
        self.order = order
        self.tags = tags
        self.title = title
        self.icon = icon
        self.subtitle = subtitle
        self.category = category
        self.status = .comingSoon
        self.destination = AnyView(EmptyView())
        self.androidOnlyReason = androidOnlyReason
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
