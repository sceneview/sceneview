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
///   - `residualIds` — ids that must be accepted by the gate without having
///     any iOS screen of their own. Empty today, and the preferred state: an
///     id with no real screen needs no registry entry at all, because an
///     unregistered id already reaches the same honest placeholder (see
///     "Deep-link guarantee" below).
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
        //
        // `ar-rooftop-anchors` / `ar-terrain-anchors` used to live here too.
        // Their canonical targets (`ar-rooftop`, `ar-terrain`) were
        // coming-soon Scene stubs with no destination, and were deleted with
        // the rest of the dead-end catalogue — an alias may only point at a
        // live Scene id (`DemoRegistryGuardTests`
        // `testEveryLegacyAliasTargetIsARegisteredSceneId`). Both ids still
        // land on `DeepLinkPlaceholder` through the unregistered-id path
        // below, so no QR code 404s; they simply no longer claim a catalogue
        // entry that does not exist.
        "ar-cloud-anchors": "ar-cloud-anchor",

        // Umbrella aliases (L0.6, #2804) — an Android umbrella id routed to
        // the iOS scene that carries its default tab. Android's own ids for
        // `camera-gestures` and `animation-physics` are iOS scene ids since the
        // samples audit (step 0), so only these two remain until step 1:
        //   - custom-geometry   → custom-mesh   (default tab: CustomMesh)
        //   - picking-collision → collision     (default tab: RayHitTest)
        //   - two-d-in-three-d  → text          (default tab: Text)
        "custom-geometry": "custom-mesh",
        "picking-collision": "collision",
        "two-d-in-three-d": "text",

        // Retired scene ids (#4083) — a scene replaced by a new one keeps its
        // old deep link alive. `physics` (RealityKit cubes) became the Rolling
        // Balls tray, like Android's `DeepLinkRouter` alias.
        "physics": "rolling-balls",
        // `reflection-probes` became the Lighting Lab, like Android's
        // `DeepLinkRouter` alias (#3496).
        "reflection-probes": "lighting-lab",

        // Samples audit, step 0 (48 → 34 cards): every absorbed iOS card
        // keeps its link and opens the card that absorbed it, on the mode
        // that carries it (`aliasModes`). One hop only, like Android's
        // `DeepLinkRouter.validate`: each value is a live scene id.
        //
        // Android's own absorbed ids (`placement-scene`, `video-recording`,
        // `ar-collaborative`) are still Android cards until its step 0 lands;
        // `parity-manifest.yml` keeps them `android-only` here, so they join
        // this table with the rows that retire them. `secondary-camera` is a
        // live iOS card since #4250, not an alias.
        //
        // The shared Record action replaced the `ar-record-playback` card;
        // what it recorded is replayed in Rerun.
        "ar-record-playback": "ar-rerun",
        "ar-recording": "ar-rerun",
        // Placement, with its Wall / Free pose / Light modes.
        "wall-placement": "ar-placement",
        "ar-pose": "ar-placement",
        "ar-lighting": "ar-placement",
        // Camera & Gestures, Android's card and id.
        "camera-controls": "camera-gestures",
        "gesture-editing": "camera-gestures",
        "animation": "animation-physics",
        "double-pendulum": "rolling-balls",
        // Lighting's three rigs already were these three screens.
        "dynamic-sky": "lighting",
        "environment": "lighting",
        "movable-light": "lighting",
        "texture-streaming": "materials",
        "occlusion-material": "materials",
        "multi-model": "model-viewer",
        "scene-gallery": "model-viewer",
    ]

    /// The mode an alias opens inside its card (samples audit, step 0) —
    /// Android's `ALIAS_INITIAL_TAB`, by name. The token is one the target
    /// card's ``DemoModeHost`` (or `LightingDemo`'s rig picker) recognises;
    /// an alias missing here opens its card on the default mode.
    static let aliasModes: [String: String] = [
        "wall-placement": "wall",
        "ar-pose": "free-pose",
        "ar-lighting": "light",
        "camera-controls": "camera",
        "gesture-editing": "gestures",
        "double-pendulum": "pendulum",
        "dynamic-sky": "sun",
        "environment": "image",
        "movable-light": "studio",
        "occlusion-material": "occlusion",
        "multi-model": "park",
    ]

    /// Hands a tab asked for under an alias to the card the alias opens.
    ///
    /// `DeepLinkRouter` stores a link's `?tab=` under the id the link named —
    /// for `sceneview://demo/wall-placement`, under `wall-placement` — but the
    /// screen that reads it is `ar-placement`'s. Call once per incoming link,
    /// after the tab was stored: an explicit `?tab=` wins, else the alias's
    /// own mode (`aliasModes`). A canonical id is left untouched.
    static func routeTab(for id: String) {
        guard !GeneratedScenes.allowedIds.contains(id), let canonical = legacyAliases[id] else { return }
        let explicit = DeepLinkRouter.consumeTab(for: id)
        guard let tab = explicit ?? aliasModes[id] else { return }
        DeepLinkRouter.setTab(tab, for: canonical)
    }

    /// Deep-linkable ids with no `*Scene.swift` file — accepted by the gate,
    /// resolved to `DeepLinkPlaceholder`. Remove an id from here the moment a
    /// matching Scene file is added; the generated union then covers it with
    /// no hand edit.
    ///
    /// Empty, and expected to stay that way: an id with no real iOS screen
    /// does not get a catalogue entry at all. L0.6 (#2804) had emptied this
    /// list by giving each residual id a coming-soon `*Scene.swift` stub;
    /// those stubs were themselves deleted (a Samples card that opens a
    /// "Coming soon" screen is a dead end in a showcase app), so the ids are
    /// simply unregistered now and fall through to `DeepLinkPlaceholder` via
    /// the unregistered-id path in `destination(for:)`. Kept as an empty
    /// `Set` (not deleted) — it is still the documented landing spot for an
    /// id that must be *accepted* without having a screen, and
    /// `DemoRegistryGuardTests` asserts the `[]` case directly (no residual id
    /// may shadow a live generated scene id). `.claude/scripts/check-demo-id-parity.sh`
    /// (#2801) checks the cross-platform side of the id set in CI.
    ///
    /// Internal (not `private`) for the same reason as `legacyAliases` above —
    /// `DemoRegistryGuardTests` (#2801) asserts this list never collides with a
    /// live generated scene id (a stale entry that should have been deleted).
    /// Demos removed from the catalogue on **every** platform, mapped to the
    /// title they used to carry. A QR code or a bookmark printed while the demo
    /// existed still resolves, and the user is told the truth: it is gone, not
    /// pending, and not hiding on Android.
    ///
    /// - `fog`: RealityKit exposes no depth-based fog, so the iOS screen was a
    ///   translucent volume standing in for it. Removed rather than simulated.
    ///   Android keeps its Filament fog demo; the removal is iOS-only.
    /// - `placement-reticle-preview`: a camera-less preview of the placement
    ///   reticle. Android removed its twin in #3275; iOS followed in the
    ///   samples audit (step 0). The reticle itself is in AR Placement.
    static let removedIds: [String: String] = [
        "fog": "Fog",
        "placement-reticle-preview": "AR Placement Reticle Preview",
    ]

    /// Why each ``removedIds`` entry is gone, in one sentence for the
    /// placeholder.
    static let removedReasons: [String: String] = [
        "fog": "Fog needs depth-based fog, which RealityKit does not offer, so the iOS screen was removed. The Android app still has it.",
        "placement-reticle-preview": "The reticle preview was removed from both apps. The reticle itself is in AR Placement, on a real camera.",
    ]

    static let residualIds: Set<String> = []

    /// Full set of accepted deep-link ids: the generated scene ids
    /// (`GeneratedScenes.allowedIds`) ∪ legacy aliases ∪ residual ids. Every
    /// id in this set opens a real iOS screen. An Android-only id is NOT in
    /// the set and is not a 404 either: `SceneViewDemoApp.onOpenURL` forwards
    /// the unregistered candidate to `destination(for:)`, which answers with
    /// `DeepLinkPlaceholder`.
    static let allowedIds: Set<String> =
        GeneratedScenes.allowedIds
            .union(legacyAliases.keys)
            .union(residualIds)

    /// Resolve a demo id to its presented `View`.
    ///
    /// Resolution order: the generated id→view map, then the legacy-alias
    /// indirection, then `DeepLinkPlaceholder`. The placeholder is returned for
    /// any id without a live destination — an Android-only id, an iOS-only AR
    /// scene on a non-iOS build, or an id that isn't in `allowedIds` at all —
    /// so a deep link always lands on a screen and is never silently dropped.
    ///
    /// `@MainActor`-isolated because it constructs SwiftUI `View` values, which
    /// are main-actor-isolated; the only call site (`ContentView`'s
    /// `.fullScreenCover` / `.sheet`) already runs on the main actor.
    @MainActor
    static func destination(for id: String) -> AnyView {
        // A deep link skips the Showcase grid, which is what normally tells the
        // demo chrome its title — so a linked demo opened with no identity pill.
        let canonical = GeneratedScenes.allowedIds.contains(id) ? id : legacyAliases[id]
        if let canonical, let view = GeneratedScenes.destination(for: canonical) {
            let title = GeneratedScenes.all().first { $0.sceneId == canonical }?.title
            return AnyView(view.environment(\.demoTitle, title))
        }
        if let title = removedIds[id] {
            return AnyView(DeepLinkPlaceholder(
                headline: "This demo isn\u{2019}t in the iOS app.",
                detail: removedReasons[id] ?? "\(title) was removed from the iOS app."
            ))
        }
        return AnyView(DeepLinkPlaceholder(
            headline: "This demo isn't in the iOS app.",
            detail: "The link points at \u{201C}\(id)\u{201D}, which has no screen here. Browse the Showcase grid for the full iOS catalogue."
        ))
    }

    /// The demo's human title for the host's navigation bar.
    ///
    /// A removed id keeps the title it shipped under; an id that never existed
    /// here gets a neutral one. Never the raw id: `sceneview://demo/fog` used to
    /// title the screen \u{201C}fog\u{201D}, lower-case, which reads as a bug.
    @MainActor
    static func title(for id: String) -> String {
        let canonical = GeneratedScenes.allowedIds.contains(id) ? id : legacyAliases[id]
        guard let canonical,
              let title = GeneratedScenes.all().first(where: { $0.sceneId == canonical })?.title
        else { return removedIds[id] ?? "Demo not found" }
        return title
    }

    /// A deep-linked demo inside the SAME host the catalogue uses
    /// (``DemoCover``): a Close control in every case, and the host's
    /// leading-edge swipe dismissal.
    ///
    /// The bare `destination(for:)` is still the resolver; it is not a screen.
    /// Presenting it directly is what left a hand-rolled AR demo — which draws
    /// no chrome of its own — with no dismissal affordance at all when it was
    /// reached from a QR code instead of the catalogue.
    @MainActor
    static func cover(for id: String, source: SampleOpenSource = .deeplink,
                      onClose: @escaping () -> Void) -> DemoCover {
        let canonical = GeneratedScenes.allowedIds.contains(id) ? id : legacyAliases[id]
        let section = canonical.flatMap { c in GeneratedScenes.all().first { $0.sceneId == c }?.section }
        return DemoCover(title: title(for: id), destination: destination(for: id),
                         sampleId: canonical, category: DemoAnalytics.category(for: section),
                         entryId: id, source: source,
                         mode: canonical.flatMap {
                             DemoAnalytics.initialMode(for: $0, tab: DeepLinkRouter.pendingTab(for: $0))
                         },
                         onClose: onClose)
    }

    /// Whether `id` opens a real screen (a live id or a legacy alias of one).
    static func resolves(_ id: String) -> Bool {
        GeneratedScenes.allowedIds.contains(id) || legacyAliases[id] != nil
    }
}

/// Shown when a deep-link id resolves to no live iOS destination — a demo that
/// was removed, or an id this app never carried.
///
/// Says what happened in one sentence and offers the one way forward: close the
/// link and browse the catalogue. No QR glyph (the link may not have come from a
/// code), no pointer to a tab that does not exist, and never "yet" — a removed
/// demo is not a pending one.
private struct DeepLinkPlaceholder: View {
    let headline: String
    let detail: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(spacing: SceneViewTokens.Space.md) {
            Spacer()
            Text(headline)
                .font(.headline)
                .foregroundStyle(SceneViewTokens.HomeColor.onSurface)
                .multilineTextAlignment(.center)
            Text(detail)
                .font(.subheadline)
                // `.secondary` renders as a light grey here — the design
                // system's own dim role is the one with a measured ratio.
                .foregroundStyle(SceneViewTokens.HomeColor.onSurfaceDim)
                .multilineTextAlignment(.center)
                .padding(.horizontal, SceneViewTokens.Space.xl)
            Spacer()
            Button("Browse demos") { dismiss() }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("deeplink-browse-demos")
                .padding(.bottom, SceneViewTokens.Space.lg)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(SceneViewTokens.HomeColor.surface)
    }
}
