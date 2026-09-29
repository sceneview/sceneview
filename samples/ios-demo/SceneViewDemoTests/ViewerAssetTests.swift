// ViewerAssetTests.swift
//
// Guards the Model Viewer's bundled catalog against the silent-regression class
// of #3584: `BundledViewerModel.thumbnailName` probes `UIImage(named:)` and
// returns `nil` when the imageset is absent, so a model added without its
// `model_thumb_<asset>` tile renders as an anonymous `cube.transparent`
// placeholder and nothing fails. Cyberpunk Hovercar — the iOS App Store hero —
// and Butterfly shipped that way. These assert the whole list resolves.
//
// The lists under test are `ModelViewerDemo.bundledModels` / `.environments`
// themselves (`internal`, exposed to this target via `@testable import`) —
// NOT a hand-copied duplicate. An earlier version of this file kept its own
// copy of the catalog: it happened to match on introduction, but nothing
// forced it to stay in sync, so a model added to `ModelViewerDemo` alone
// (the easy, obvious edit) without a matching update here would pass the
// suite while the real app regressed — the exact silent-failure shape #3584
// was filed about. Testing the live array closes that gap.

#if DEBUG

import XCTest
import RealityKit
@testable import SceneViewDemo

// `ModelViewerDemo` is a SwiftUI `View`; its `static let` catalogs are
// therefore main-actor isolated under Swift 6 strict concurrency, same as
// `BundledAssetPrimBudgetTests` next door.
@MainActor
final class ViewerAssetTests: XCTestCase {

    /// The catalog under test — the real one the app renders, not a copy.
    private let models: [BundledViewerModel] = ModelViewerDemo.bundledModels

    private let environments: [ViewerEnvironment] = ModelViewerDemo.environments

    #if canImport(UIKit)
    func testEveryBundledModelResolvesAThumbnail() {
        for model in models {
            XCTAssertNotNil(
                model.thumbnailName,
                "\(model.displayName) has no model_thumb_\(model.assetName) imageset — it renders as a blank cube (#3584)."
            )
        }
    }

    func testEveryEnvironmentResolvesAThumbnail() {
        for environment in environments {
            XCTAssertNotNil(
                environment.thumbnailName,
                "\(environment.displayName) has no env_thumb_\(environment.assetName) imageset."
            )
        }
    }
    #endif

    /// A bundled model ships its own USDZ; an HD pack model ships its
    /// stand-in instead, so the stage is never empty offline.
    func testEveryBundledModelShipsItsUSDZ() {
        for model in models {
            XCTAssertNotNil(
                Bundle.main.url(forResource: model.bundledResourceName, withExtension: "usdz"),
                "\(model.displayName) is listed in the picker but \(model.bundledResourceName).usdz is not in the bundle."
            )
        }
    }

    /// Every HD pack model names a stand-in and an id the bundled manifest knows.
    /// "View in AR" never reaches for the HD file (#4147): every model places
    /// a USDZ that ships in the bundle.
    func testEveryARModelResolvesToABundledResource() {
        for model in ModelViewerDemo.bundledModels {
            XCTAssertNotNil(
                Bundle.main.url(forResource: model.arResourceName, withExtension: "usdz"),
                "\(model.displayName) would open AR on \(model.arResourceName).usdz, which is not in the bundle."
            )
        }
    }

    func testEveryHDPackModelResolvesInTheManifest() {
        let manifest = HDPackManifest.loadBundled()
        for model in models where model.hdPackID != nil {
            XCTAssertNotNil(model.standInAssetName, "\(model.displayName) has no bundled stand-in.")
            XCTAssertNotNil(manifest.asset(id: model.hdPackID!),
                            "\(model.displayName) points at HD asset \(model.hdPackID!), absent from ios.json.")
        }
    }

    /// The home hero renders a real bundled model, not a picture of one. If its
    /// asset ever leaves the bundle the card silently falls back to the poster
    /// frame forever — nothing throws, nothing fails, the home screen just
    /// stops being alive. Same silent-regression class as #3584.
    func testHomeHeroModelShipsItsUSDZ() {
        XCTAssertNotNil(
            Bundle.main.url(forResource: HomeHero.heroAssetName, withExtension: "usdz"),
            "the home hero loads \(HomeHero.heroAssetName).usdz, which is not in the bundle."
        )
    }

