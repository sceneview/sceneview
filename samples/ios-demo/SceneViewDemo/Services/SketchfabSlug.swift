import Foundation

/// Curated metadata for a single Sketchfab asset that is safe to stream from
/// the iOS demo app.
///
/// Every entry must satisfy three invariants before it is allowed into
/// `SampleAssets`:
///
///  1. **License is CC-BY** — the only Sketchfab license SceneView's demo
///     apps redistribute. Other Creative Commons variants (NC, ND, SA) and
///     the bespoke "Sketchfab Standard" license are intentionally excluded.
///     Enforced at runtime by `SampleAssets.validate()`.
///  2. **Fallback bundled asset exists** — the resolver falls back to
///     `fallbackBundledPath` when no Sketchfab API key is configured (App
///     Store builds, offline cold cache, network failure). This guarantees
///     the `feedback_demo_quality` rule "NEVER ship a build that needs the
///     network to render something useful".
///  3. **Scale hint + animation hint are honest** — both surface in the
///     bounds sanity check in `SketchfabAssetResolver.resolve(_:)` so we can
///     blacklist drifting slugs (authors edit their models over time).
///
/// Mirrors the Android scaffold (`SketchfabSlug.kt`) — keep both in sync when
/// adding fields.
struct SketchfabSlug: Hashable, Sendable {
    /// What the bundled fallback IS relative to the label it renders under
    /// (#2960).
    ///
    /// The defect #2940 named is "a fallback whose subject is semantically
    /// incompatible with the label it renders under" — a book under
    /// "Wine Barrel", a piano under "Coffee Mug". The bundled set has no vase,
    /// mug, crate, table, sofa or statue, so most of those cannot be re-pointed
    /// without shipping a new binary. Until then the demo must *say* it is
    /// showing a placeholder rather than pretend: the asset-source pill reads
    /// "Offline placeholder" instead of "Offline model" for a ``placeholder``
    /// slug. `BundledAssetPrimBudgetTests` pins every ``subjectMatch`` claim to
    /// a reviewed allowlist, so a new slug cannot claim a match by default.
    enum FallbackRole: Hashable, Sendable {
        /// The fallback is the same kind of thing as the label — a fox for a
        /// fox, a lantern for a lamp, a butterfly for a butterfly, an animated
        /// humanoid for a walking robot.
        case subjectMatch
        /// The fallback is a different subject that only keeps the demo's
        /// *mechanism* working (something to place, drop, light, animate).
        /// The UI must label it as a placeholder.
        case placeholder
    }

    /// The Sketchfab model uid (matches the path segment in
    /// `sketchfab.com/3d-models/<slug>-<uid>`). Always the unique identifier
    /// — never trust the `<slug>` portion which authors can edit.
    let uid: String

    /// Human-readable label shown in carousels, credits sheets and registry
    /// test reports.
    let displayName: String

    /// The Sketchfab username of the original author (required by CC-BY
    /// attribution). Printed in the Stage 3 credits sheet.
    let author: String

    /// Direct link to the CC-BY license text. Pinned to the exact license
    /// version (`/licenses/by/4.0/` not `/licenses/by/`) so legal review can
    /// audit the registry without resolving redirects.
    let licenseURL: URL

    /// Bundle-relative path to the offline fallback (`Models/<file>.usdz`).
    /// Used by `SketchfabAssetResolver.fallbackBundle(for:)`.
    let fallbackBundledPath: String

    /// Whether `fallbackBundledPath` is the same subject as the label or a
    /// declared stand-in. See ``FallbackRole``.
    let fallbackRole: FallbackRole

    /// Expected post-load bounding-sphere radius in metres. Out-of-range
    /// values trigger the resolver's fallback.
    let scaleToUnits: Float

    /// `true` when the model carries one or more skeletal animations —
    /// checked against the asset's actual animation count by the bounds
    /// sanity step.
    let hasBakedAnimation: Bool

    /// Free-form grouping (`solar`, `gallery`, `animation`, `ar_placement`,
    /// `physics`, `materials`). Used by
    /// `SketchfabAssetResolver.prefetchAll(category:)`.
    let category: String

    /// Optional free-form tags surfaced in the credits sheet (e.g.
    /// `"low-poly"`, `"hard-surface"`).
    let tags: [String]

    /// Canonical Sketchfab page URL for this model, used by the Credits sheet
    /// (CC-BY attribution requires a visible link back to the source). Built
    /// from `uid` only — Sketchfab tolerates a missing `<slug>` segment and
    /// 301-redirects to the canonical URL.
    var sketchfabURL: URL {
        // Construction is total — `uid` is non-empty and the prefix is a
        // hard-coded literal.
        URL(string: "https://sketchfab.com/3d-models/\(uid)")!
    }

    init(
        uid: String,
        displayName: String,
        author: String,
        licenseURL: URL,
        fallbackBundledPath: String,
        fallbackRole: FallbackRole = .subjectMatch,
        scaleToUnits: Float,
        hasBakedAnimation: Bool,
        category: String,
        tags: [String] = []
    ) {
        precondition(!uid.isEmpty, "SketchfabSlug.uid must not be empty")
        precondition(!displayName.isEmpty,
                     "SketchfabSlug.displayName must not be empty (uid=\(uid))")
        precondition(!author.isEmpty,
                     "SketchfabSlug.author must not be empty for CC-BY attribution (uid=\(uid))")
        precondition(
            licenseURL.absoluteString.hasPrefix("https://creativecommons.org/licenses/by/"),
            "SketchfabSlug.licenseURL must be a CC-BY creativecommons.org URL (uid=\(uid))."
            + " Other licenses (NC, ND, SA, Sketchfab Standard) are not allowed in the"
            + " demo registry — see SampleAssets documentation."
        )
        precondition(!fallbackBundledPath.isEmpty,
                     "SketchfabSlug.fallbackBundledPath must not be empty (uid=\(uid))."
                     + " Every entry needs an offline fallback so demos render without a"
                     + " key/network.")
        precondition(scaleToUnits > 0,
                     "SketchfabSlug.scaleToUnits must be > 0 (uid=\(uid))")
        precondition(!category.isEmpty,
                     "SketchfabSlug.category must not be empty (uid=\(uid))")

        self.uid = uid
        self.displayName = displayName
        self.author = author
        self.licenseURL = licenseURL
        self.fallbackBundledPath = fallbackBundledPath
        self.fallbackRole = fallbackRole
        self.scaleToUnits = scaleToUnits
        self.hasBakedAnimation = hasBakedAnimation
        self.category = category
        self.tags = tags
    }
}
