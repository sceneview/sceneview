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

#if DEBUG
import XCTest
import RealityKit
@testable import SceneViewDemo

@MainActor
final class BundledAssetPrimBudgetTests: XCTestCase {

    /// Above the current 47 with room to grow, and low enough to still mean
    /// something: at the ~34 ms/prim measured here, 100 prims is ~3.4 s — the
    /// budget has to stay inside the settle window it exists to protect, so a
    /// larger ceiling would pass while the demo was already stalling.
    private static let meshPrimBudget = 100

    private func meshPrimCount(of entity: Entity) -> Int {
        var count = 0
        var stack = [entity]
        while let e = stack.popLast() {
            if e.components.has(ModelComponent.self) { count += 1 }
            stack.append(contentsOf: e.children)
        }
        return count
    }

    /// `"Models/foo.usdz"` → `"foo"`, matching how
    /// `SketchfabAssetResolver.splitBundlePath` reads a declared fallback path.
    private func bundleName(from declaredPath: String) -> String {
        URL(fileURLWithPath: declaredPath).deletingPathExtension().lastPathComponent
    }

    private func bundledURL(_ name: String) throws -> URL {
        let bundle = Bundle.main
        let url = bundle.url(forResource: name, withExtension: "usdz", subdirectory: "Models")
            ?? bundle.url(forResource: name, withExtension: "usdz")
        return try XCTUnwrap(url, "\(name).usdz missing from the app bundle")
    }

    private func assertUnderMeshPrimBudget(_ name: String) async throws {
        let entity = try await Entity(contentsOf: try bundledURL(name))
        let count = meshPrimCount(of: entity)
        XCTAssertLessThanOrEqual(
            count, Self.meshPrimBudget,
            "\(name).usdz has \(count) mesh prims. RealityKit's simulator " +
            "import cost scales with prim count (~34 ms each), so this pushes " +
            "the slot past the settle window and it renders as absent."
        )
        // The optimisation must not have emptied the model.
        XCTAssertFalse(
            entity.visualBounds(relativeTo: nil).isEmpty,
            "\(name).usdz parsed to empty bounds — it would render nothing"
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
        try await assertUnderMeshPrimBudget("tree_scene")
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
            try await assertUnderMeshPrimBudget(bundleName(from: path))
        }
    }
}
#endif
