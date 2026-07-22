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
/// Only ids that have **no** `*Scene.swift` file of their own:
///   - `legacyAliases` — an id with no Scene file that should route to a
///     DIFFERENT id's Scene, in two flavors:
///     1. **Rename aliases** — pre-canonicalization ids (#2799) kept so
///        existing QR codes keep resolving (e.g. `ar-cloud-anchors` →
///        `ar-cloud-anchor`).
///     2. **Umbrella aliases** (L0.6, #2804/#2769) — a live Android id from
///        the #2239 catalog regroup (e.g. `custom-geometry`) whose iOS
///        constituent screens already exist under their pre-regroup granular
///        ids (e.g. `custom-mesh`). Routing the umbrella id straight to the
///        single most-representative granular scene resolves the deep link
///        to REAL, already-shipped content — not a coming-soon card, which
///        would dishonestly imply the capability doesn't exist on iOS yet.
///        A full "regrouped umbrella UI" mirroring Android's exact
///        combined-tab layout is a separate, larger, explicitly-deferred
///        piece of work (#2804) — this only fixes deep-link resolution.
///     Either way, the alias inherits its target scene's destination (or its
///     placeholder, if the target is itself `@available false`).
///   - `residualIds` — AR demos present in Android's catalog whose iOS card is
///     still to be ported. They resolve to a placeholder until a Scene file
///     lands; when one does, delete the id here and the generated union
///     covers it automatically. Emptied by L0.6 (#2804) — every id that was
///     here now has a Scene file — kept as an empty `Set` (rather than
///     deleted) since it is still the documented landing spot for the NEXT
///     not-yet-ported AR id.
///
/// # Deep-link guarantee
///
/// An id is **never silently ignored**. Ids in `allowedIds` resolve to a real
/// view or an honest placeholder; a well-formed `sceneview://demo/<id>` URL
/// whose id is *not* in `allowedIds` is still surfaced as a placeholder by the
/// caller (`SceneViewDemoApp.onOpenURL`), never dropped into a silent no-op.
enum DemoDeepLinkRegistry {

    /// Legacy / umbrella deep-link aliases → canonical scene id (see the two
    /// flavors documented above). Every value is declared by a `*Scene.swift`
    /// file, so the alias resolves to exactly that scene's destination (or
    /// its placeholder, when the canonical scene is `@available false`).
    ///
    /// Internal (not `private`) so `DemoRegistryGuardTests` (#2801) can assert
    /// the registry-integrity invariants directly against this table — e.g.
    /// every value must be a live `GeneratedScenes.allowedIds` member, no key
    /// may also be a live scene id — mirroring how Android's
    /// `DeepLinkRouterTest` asserts directly against `DEMO_ID_ALIASES`.
    static let legacyAliases: [String: String] = [
        // Rename aliases (#2799) — pre-canonicalization ids.
        "ar-recording": "ar-record-playback",
        "ar-cloud-anchors": "ar-cloud-anchor",
        "ar-rooftop-anchors": "ar-rooftop",
        "ar-terrain-anchors": "ar-terrain",

        // Umbrella aliases (L0.6, #2804 Job A / #2769) — a live Android
        // #2239-regrouped id routed to the single most-representative
        // pre-regroup granular iOS scene. The target is Android's own
        // default/first tab for that umbrella (`initialDemoMode`'s `default`
        // parameter in `DemoSettings.kt`), not an arbitrary pick:
        //   - custom-geometry   → custom-mesh   (default tab: CustomMesh)
        //   - camera-gestures   → camera-controls (default tab: CameraModes)
        //   - picking-collision → collision     (default tab: RayHitTest)
        //   - animation-physics → animation     (default tab: Animation)
        //   - two-d-in-three-d  → text          (default tab: Text)
        //   - lighting-lab      → dynamic-sky   (default tab: Sky — NOT
        //     `environment`; #2769's own suggestion predates checking
        //     Android's actual default, which is Sky)
        // A full "regrouped umbrella UI" (Android's exact combined-tab
        // layout) is explicitly out of scope for this lot — see the doc
        // comment above.
        "custom-geometry": "custom-mesh",
        "camera-gestures": "camera-controls",
        "picking-collision": "collision",
        "animation-physics": "animation",
        "two-d-in-three-d": "text",
        "lighting-lab": "dynamic-sky",
    ]

    /// Deep-linkable ids with no `*Scene.swift` file yet — AR demos present in
    /// Android's catalog whose iOS card is still to be ported. They pass
    /// `allowedIds` so the URL is accepted, and resolve to the coming-soon
    /// placeholder. Remove an id from here the moment a matching Scene file is
    /// added; the generated union then covers it with no hand edit.
    ///
    /// Empty as of L0.6 (#2804) — every AR id that was here (`ar-collaborative`,
    /// `ar-depth-collider`, `ar-depth-of-field`, `ar-depth-visualization`,
    /// `ar-fog`, `ar-hand-tracking`, `ar-ml-object-label`,
    /// `ar-raw-depth-point-cloud`, `ar-scene-semantics`, `ar-xr-face`,
    /// `placement-scene`) now has its own `*Scene.swift` stub with an honest
    /// `comingSoonTitle`. Kept as an empty `Set` (not deleted) — it is still
    /// the documented landing spot for the next not-yet-ported AR id, and
    /// `check-demo-id-parity.sh` (#2801) is specifically tested against this
    /// `[]` case.
    ///
    /// Internal (not `private`) for the same reason as `legacyAliases` above —
    /// `DemoRegistryGuardTests` (#2801) asserts this list never collides with a
    /// live generated scene id (a stale entry that should have been deleted).
    static let residualIds: Set<String> = []

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
