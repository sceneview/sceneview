import SwiftUI

/// Append-only demo registry protocol — iOS counterpart of Android's `DemoFragment`.
///
/// To add a new iOS demo:
/// 1. Create `Views/Demos/Scenes/<YourId>Scene.swift` declaring an `enum`
///    conforming to `DemoScene`.
/// 2. Run `bash samples/ios-demo/scripts/collate-ios-demos.sh` to regenerate
///    `GeneratedScenes.swift`.
/// 3. The demo appears in `SamplesTab` **and** becomes deep-linkable via
///    `sceneview://demo/<sceneId>` automatically — the collator generates
///    the Samples list, the `allowedIds` gate, and the id→view resolver from
///    the same `@sceneId`, so **no other file needs to be edited**.
///
/// # Rules
///
/// - The `sceneId` must be a stable slug matching the Android `DemoRegistry`
///   entry (e.g. `"model-viewer"`). It is used as the deep-link token.
/// - `sceneId` must be unique across all conforming types.
/// - The file must be named exactly `<PascalCaseId>Scene.swift` under
///   `Views/Demos/Scenes/` so the collator can discover it.
///
/// # How the collator works
///
/// `samples/ios-demo/scripts/collate-ios-demos.sh` discovers every
/// `*Scene.swift` file, reads the `// @sceneId`, `// @title`,
/// `// @subtitle`, `// @category`, `// @available`, and `// @status`
/// directives from each file's header comments, and regenerates
/// `GeneratedScenes.swift`. That generated file is `.gitignore`d (like
/// Android's `GeneratedDemos.kt`) and re-created before each Xcode build by a
/// Run Script phase.
///
/// Using structured header comments (instead of parsing Swift syntax)
/// keeps the collator a simple line-grepping shell script that works
/// without a Swift parser or SPM plugins.
///
/// # Directives
///
/// ```
/// // @sceneId     model-viewer
/// // @title       Model Viewer
/// // @subtitle    Load and display 3D models
/// // @category    basics3D          (one of: basics3D|lighting|content|interaction|advanced|ar)
/// // @available   true              (true = shows destination view; false = "Coming soon")
/// // @status      working           (optional — see below)
/// // @androidOnlyReason  <reason>   (optional — see below)
/// ```
///
/// `@sceneId`, `@title`, `@subtitle`, `@category`, and `@available` are
/// required. `@status` is **optional** and mirrors Android's `DemoStatus`
/// (`Working`/`KnownIssue`/`ComingSoon`/`InReview`, see `DemoItem.swift`):
///
/// - One of `working` | `knownIssue` | `inReview` | `comingSoon`.
/// - **Default when omitted** (so no pre-existing `*Scene.swift` file needs
///   editing): `working` when `@available true`, `comingSoon` when
///   `@available false`.
/// - **Cross-validated against `@available`** — the collator errors out on a
///   contradictory pair: `working`/`knownIssue`/`inReview` all require
///   `@available true` (they claim a real destination exists); `comingSoon`
///   requires `@available false` (it claims none does).
/// - Drives the badge rendered on the Samples-tab card
///   (`SamplesTab.swift`'s `StatusBadge` — "Preview" / "In review" / "Soon",
///   `.working` renders no badge) — the iOS mirror of Android's
///   `DemoListScreen.kt` `StatusChip`.
///
/// `@androidOnlyReason` is **optional** (#2804 Job C) and only valid on a
/// `@available false` scene — the collator errors out if set alongside
/// `@available true`. When present, the one-line text replaces "Coming soon"
/// with an honest "Android-only" treatment on both the Samples-tab card and
/// ``ComingSoonScreen`` (pill text, footer paragraph, nav title) — for a
/// capability that is **permanently** platform-locked (no ARKit/RealityKit
/// equivalent, e.g. ARCore Geospatial/VPS) rather than merely not ported yet.
/// Omit it for the ordinary "not ported yet, might land later" case.
///
/// # `@available false` is no longer a shipping state (#3231)
///
/// Every `@available false` Scene file was deleted: a Samples card that opens
/// a "Coming soon" screen is a dead end, and a showcase app does not display
/// its holes. An id with no iOS screen now has **no Scene file at all** — its
/// deep link falls through to `DeepLinkPlaceholder`
/// (`DemoDeepLinkRegistry.destination(for:)`), and its absence from the iOS
/// catalog is recorded in the cross-platform ledger `parity-manifest.yml`
/// (`iosStatus: android-only`), which `.claude/scripts/check-demo-id-parity.sh`
/// enforces.
///
/// The `@available` / `comingSoon` / ``ComingSoonScreen`` machinery below is
/// kept deliberately — it is the compile-time path a new `@available false`
/// file would take, and `DemoRegistryGuardTests.testNoSamplesCardIsADeadEnd`
/// is what fails the build if one is ever added again. Adding a demo means
/// adding a Scene file with a **real** destination.
///
/// The conforming type must also provide:
/// - `static var destination: AnyView { get }` — SwiftUI view (ignored when
///   `@available false`).
///
/// Conforming with `enum` (no cases) is idiomatic Swift for a namespace:
///
/// ```swift
/// // @sceneId     model-viewer
/// // @title       Model Viewer
/// // @subtitle    Load and display 3D models
/// // @category    basics3D
/// // @available   true
/// enum ModelViewerScene: DemoScene {
///     static var destination: AnyView { AnyView(ModelViewerDemo()) }
/// }
/// ```
public protocol DemoScene {
    /// The SwiftUI `View` presented when the user taps the demo card.
    ///
    /// Ignored for coming-soon demos (collator sets `available = false` in
    /// the generated `DemoItem`).
    @MainActor static var destination: AnyView { get }
}
