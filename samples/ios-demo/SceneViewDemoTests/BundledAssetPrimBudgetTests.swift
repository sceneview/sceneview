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

    /// Ceiling applied to *every* bundled asset by
    /// `testEveryBundledModelStaysUnderMeshPrimBudget`. Deliberately looser
    /// than [meshPrimBudget]: it is set from a sweep of the real bundle
    /// rather than extrapolated from `tree_scene` — see that test's docs for
    /// the measured table and why 100 would fail two assets that parse in
    /// under 1.2 s.
    private static let classMeshPrimBudget = 500

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

    /// The bug class is "any bundled USDZ with too many prims stalls past the
    /// settle window", not "tree_scene specifically". Budgeting one asset
    /// leaves the next one added to `Models/` unguarded — which is how
    /// `tree_scene` shipped in the first place.
    ///
    /// **Why a looser ceiling than [meshPrimBudget].** The 34 ms/prim figure
    /// measured on `tree_scene` does not transpose to the rest of the bundle.
    /// Sweeping all 31 assets on this host (iPhone 17 Pro Max simulator,
    /// iOS 26.3) took 19.1 s on a warm simulator, and the two heaviest are
    /// nowhere near a stall (per-asset times below are from that warm run):
    ///
    ///   | asset                   | mesh prims | parse  |
    ///   |-------------------------|------------|--------|
    ///   | shelby_cobra.usdz       |        155 |  0.92 s|
    ///   | cyberpunk_hovercar.usdz |        126 |  1.15 s|
    ///   | porsche_911.usdz        |         75 |  0.70 s|
    ///   | tree_scene.usdz         |         47 |  0.51 s|
    ///
    /// That is ~6-9 ms/prim, not 34 — the 2 665 sub-pixel grass tufts were a
    /// pathological shape for the importer, not a linear cost anyone can
    /// extrapolate from. Holding the whole bundle to 100 prims would fail on
    /// two assets that parse in under 1.2 s, so the class ceiling is set from
    /// the measurement: ~3x above today's heaviest asset, still 5x under the
    /// only shape ever observed to stall (2 712 prims / 91.71 s). It is a
    /// coarse regression guard, not a performance prediction — the
    /// prims-to-time relation is demonstrably not linear.
    ///
    /// Suite cost is real and paid on every PR run (`ios.yml`), and the honest
    /// figure is the cold one: 19.1 s on a warm simulator but **47.6 s on a
    /// freshly created one**, which is CI's case — it took the whole
    /// `SceneViewDemoTests` suite from ~3 s to 50.4 s (61 tests). If that ever
    /// outgrows the CI budget, narrow the sweep to the demo hot-path assets
    /// rather than dropping back to a single name.
    ///
    /// The assertion is known to bite rather than assumed to: run against the
    /// tighter 100-prim ceiling it failed on `cyberpunk_hovercar` (126) and
    /// `shelby_cobra` (155), which is how the non-linear cost above was found.
    func testEveryBundledModelStaysUnderMeshPrimBudget() async throws {
        // Enumerate the resource tree rather than asking for
        // `urls(forResourcesWithExtension:subdirectory:)`: measured on this
        // host, that call answers an EMPTY (non-nil) array for "Models" even
        // though `url(forResource:withExtension:subdirectory:)` resolves
        // `tree_scene` from it — so a `??` fallback never fires and the sweep
        // silently covers nothing.
        let root = try XCTUnwrap(Bundle.main.resourceURL, "app bundle has no resource URL")
        let enumerated = FileManager.default.enumerator(
            at: root,
            includingPropertiesForKeys: nil
        )
        let urls = (enumerated?.allObjects as? [URL] ?? [])
            .filter { $0.pathExtension.lowercased() == "usdz" }
        XCTAssertFalse(
            urls.isEmpty,
            "no bundled .usdz found — the sweep would pass by covering nothing"
        )

        var offenders: [String] = []
        var heaviest = (name: "", count: 0)
        let sweepStarted = Date()
        for url in urls.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
            let entity = try await Entity(contentsOf: url)
            let count = meshPrimCount(of: entity)
            if count > heaviest.count { heaviest = (url.lastPathComponent, count) }
            if count > Self.classMeshPrimBudget {
                offenders.append("\(url.lastPathComponent): \(count) mesh prims")
            }
        }
        print(String(
            format: "[prim-budget] swept %d bundled .usdz in %.1fs — heaviest %@ (%d prims)",
            urls.count, Date().timeIntervalSince(sweepStarted), heaviest.name, heaviest.count
        ))

        XCTAssertTrue(
            offenders.isEmpty,
            "bundled assets over the \(Self.classMeshPrimBudget)-prim class budget. "
            + "RealityKit's USD import cost grows with prim count and does so "
            + "non-linearly: the one shape ever measured to stall was 2 712 prims / "
            + "91.71 s, which rendered as absent inside any settle window. Re-measure "
            + "the offender's parse time before deciding whether to strip it or raise "
            + "the ceiling: \(offenders.joined(separator: ", "))"
        )
    }
}
