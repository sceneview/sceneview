// DemoRegistryGuardTests.swift
//
// Registry / deep-link guard suite for `GeneratedScenes` + `DemoDeepLinkRegistry`
// (#2801, part of the iOS-parity tracker #2798). This is the iOS mirror of
// Android's `DemoRegistryIntegrityTest.kt` (registry shape: uniqueness,
// kebab-case, routability) and the alias section of `DeepLinkRouterTest.kt`
// (`every DEMO_ID_ALIASES target is a live registered demo`, `retired alias id
// must not also be a registered demo`).
//
// Before this suite, iOS had ZERO registry/deep-link guard tests, against
// Android's 284 `@Test`s covering the same surface. The specific gap this
// closes: the #2769 regression (12 ids silently dropped because three
// hand-maintained surfaces — a `Set` literal, a `switch`, and the Samples-tab
// list — drifted apart) was fixed structurally in #2800 by generating all
// three from the same `@sceneId` directives, but nothing YET asserted the
// generator's output stays internally consistent, or that a human doesn't
// have to re-verify "does this id show a real screen or the placeholder?" by
// hand on every review — which is exactly what happened during the #2798
// baseline audit. This suite automates that manual cross-check.
//
// Depends on L0.2 (#2800): it asserts against the GENERATED
// `GeneratedScenes.allowedIds` / `.destination(for:)` surface, not the old
// hand-maintained registry. It consumes that surface; it does not change
// `collate-ios-demos.sh` (L0.4's territory, #2802).

#if DEBUG

import XCTest
@testable import SceneViewDemo

@MainActor
final class DemoRegistryGuardTests: XCTestCase {

    /// kebab-case: lowercase ASCII letters / digits, hyphen-separated —
    /// mirrors Android's `DemoRegistryIntegrityTest.kebabCase`. The id is a
    /// `sceneview://demo/<id>` path segment; anything else breaks routing.
    /// Also the compensating control for the L0.2-review NIT: the collator
    /// (`collate-ios-demos.sh`) validates `@available`/`@category` against a
    /// closed enum but never validates `@sceneId`'s own format — this test
    /// runs against the REAL generated output on every CI run instead.
    private let kebabCase = try! NSRegularExpression(pattern: "^[a-z0-9]+(-[a-z0-9]+)*$")

    private func isKebabCase(_ id: String) -> Bool {
        let range = NSRange(id.startIndex..<id.endIndex, in: id)
        return kebabCase.firstMatch(in: id, options: [], range: range) != nil
    }

    // MARK: - Registry shape (non-emptiness, format)

    func testAllowedIdsIsNonEmpty() {
        // A regression in the collator (or an empty Scenes dir) would
        // silently ship a zero-demo deep-link gate.
        XCTAssertFalse(DemoDeepLinkRegistry.allowedIds.isEmpty,
                        "DemoDeepLinkRegistry.allowedIds must not be empty")
    }

    func testGeneratedAllowedIdsIsNonEmpty() {
        XCTAssertFalse(GeneratedScenes.allowedIds.isEmpty,
                        "GeneratedScenes.allowedIds must list at least one *Scene.swift")
    }

    func testEveryAllowedIdIsKebabCase() {
        for id in DemoDeepLinkRegistry.allowedIds {
            XCTAssertTrue(isKebabCase(id),
                          "id '\(id)' must be kebab-case (lowercase, hyphen-separated) — " +
                          "it is a sceneview://demo/<id> path segment")
        }
    }

    func testUnknownIdIsNotInAllowedIds() {
        // Negative-space guard, mirrors Android's `deep-link router rejects
        // ids that are not in the registry`.
        XCTAssertFalse(DemoDeepLinkRegistry.allowedIds.contains("definitely-not-a-real-demo"))
    }

    // MARK: - The three sources never collide (Swift's analogue of "unique ids")
    //
    // `allowedIds` is a `Set`, so a literal duplicate string can't survive as
    // two entries — the real drift risk this repo has actually hit is the
    // SAME id being claimed by two of the three sources: a new `*Scene.swift`
    // reusing an id that's still a legacy alias key or a residual
    // (not-yet-ported) id that was never deleted once its Scene shipped
    // (`DemoDeepLinkRegistry`'s own doc comment: "Remove an id from here the
    // moment a matching Scene file is added").

