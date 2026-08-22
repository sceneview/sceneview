// BundledAssetPrimBudgetTests.swift
//
// Guards the root cause behind "the MultiModelDemo Tree slot never renders on
// the iOS Simulator": RealityKit's USD import cost is driven by **prim count**,
// not by file size or triangle count. Measured on an iPhone 17 Pro Max
// simulator (iOS 26.3) with `Entity(contentsOf:)`:
//
//   | asset                   | bytes  | mesh prims | triangles | parse    |
//   |-------------------------|--------|------------|-----------|----------|
//   | retro_piano.usdz        |  1.9 MB|          1 |    21 555 |   1.12 s |
//   | earthquake_california   | 25.3 MB|         17 | 1 305 082 |   0.85 s |
//   | black_dragon.usdz       | 16.0 MB|          2 |    37 998 |   2.91 s |
//   | tree_scene.usdz (before)| 14.8 MB|     2 712  |   256 652 |  91.71 s |
//   | tree_scene.usdz (after) | 14.1 MB|        47  |    83 622 |   2.58 s |
//
// 25 MB / 1.3 M triangles parses in under a second; 2 712 mesh prims takes a
// minute and a half — past any settle window a screenshot or a QA pass waits
// for, which is why the slot looked permanently absent while never throwing.
// 2 665 of those prims were individual sub-pixel grass tufts and were stripped
// from the asset.
//
// A prim-count budget is the deterministic form of that guard: unlike a
// wall-clock assertion it cannot flake on a loaded CI host, and it fails for
// the actual reason a future asset would regress.

import XCTest
import RealityKit
// Deliberately NOT wrapped in `#if DEBUG`, unlike some siblings here.
// `ENABLE_TESTABILITY` is set only in the two Debug configurations, so under a
// Release test run this import fails to COMPILE — loudly. Wrapped, the file
// would instead compile to nothing and the suite would report green with both
// guards silently gone, which is the failure this file exists to prevent.
@testable import SceneViewDemo

@MainActor
final class BundledAssetPrimBudgetTests: XCTestCase {

    /// Above the current 47 with room to grow, and low enough to still mean
    /// something: at the ~34 ms/prim measured here, 100 prims is ~3.4 s — the
    /// budget has to stay inside the settle window it exists to protect, so a
    /// larger ceiling would pass while the demo was already stalling.
    private static let meshPrimBudget = 100

    /// The host app's bundle — where the resolver looks for a fallback.
    private var bundle: Bundle { .main }

    private func meshPrimCount(of entity: Entity) -> Int {
        var count = 0
        var stack = [entity]
        while let e = stack.popLast() {
            if e.components.has(ModelComponent.self) { count += 1 }
            stack.append(contentsOf: e.children)
        }
        return count
    }

    /// Resolve a declared `fallbackBundledPath` the way the resolver itself
    /// does — subdirectory *derived from the path*, then a flat-bundle retry
    /// (`SketchfabAssetResolver.splitBundlePath` / `bundleSubdirectory(for:)`).
    ///
    /// Today the derived subdirectory never actually matches: `Models` is a
    /// `PBXGroup`, not a folder reference, so every asset is copied to the
    /// bundle root and BOTH the resolver and this test resolve on the flat
    /// retry — measured, by declaring a path under a bogus subdirectory and
    /// watching it still resolve. Mirroring the resolver is therefore not a
    /// bug fix; it is what keeps the two from drifting if `Models` is ever
    /// made a real folder reference, at which point the subdirectory starts
    /// mattering to both at once.
    private func bundledURL(declaredPath: String) throws -> URL {
        let file = (declaredPath as NSString).lastPathComponent
        let name = (file as NSString).deletingPathExtension
        let ext = (file as NSString).pathExtension
        let components = declaredPath.split(separator: "/").dropLast()
        let subdirectory = components.isEmpty ? nil : components.joined(separator: "/")

        let url = bundle.url(forResource: name, withExtension: ext, subdirectory: subdirectory)
            ?? bundle.url(forResource: name, withExtension: ext)
        return try XCTUnwrap(url, "\(declaredPath) missing from the app bundle")
    }