    /// The #3583 smart default: a studio rig is a light source, so its backdrop
    /// stays hidden; an environment authored as a place is meant to be seen.
    func testOnlyStudioRigsHideTheirBackdropByDefault() {
        let hidden = environments.filter { !$0.authoredAsPlace }.map(\.assetName)
        XCTAssertEqual(hidden, ["studio_warm", "studio"])
    }

    /// A first run must land on an environment whose backdrop is worth drawing,
    /// otherwise "show the environment by default" (#3583) resolves to nothing:
    /// the smart default above would hide the backdrop of a studio rig forever.
    func testFirstRunEnvironmentIsAPlace() {
        let first = environments.first { $0.assetName == "chinese_garden" }
        XCTAssertNotNil(first, "the first-run environment left the catalog")
        XCTAssertEqual(first?.authoredAsPlace, true)
    }
}

/// Pins the Surprise-me coherence check (#4012): a broken USDZ conversion —
/// a small subject in a cloud of scattered shards — never goes on stage,
/// while ordinary single-mesh and multi-part models still do.
@MainActor
final class SurpriseModelCheckTests: XCTestCase {

    private func box(_ center: SIMD3<Float>, _ size: Float) -> BoundingBox {
        BoundingBox(min: center - size / 2, max: center + size / 2)
    }

    func testSingleMeshPasses() {
        XCTAssertTrue(SurpriseModelCheck.isCoherent(parts: [box(.zero, 1)]))
    }

    func testNoMeshFails() {
        XCTAssertFalse(SurpriseModelCheck.isCoherent(parts: []))
    }

    func testSmallSubjectInScatteredShardsFails() {
        // The "PBR Firefighter Helmet" shape: a 0.2 m helmet at the origin and
        // 60 small shards strewn over a 4 m cube, none touching another.
        var parts = [box(.zero, 0.2)]
        for i in 0..<60 {
            let t = Float(i)
            let p = SIMD3<Float>(sin(t * 1.7), cos(t * 2.3), sin(t * 0.9 + 1)) * 2
            parts.append(box(p, 0.03))
        }
        XCTAssertFalse(SurpriseModelCheck.isCoherent(parts: parts))
    }

    func testDominantPartWithSmallDetailsPasses() {
        // A car body with four wheels and a few small trims.
        var parts = [BoundingBox(min: [-2, 0, -1], max: [2, 1.2, 1])]
        for x: Float in [-1.4, 1.4] {
            for z: Float in [-1, 1] { parts.append(box([x, 0.3, z], 0.6)) }
        }
        XCTAssertTrue(SurpriseModelCheck.isCoherent(parts: parts))
    }

    func testManySmallTouchingPartsPass() {
        // A fence of 40 planks, each touching its neighbours: no dominant
        // part, but nothing is isolated.
        let parts = (0..<40).map { i in
            BoundingBox(min: [Float(i) * 0.1, 0, 0], max: [Float(i) * 0.1 + 0.1, 1, 0.02])
        }
        XCTAssertTrue(SurpriseModelCheck.isCoherent(parts: parts))
    }

    func testPartBoundsReadsNestedTransforms() {
        let root = Entity()
        let child = ModelEntity(mesh: .generateBox(size: 0.5))
        child.position = [3, 0, 0]
        let holder = Entity()
        holder.position = [0, 2, 0]
        holder.addChild(child)
        root.addChild(holder)
        root.addChild(ModelEntity(mesh: .generateBox(size: 1)))

        let parts = SurpriseModelCheck.partBounds(of: root)
        XCTAssertEqual(parts.count, 2)
        let moved = parts.first { $0.center.x > 1 }
        XCTAssertEqual(moved?.center.x ?? 0, 3, accuracy: 1e-4)
        XCTAssertEqual(moved?.center.y ?? 0, 2, accuracy: 1e-4)
    }
}

#endif