    func testGeneratedIdsDoNotCollideWithLegacyAliasKeys() {
        let collisions = GeneratedScenes.allowedIds.intersection(DemoDeepLinkRegistry.legacyAliases.keys)
        XCTAssertTrue(collisions.isEmpty,
                      "Scene id(s) \(collisions) are also legacy alias keys — a real Scene file " +
                      "must not be shadowed by an alias entry")
    }

    func testGeneratedIdsDoNotCollideWithResidualIds() {
        let collisions = GeneratedScenes.allowedIds.intersection(DemoDeepLinkRegistry.residualIds)
        XCTAssertTrue(collisions.isEmpty,
                      "Scene id(s) \(collisions) are still listed as residual (not-yet-ported) but " +
                      "already have a real Scene file — delete from residualIds, the generated " +
                      "union already covers them (see DemoDeepLinkRegistry's doc comment)")
    }

    func testLegacyAliasKeysDoNotCollideWithResidualIds() {
        let collisions = Set(DemoDeepLinkRegistry.legacyAliases.keys).intersection(DemoDeepLinkRegistry.residualIds)
        XCTAssertTrue(collisions.isEmpty,
                      "id(s) \(collisions) are listed as both a legacy alias key and a residual id")
    }

    /// Pins the union arithmetic itself so a future refactor of `allowedIds`
    /// can't silently drop one of the three sources without a test noticing.
    func testAllowedIdsIsExactlyTheUnionOfAllThreeSources() {
        let expected = GeneratedScenes.allowedIds
            .union(DemoDeepLinkRegistry.legacyAliases.keys)
            .union(DemoDeepLinkRegistry.residualIds)
        XCTAssertEqual(DemoDeepLinkRegistry.allowedIds, expected,
                       "allowedIds must be exactly generated ∪ legacyAliases.keys ∪ residualIds")
    }

    // MARK: - Legacy aliases resolve to a real, currently-registered scene id
    //
    // Mirrors Android's `DeepLinkRouterTest`: "every DEMO_ID_ALIASES target is
    // a live registered demo" + "retired alias id must not also be a
    // registered demo".

    func testEveryLegacyAliasTargetIsARegisteredSceneId() {
        // An alias VALUE that typos or outlives its target Scene file would
        // silently fall through to the generic placeholder — this is the
        // only thing standing between that typo and a shipped 404.
        for (retired, canonical) in DemoDeepLinkRegistry.legacyAliases {
            XCTAssertTrue(
                GeneratedScenes.allowedIds.contains(canonical),
                "legacy alias '\(retired)' -> '\(canonical)' but '\(canonical)' is not a " +
                "registered Scene id"
            )
        }
    }

    func testNoLegacyAliasKeyIsItselfARegisteredSceneId() {
        // An alias key that's ALSO a live scene id is dead code (the
        // generated id already wins any `Set` union) and hides a stale entry
        // that should have been deleted when the id was canonicalized.
        for retired in DemoDeepLinkRegistry.legacyAliases.keys {
            XCTAssertFalse(
                GeneratedScenes.allowedIds.contains(retired),
                "legacy alias key '\(retired)' is also a live Scene id — the alias is dead, delete it"
            )
        }
    }

    // MARK: - The central invariant: real demos resolve, everything else
    // honestly falls through to the placeholder.
    //
    // This is the check that used to be done BY HAND during the #2798 /
    // #2769 audits: grep every `*Scene.swift`'s `@available` value, cross-
    // reference against `destination(for:)`'s switch, note any mismatch.
    // Rather than hardcode all 66 ids (a list that would itself rot the
    // moment a demo is added, ported, or its `@available` flag flips — churn
    // this repo sees on nearly every samples PR), this pins:
    //   (a) a handful of long-stable flagship ids that must never regress to
    //       a placeholder, and
    //   (b) the small, historically-significant set that's already caused a
    //       real bug (the #2799 canonicalized ids + their #2769 aliases).
    // Growth of the *un-pinned* middle (new demos, newly-ported residuals) is
    // exactly what `parity-manifest.yml` + `check-demo-id-parity.sh` (#2801
    // deliverable c) track going forward, so it isn't duplicated here.

