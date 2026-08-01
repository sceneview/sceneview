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
    /// It backed three `ar_placement` slugs too until #2940 repointed them; those
    /// keep their own budget check below.
    func testTreeSceneStaysUnderMeshPrimBudget() async throws {
        try await assertUnderMeshPrimBudget("tree_scene")
    }

    /// The keyless fallbacks #2940 repointed the *Potted Monstera*, *Wooden End
    /// Table* and *Floor Lamp* `ar_placement` slugs to. They sit on exactly the
    /// stall-sensitive path the tree did — the AR placement picker in a keyless
    /// build, which is both the default local build and the App Store build —
    /// so they carry the same budget.
    func testARPlacementFallbacksStayUnderMeshPrimBudget() async throws {
        for name in ["khronos_damaged_helmet", "khronos_toy_car", "khronos_lantern"] {
            try await assertUnderMeshPrimBudget(name)
        }
    }
}
