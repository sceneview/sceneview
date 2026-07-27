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

@MainActor
final class BundledAssetPrimBudgetTests: XCTestCase {

    /// Comfortably above the current 47, far below the 2 712 that broke it.
    private static let meshPrimBudget = 200

    private func meshPrimCount(of entity: Entity) -> Int {
        var count = 0
        var stack = [entity]
        while let e = stack.popLast() {
            if e.components.has(ModelComponent.self) { count += 1 }
            stack.append(contentsOf: e.children)
        }
        return count
    }

    private func bundledURL(_ name: String) throws -> URL {
        let bundle = Bundle.main
        let url = bundle.url(forResource: name, withExtension: "usdz", subdirectory: "Models")
            ?? bundle.url(forResource: name, withExtension: "usdz")
        return try XCTUnwrap(url, "\(name).usdz missing from the app bundle")
    }

    /// `tree_scene.usdz` backs the MultiModelDemo Tree slot, the Explore and AR
    /// tabs' "Tree Scene" entry, and the keyless fallback of three
    /// `ar_placement` slugs — so a prim-count regression here stalls several
    /// demos at once.
    func testTreeSceneStaysUnderMeshPrimBudget() async throws {
        let entity = try await Entity(contentsOf: try bundledURL("tree_scene"))
        let count = meshPrimCount(of: entity)
        XCTAssertLessThanOrEqual(
            count, Self.meshPrimBudget,
            "tree_scene.usdz has \(count) mesh prims. RealityKit's simulator " +
            "import cost scales with prim count (~34 ms each), so this pushes " +
            "the slot past the settle window and it renders as absent."
        )
        // The optimisation must not have emptied the model.
        XCTAssertFalse(
            entity.visualBounds(relativeTo: nil).isEmpty,
            "tree_scene.usdz parsed to empty bounds — it would render nothing"
        )
    }
}