    /// Flagship, long-shipped ids — if any of these ever falls through to the
    /// placeholder, something is badly broken (a deleted Scene file, an
    /// `@available` flip, a switch typo). Deliberately chosen to be simple,
    /// non-networked, non-AR demos so constructing the destination view has
    /// no side effects beyond a plain SwiftUI initializer.
    func testWellKnownWorkingDemosResolveToARealDestination() {
        let mustBeReal = ["model-viewer", "geometry", "animation", "physics", "materials"]
        for id in mustBeReal {
            XCTAssertNotNil(GeneratedScenes.destination(for: id),
                            "'\(id)' is expected to be a real, working demo but resolved to nil " +
                            "(placeholder) — check its Scene file's @available directive")
        }
    }

    /// Ids known today to be coming-soon or not-yet-ported — a regression pin
    /// so a demo doesn't quietly start claiming to be "working" (a false
    /// promise to users) without this test (and `parity-manifest.yml`, if the
    /// id is Android-sourced) also being updated intentionally.
    func testKnownComingSoonAndResidualIdsStillFallThroughToThePlaceholder() {
        let mustBePlaceholder = [
            "ar-cloud-anchor", "ar-rooftop", "ar-terrain",   // #2799 canonicalized ids, still @available false
            "post-processing", "secondary-camera",           // long-standing coming-soon cards
            "ar-collaborative", "placement-scene",           // residual — no Scene file yet (L0.6, #2804)
        ]
        for id in mustBePlaceholder {
            XCTAssertNil(GeneratedScenes.destination(for: id),
                        "'\(id)' was expected to still be coming-soon/residual. If it now has a " +
                        "real, @available Scene, that's great — update this pin (and " +
                        "residualIds / parity-manifest.yml if it's an Android-sourced id).")
        }
    }

    /// The #2769 regression, pinned directly. Before #2800, `custom-geometry`
    /// et al. (a different bug, still open — tracked by `parity-manifest.yml`
    /// as `android-only`) and these 4 pre-#2799 ids silently 404'd because
    /// `allowedIds` was a hand-copied literal that never got updated. Each of
    /// these 4 must keep resolving — through its canonical target — to
    /// exactly that target's current realness, whichever way the canonical
    /// scene's own `@available` flag goes.
    func testLegacyAliasesArePinnedAndInheritTheirCanonicalTargetsRealness() {
        let aliases = DemoDeepLinkRegistry.legacyAliases
        XCTAssertEqual(aliases, [
            "ar-recording": "ar-record-playback",
            "ar-cloud-anchors": "ar-cloud-anchor",
            "ar-rooftop-anchors": "ar-rooftop",
            "ar-terrain-anchors": "ar-terrain",
        ], "legacyAliases changed — update this pin (and re-verify the new/changed alias " +
           "resolves sanely through DemoDeepLinkRegistry.destination(for:))")

        for (retired, canonical) in aliases {
            let canonicalIsReal = GeneratedScenes.destination(for: canonical) != nil
            // The alias key itself is never a generated id (pinned above), so
            // its ONLY path to a real destination is through `canonical`.
            let aliasReachesReal = GeneratedScenes.destination(for: retired) != nil || canonicalIsReal
            XCTAssertEqual(
                aliasReachesReal, canonicalIsReal,
                "alias '\(retired)' must inherit exactly '\(canonical)'s realness (\(canonicalIsReal))"
            )
        }
    }

    // MARK: - Every allowed id is routable through the public entry point

    /// Smoke-tests the PUBLIC surface (`DemoDeepLinkRegistry.destination(for:)`
    /// — the one `SceneViewDemoApp` / `ContentView` actually call) end-to-end
    /// for every id in `allowedIds`, not just the generated half. This is
    /// what would have caught #2769: `custom-geometry` et al. were accepted
    /// by nothing, so `destination(for:)` was never even exercised for them.
    /// `destination(for:)` returns `AnyView` (non-optional) — reaching the
    /// end of the loop without a crash/trap IS the assertion; every id
    /// resolves to *some* view (real or the honest placeholder), never a
    /// silent drop.
    func testEveryAllowedIdIsRoutableThroughThePublicRegistry() {
        for id in DemoDeepLinkRegistry.allowedIds {
            _ = DemoDeepLinkRegistry.destination(for: id)
        }
    }