    private func assertUnderMeshPrimBudget(declaredPath: String) async throws {
        let entity = try await Entity(contentsOf: try bundledURL(declaredPath: declaredPath))
        let count = meshPrimCount(of: entity)
        XCTAssertLessThanOrEqual(
            count, Self.meshPrimBudget,
            "\(declaredPath) has \(count) mesh prims. RealityKit's simulator " +
            "import cost scales with prim count (~34 ms each), so this pushes " +
            "the slot past the settle window and it renders as absent."
        )
        // The optimisation must not have emptied the model.
        XCTAssertFalse(
            entity.visualBounds(relativeTo: nil).isEmpty,
            "\(declaredPath) parsed to empty bounds — it would render nothing"
        )
    }

    /// `tree_scene.usdz` backs the MultiModelDemo Tree slot, the Explore and AR
    /// tabs' "Tree Scene" entry, and the `park` "Oak Trees" slug's keyless
    /// fallback — so a prim-count regression here stalls several demos at once.
    /// It backed three `ar_placement` slugs too until #2940 repointed them.
    ///
    /// Named explicitly, and kept even though the registry-driven test below
    /// happens to cover it today: those two Explore/AR entries reach the asset
    /// by filename, not through a `SketchfabSlug`, so repointing "Oak Trees"
    /// would silently drop this asset out of the registry-driven sweep while it
    /// was still shipping in two tabs.
    func testTreeSceneStaysUnderMeshPrimBudget() async throws {
        try await assertUnderMeshPrimBudget(declaredPath: "Models/tree_scene.usdz")
    }

    /// Every asset the registry can hand a keyless build, read **from the
    /// registry** rather than from a literal list: repointing any
    /// `fallbackBundledPath` re-aims this guard instead of leaving it on the
    /// assets that used to be declared. That matters because a wrong repoint is
    /// the #2940 defect itself, and `SampleAssetsTests.testEveryEntryHasFallback`
    /// only asserts the string is non-empty — nothing else proves a declared
    /// path resolves to a file that is actually in the app bundle.
    ///
    /// So this covers three failure modes at once: a path that resolves to
    /// nothing (typo, or an asset never added to the Resources build phase), an
    /// asset heavy enough to blow the settle window, and one that parses to
    /// empty bounds.
    func testEveryDeclaredFallbackResolvesAndStaysUnderMeshPrimBudget() async throws {
        let declared = Set(SampleAssets.all.map(\.fallbackBundledPath))
        XCTAssertFalse(
            declared.isEmpty,
            "SampleAssets declared no fallbacks — this guard would be vacuous"
        )
        for path in declared.sorted() {
            try await assertUnderMeshPrimBudget(declaredPath: path)
        }
    }

    /// #2355's distinctness rule, enforced for `ar_placement` — the one
    /// category whose demos can put several *different* slugs on screen at the
    /// same time.
    ///
    /// `ARPlacementDemo` and `ARInstantPlacementDemo` both accumulate into a
    /// `placedAnchors` array: every tap adds an anchor and nothing removes the
    /// earlier ones until "Clear all placed models". So a user can arm one chip,
    /// tap, arm another, tap — and in keyless mode two differently-labelled
    /// objects render as the same bundled asset, side by side in one frame.
    /// That is #2940's defect (a fallback reading as the real asset) with the
    /// contradiction visible without even leaving the screen.
    ///
    /// The prim-budget test above cannot catch this: it de-duplicates paths
    /// into a `Set` precisely to avoid parsing the same asset twice, so
    /// collapsing all six slugs onto one fallback would *shrink* its workload
    /// and still pass green. That is how #2940 shipped.
    ///
    /// The slug set is derived from the registry by category rather than from a
    /// literal list of names, so a seventh `ar_placement` slug is covered the
    /// day it lands.
    ///
    /// **Scope is deliberately `ar_placement` only.** Other categories share
    /// fallbacks legitimately — all four `solar` butterflies genuinely stand in
    /// for one another — or carry label mismatches tracked under the #2960
    /// umbrella. A registry-wide distinctness assertion would be red on arrival
    /// and would therefore guard nothing.
    func testARPlacementFallbacksArePairwiseDistinct() throws {
        let slugs = SampleAssets.byCategory["ar_placement"] ?? []
        // Distinctness over fewer than two slugs is vacuously true, so a
        // registry that lost the category would pass silently without this.
        XCTAssertGreaterThan(
            slugs.count, 1,
            "ar_placement has \(slugs.count) slug(s) — pairwise distinctness " +
            "is vacuous below two, so this guard is no longer guarding anything"
        )

        /// First slug to claim each fallback path, in registry order.
        var claimedBy: [String: String] = [:]
        for slug in slugs {
            let path = slug.fallbackBundledPath
            if let owner = claimedBy[path] {
                XCTFail(
                    "ar_placement fallback collision: \"\(owner)\" and " +
                    "\"\(slug.displayName)\" both fall back to \(path). Both " +
                    "demos in this category place several models in one scene, " +
                    "so in keyless mode the two labels render the identical " +
                    "asset — the #2940 defect. Point one of them at a distinct " +
                    "bundled model (#2355)."
                )
            } else {
                claimedBy[path] = slug.displayName
            }
        }
    }

