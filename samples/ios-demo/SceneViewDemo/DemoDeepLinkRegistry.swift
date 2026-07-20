import SwiftUI

/// Maps a stable demo id (`ar-rerun`, `model-viewer`, …) to the corresponding
/// SwiftUI destination for the deep-link path (`sceneview://demo/<id>`).
///
/// # Why the id set is (mostly) generated
///
/// The deep-link contract requires a stable, slug-style id matched
/// **byte-for-byte** with Android's `DemoRegistry` — otherwise the same
/// `sceneview://demo/<id>` URL would route to different things on Android and
/// iOS, defeating the cross-platform guarantee of the QR codes on the website.
///
/// Historically this file hand-maintained *two* copies of that id set — a
/// `allowedIds` literal and a parallel `switch` — alongside the Samples tab's
/// own generated list. Three surfaces, three hand edits, and they drifted:
/// 12 ids silently diverged (#2769). They are now driven from **one** source
/// of truth — the `@sceneId` header directives that the collator
/// (`samples/ios-demo/scripts/collate-ios-demos.sh`) already parses:
///
///   - `GeneratedScenes.allowedIds` — every id that has a `*Scene.swift` file.
///   - `GeneratedScenes.destination(for:)` — that id's view (or `nil` for a
///     coming-soon / non-iOS AR scene).
///
/// Adding a demo = adding one Scene file; all three surfaces update on the
/// next build with no edit here.
///
/// # What stays hand-maintained (the residual)
///
/// Only ids that have **no** `*Scene.swift` file yet:
///   - `legacyAliases` — pre-canonicalization ids (#2799) kept so existing QR
///     codes keep resolving. Each maps to a canonical id a Scene declares, so
///     the alias inherits that scene's destination.
///   - `residualIds` — AR demos present in Android's catalog whose iOS card is
///     still to be ported (L0.6, #2804). They resolve to a placeholder until a
///     Scene file lands; when one does, delete the id here and the generated
///     union covers it automatically.
///
/// # Deep-link guarantee
///
/// An id is **never silently ignored**. Ids in `allowedIds` resolve to a real
/// view or an honest placeholder; a well-formed `sceneview://demo/<id>` URL
/// whose id is *not* in `allowedIds` is still surfaced as a placeholder by the
/// caller (`SceneViewDemoApp.onOpenURL`), never dropped into a silent no-op.
enum DemoDeepLinkRegistry {

    /// Legacy deep-link aliases → canonical scene id. Pre-canonicalization
    /// ids (#2799) kept only so existing QR codes / bookmarks keep resolving.
    /// The canonical target is declared by a `*Scene.swift` file, so the alias
    /// resolves to exactly that scene's destination (or its placeholder, when
    /// the canonical scene is `@available false`).
    private static let legacyAliases: [String: String] = [
        "ar-recording": "ar-record-playback",
        "ar-cloud-anchors": "ar-cloud-anchor",
        "ar-rooftop-anchors": "ar-rooftop",
        "ar-terrain-anchors": "ar-terrain",
    ]

    /// Deep-linkable ids with no `*Scene.swift` file yet — AR demos present in
    /// Android's catalog whose iOS card is still to be ported (L0.6, #2804).
    /// They pass `allowedIds` so the URL is accepted, and resolve to the
    /// coming-soon placeholder. Remove an id from here the moment a matching
    /// Scene file is added; the generated union then covers it with no hand edit.
    private static let residualIds: Set<String> = [
        "ar-collaborative",
        "ar-depth-collider",
        "ar-depth-of-field",
        "ar-depth-visualization",
        "ar-fog",
        "ar-hand-tracking",
        "ar-ml-object-label",
        "ar-raw-depth-point-cloud",
        "ar-scene-semantics",
        "ar-xr-face",
        "placement-scene",
    ]

    /// Full set of accepted deep-link ids: the generated scene ids
    /// (`GeneratedScenes.allowedIds`) ∪ legacy aliases ∪ not-yet-ported
    /// residual ids. Every `sceneview://demo/<id>` QR code for a real Android
    /// demo resolves on iOS through this set — coming-soon ids included, routed
    /// to a placeholder so the URL is never a silent 404.
    static let allowedIds: Set<String> =
        GeneratedScenes.allowedIds
            .union(legacyAliases.keys)
            .union(residualIds)

    /// Resolve a demo id to its presented `View`.
    ///
    /// Resolution order: the generated id→view map, then the legacy-alias
    /// indirection, then a coming-soon placeholder. The placeholder is returned
    /// for any id without a live destination — a coming-soon scene, a residual
    /// not-yet-ported AR id, an iOS-only AR scene on a non-iOS build, or an id
    /// that isn't in `allowedIds` at all — so a deep link always lands on a
    /// screen and is never silently dropped.
    ///
    /// `@MainActor`-isolated because it constructs SwiftUI `View` values, which
    /// are main-actor-isolated; the only call site (`ContentView`'s
    /// `.fullScreenCover` / `.sheet`) already runs on the main actor.
    @MainActor
    static func destination(for id: String) -> AnyView {
        if let view = GeneratedScenes.destination(for: id) {
            return view
        }
        if let canonical = legacyAliases[id],
           let view = GeneratedScenes.destination(for: canonical) {
            return view
        }
        return AnyView(DeepLinkPlaceholder(
            id: id,
            reason: "This demo isn't available in the iOS app yet — open it on Android, or browse the Samples tab for the full iOS catalog."
        ))
    }
}

/// Tiny placeholder shown when a deep-link id resolves to no live iOS
/// destination — a coming-soon scene, a not-yet-ported AR id, or an id that
/// isn't registered at all. Communicates the gap clearly and offers a way out
/// (close + browse the Samples tab).
private struct DeepLinkPlaceholder: View {
    let id: String
    let reason: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "qrcode.viewfinder")
                .font(.system(size: 48))
                .foregroundStyle(.secondary)
            Text("Demo: \(id)")
                .font(.headline)
            Text(reason)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
            Button("Close") { dismiss() }
                .buttonStyle(.bordered)
                .padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        #if os(iOS)
        .background(Color(UIColor.systemBackground))
        #endif
    }
}