    /// `destination(for:)` never special-cases its input against
    /// `allowedIds` — it is a total function that resolves generated → alias
    /// → placeholder for ANY string. Defense in depth beyond the `allowedIds`
    /// gate `SceneViewDemoApp` applies before ever calling this: even a
    /// completely unregistered id must resolve to the honest placeholder,
    /// never crash.
    func testDestinationNeverCrashesForAnUnregisteredId() {
        _ = DemoDeepLinkRegistry.destination(for: "definitely-not-a-real-demo")
        _ = DemoDeepLinkRegistry.destination(for: "")
    }

    // MARK: - Samples-tab list (`GeneratedScenes.all()`) stays in lockstep
    // with the deep-link surface (`GeneratedScenes.allowedIds`)
    //
    // Both are emitted from the SAME collator pass over the SAME per-scene
    // metadata, so they can't drift from typing a demo twice — but nothing
    // previously asserted that guarantee at the Swift level. Mirrors the
    // spirit of Android's `DemoRegistryIntegrityTest` checks on `ALL_DEMOS`
    // (non-empty, unique titles, every category populated), adapted to
    // iOS's plain-string `DemoItem` (no `@StringRes` indirection to check).

    /// On iOS (this test always runs on an iOS simulator/device), every
    /// `#if os(iOS)` AR item is included, so the Samples-tab list and the
    /// deep-link id set must describe exactly the same number of scenes.
    /// A mismatch means the two collator emissions (the `all()` list and the
    /// `allowedIds` set) fell out of sync.
    func testGeneratedAllCountMatchesAllowedIdsCountOnIOS() {
        XCTAssertEqual(
            GeneratedScenes.all().count, GeneratedScenes.allowedIds.count,
            "GeneratedScenes.all() and .allowedIds must describe the same scenes on iOS"
        )
    }

    /// Two demos sharing the same title would confuse users in the Samples
    /// grid — almost always a copy-paste bug in a new `*Scene.swift`'s
    /// `@title` directive. Mirrors Android's title/subtitle-resource
    /// uniqueness check, translated to iOS's plain string titles.
    func testNoDuplicateTitlesAmongGeneratedItems() {
        let titles = GeneratedScenes.all().map(\.title)
        let duplicates = Dictionary(grouping: titles, by: { $0 }).filter { $0.value.count > 1 }.keys
        XCTAssertTrue(duplicates.isEmpty, "Duplicate demo titles: \(duplicates)")
    }

    /// The collator only guards its directives against being literally empty
    /// (bash `-z`); a whitespace-only title/subtitle/icon would slip through
    /// generation and still render as a blank card. Compensating control,
    /// same rationale as the kebab-case check above.
    func testEveryGeneratedItemHasNonBlankTitleIconAndSubtitle() {
        for item in GeneratedScenes.all() {
            XCTAssertFalse(item.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                           "A generated DemoItem has a blank title")
            XCTAssertFalse(item.icon.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                           "'\(item.title)' has a blank icon")
            XCTAssertFalse(item.subtitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                           "'\(item.title)' has a blank subtitle")
        }
    }

    /// The Samples tab groups items by category and (per `SamplesTab`)
    /// self-hides an empty section; a category with zero demos almost always
    /// means a `*Scene.swift` file failed to collate. Mirrors Android's "at
    /// least one demo exists per non-AR category" (iOS has no AR-specific
    /// exemption here since AR scenes DO run in this suite, on an iOS sim).
    func testAtLeastOneGeneratedItemExistsPerCategory() {
        let populated = Set(GeneratedScenes.all().map(\.category))
        for category in DemoCategory.allCases {
            XCTAssertTrue(populated.contains(category),
                          "Category '\(category.rawValue)' has no demos — a Scene file likely " +
                          "failed to collate")
        }
    }
}

#endif