    /// #2960's rule: every slug's fallback is either the same kind of thing as
    /// its label, or the slug declares `fallbackRole: .placeholder` so the pill
    /// reads "Offline placeholder" instead of pretending.
    ///
    /// `.subjectMatch` is the init default, so a new slug would claim a match
    /// by omission. This allowlist is what stops that: a `.subjectMatch` slug
    /// that is not listed here fails, and listing one is a reviewed claim that
    /// the bundled asset really is a fox for a fox, a lantern for a lamp.
    func testEveryFallbackIsASubjectMatchOrADeclaredPlaceholder() {
        let reviewedSubjectMatches: Set<String> = [
            // solar — a butterfly for a butterfly
            "Fantasy Butterfly", "Fluttering Butterfly", "Animated Butterfly", "Animated Butterflies",
            // gallery
            "PBR Low-Poly Fox",      // khronos_fox
            "Desk Lamp",             // khronos_lantern
            // animation — an animated humanoid for a robot / mech (#3007)
            "Retro TV Robot", "Catfish Mech", "Walking Robot", "Enforcer Mk1",
            // park — the one tree_scene consumer that IS trees
            "Oak Trees",
            // ar_placement
            "Floor Lamp",            // khronos_lantern
            // materials — an insect scan for an insect scan
            "Iridescent Beetle",     // mosquito_amber
        ]
        for slug in SampleAssets.all {
            switch slug.fallbackRole {
            case .placeholder:
                XCTAssertFalse(
                    reviewedSubjectMatches.contains(slug.displayName),
                    "\"\(slug.displayName)\" is declared a placeholder but is also on " +
                    "the reviewed subject-match allowlist — pick one"
                )
            case .subjectMatch:
                XCTAssertTrue(
                    reviewedSubjectMatches.contains(slug.displayName),
                    "\"\(slug.displayName)\" claims its fallback \(slug.fallbackBundledPath) " +
                    "is the same subject as its label, but nobody reviewed that claim. " +
                    "Either add it to the allowlist above (if it really is) or declare " +
                    "`fallbackRole: .placeholder` in SampleAssets (#2960)."
                )
            }
        }
    }

    /// The placeholder flag has to reach the screen, or it is just metadata.
    func testPillSaysPlaceholderOnlyForABundledPlaceholder() {
        XCTAssertEqual(AssetSourcePill.label(state: .bundled, isPlaceholder: true), "Offline placeholder")
        XCTAssertEqual(AssetSourcePill.label(state: .bundled, isPlaceholder: false), "Offline model")
        // A model that actually streamed is never a placeholder.
        XCTAssertEqual(AssetSourcePill.label(state: .streamed, isPlaceholder: true), "Streamed (cached)")
        XCTAssertEqual(AssetSourcePill.label(state: .streaming, isPlaceholder: true), "Streaming…")
    }
}
