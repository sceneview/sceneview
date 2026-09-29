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
            let resource = model.bundledResourceName ?? "<none>"
            XCTAssertNotNil(
                Bundle.main.url(forResource: resource, withExtension: "usdz"),
                "\(model.displayName) is listed in the picker but \(resource).usdz is not in the bundle."
            )
        }
    }

    /// Every HD pack model names a stand-in and an id the bundled manifest knows.
    /// "View in AR" never reaches for the HD file (#4147): every model places
    /// a USDZ that ships in the bundle, and an HD-only model offers no AR.
    func testEveryARModelResolvesToABundledResource() {
        for model in ModelViewerDemo.bundledModels {
            let resource = model.arResourceName ?? "<none>"
            XCTAssertNotNil(
                Bundle.main.url(forResource: resource, withExtension: "usdz"),
                "\(model.displayName) would open AR on \(resource).usdz, which is not in the bundle."
            )
        }
        for model in ModelViewerDemo.museumModels {
            XCTAssertNil(model.arResourceName, "\(model.displayName) is HD-only: View in AR must stay off.")
        }
    }

    // MARK: Museum & Space (HD-only)

    /// Same ids, order and titles as Android's "Museum & Space" section; the
    /// pill copy comes from the manifest title, so both must agree.
    func testMuseumSectionMatchesTheSharedContract() {
        let manifest = HDPackManifest.loadBundled()
        let museum = ModelViewerDemo.museumModels
        XCTAssertEqual(museum.map(\.hdPackID),
                       ["apollo11-exterior", "apollo11-interior", "woolly-mammoth", "perseverance"])
        XCTAssertEqual(museum.map(\.displayName),
                       ["Apollo 11 Command Module", "Apollo 11 Interior", "Woolly Mammoth", "Perseverance Rover"])
        for model in museum {
            XCTAssertTrue(model.isHDOnly, "\(model.displayName) must have no bundled stand-in.")
            let asset = manifest.asset(id: model.hdPackID ?? "")
            XCTAssertNotNil(asset, "\(model.displayName): \(model.hdPackID ?? "nil") is absent from ios.json.")
            XCTAssertEqual(asset?.title, model.displayName, "Pill title and picker tile must match.")
            XCTAssertEqual(asset.map { ($0.file as NSString).pathExtension }, "usdz")
            XCTAssertNotNil(asset?.scale, "\(model.displayName) must go on stage at real-world size.")
        }
        // Smithsonian Apollo scans are in centimetres; the rest in metres.
        XCTAssertEqual(museum.map { manifest.asset(id: $0.hdPackID ?? "")?.scale }, [0.01, 0.01, 1, 1])
        // The rover's 23 rigging clips never start on their own.
        XCTAssertEqual(museum.map(\.autoplaysAnimations), [true, true, true, false])
    }

    #if canImport(UIKit)
    /// Picker tile and stage poster (before download) are the same render.
    func testEveryMuseumModelResolvesAThumbnail() {
        for model in ModelViewerDemo.museumModels {
            XCTAssertNotNil(model.thumbnailName, "\(model.displayName) has no model_thumb_\(model.assetName) imageset.")
        }
    }
    #endif

    func testEveryHDPackModelResolvesInTheManifest() {
        let manifest = HDPackManifest.loadBundled()
        // The bundled grid's HD models always have a stand-in; only the
        // Museum & Space section is HD-only (tested above).
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

    /// Every picker card carries its one line, and a model Android also ships
    /// reads Android's `demo_model_desc_*` string word for word. The strings
    /// are read from the Android sources, so a change on either side fails
    /// here instead of drifting silently.
    func testPickerDescriptionsMatchAndroid() throws {
        let android = try Self.androidModelDescriptions()
        // iOS asset name → Android `demo_model_desc_<key>`.
        let keys: [String: String] = [
            "khronos_damaged_helmet": "damaged_helmet",
            "khronos_flight_helmet": "flight_helmet",
            "khronos_lantern": "lantern",
            "khronos_toy_car": "toy_car",
            "hd_apollo11_exterior": "apollo11_exterior",
            "hd_apollo11_interior": "apollo11_interior",
            "hd_woolly_mammoth": "woolly_mammoth",
            "hd_perseverance": "perseverance",
        ]
        // The HD-pack lines reach Android's sources with #4166; until then only
        // the Khronos ones are there. Once any HD line exists, all must.
        let bundledKeys: Set = ["damaged_helmet", "lantern", "toy_car"]
        let hdKeys = Set(keys.values).subtracting(bundledKeys)
        let androidHasHD = hdKeys.contains { android[$0] != nil }
        var compared = 0
        for model in models + ModelViewerDemo.museumModels {
            XCTAssertFalse((model.description ?? "").isEmpty, "\(model.displayName) has no picker description.")
            guard let key = keys[model.assetName] else { continue }
            guard let expected = android[key] else {
                XCTAssertFalse(bundledKeys.contains(key) || androidHasHD,
                               "Android has no demo_model_desc_\(key) for \(model.displayName).")
                continue
            }
            XCTAssertEqual(model.description, expected, "\(model.displayName) drifted from Android.")
            compared += 1
        }
        XCTAssertGreaterThanOrEqual(compared, bundledKeys.count)
    }

    /// `demo_model_desc_*` from the Android demo's string resources, keyed
    /// without the prefix. Simulator tests run on the host file system, so the
    /// sources are read in place from the checkout.
    static func androidModelDescriptions(file: StaticString = #filePath) throws -> [String: String] {
        let values = URL(fileURLWithPath: "\(file)")
            .deletingLastPathComponent()      // SceneViewDemoTests
            .deletingLastPathComponent()      // ios-demo
            .deletingLastPathComponent()      // samples
            .appendingPathComponent("android-demo/src/main/res/values")
        let pattern = try NSRegularExpression(
            pattern: #"<string name="demo_model_desc_([a-z0-9_]+)">([^<]*)</string>"#)
        var out: [String: String] = [:]
        for name in ["strings_demo_model_viewer.xml", "strings_hd_pack.xml"] {
            let xml = try String(contentsOf: values.appendingPathComponent(name), encoding: .utf8)
            for match in pattern.matches(in: xml, range: NSRange(xml.startIndex..., in: xml)) {
                guard let key = Range(match.range(at: 1), in: xml),
                      let text = Range(match.range(at: 2), in: xml) else { continue }
                out[String(xml[key])] = String(xml[text])
                    .replacingOccurrences(of: #"\'"#, with: "'")
                    .replacingOccurrences(of: "&amp;", with: "&")
            }
        }
        return out
    }

    /// A museum scan opens under the neutral Studio, never under the garden's
    /// green; every other model keeps the first-run environment.
    func testMuseumModelsOpenUnderStudio() {
        for model in ModelViewerDemo.museumModels {
            let env = ModelViewerDemo.openingEnvironment(for: model)
            XCTAssertEqual(env.assetName, "studio_warm", "\(model.displayName)")
            XCTAssertEqual(env.displayName, "Studio")
        }
        for model in models {
            XCTAssertEqual(ModelViewerDemo.openingEnvironment(for: model), ModelViewerDemo.defaultEnvironment,
                           "\(model.displayName)")
        }
    }
}

/// The Model Viewer's lighting sequences (``ViewerLighting``), same rule as
/// Android's `LaunchedEffect(isMuseumModel)` (#4166): Studio replaces the
/// default garden for a museum scan, and only Studio the app put there is
/// taken back when the shelf is left.
@MainActor
final class ViewerLightingTests: XCTestCase {
    private var garden: ViewerEnvironment { ModelViewerDemo.defaultEnvironment }
    private var bundled: BundledViewerModel { ModelViewerDemo.bundledModels[0] }
    private var museum: BundledViewerModel { ModelViewerDemo.museumModels[0] }
    private var otherMuseum: BundledViewerModel { ModelViewerDemo.museumModels[1] }
    private func env(_ asset: String) -> ViewerEnvironment {
        ModelViewerDemo.environments.first { $0.assetName == asset }!
    }

    func testMuseumAndBundledRoundTripWithoutAPick() {
        var lighting = ViewerLighting()
        XCTAssertEqual(lighting.environment, garden)
        lighting.select(museum)
        XCTAssertEqual(lighting.environment, env("studio_warm"))
        lighting.select(otherMuseum)
        XCTAssertEqual(lighting.environment, env("studio_warm"), "museum to museum keeps Studio")
        lighting.select(bundled)
        XCTAssertEqual(lighting.environment, garden, "leaving the shelf gives the garden back")
        lighting.select(bundled)
        XCTAssertEqual(lighting.environment, garden)
    }

    func testUserPickSticksAcrossModels() {
        var lighting = ViewerLighting()
        lighting.pick(env("sunset"))
        lighting.select(museum)
        XCTAssertEqual(lighting.environment, env("sunset"), "a picked lighting is never overridden")
        lighting.select(bundled)
        XCTAssertEqual(lighting.environment, env("sunset"))
    }

    func testPickOnTheShelfSticksWhenLeaving() {
        var lighting = ViewerLighting()
        lighting.select(museum)
        lighting.pick(env("studio"))
        lighting.select(bundled)
        XCTAssertEqual(lighting.environment, env("studio"))
        // Picking Studio itself is the user's choice too: it stays.
        lighting.pick(env("studio_warm"))
        lighting.select(bundled)
        XCTAssertEqual(lighting.environment, env("studio_warm"))
    }

    func testPickingTheGardenBackLetsTheShelfSwapAgain() {
        // Android's rule keys on "still the garden", not on who chose it.
        var lighting = ViewerLighting()
        lighting.pick(garden)
        lighting.select(museum)
        XCTAssertEqual(lighting.environment, env("studio_warm"))
    }

    func testResetReturnsToTheModelsOpeningLighting() {
        var lighting = ViewerLighting()
        lighting.select(museum)
        lighting.pick(env("night_sky"))
        lighting.reset(for: museum)
        XCTAssertEqual(lighting.environment, env("studio_warm"))
        lighting.select(bundled)
        XCTAssertEqual(lighting.environment, garden, "Studio from a reset is still the app's")

        lighting.pick(env("sunset"))
        lighting.reset(for: bundled)
        XCTAssertEqual(lighting.environment, garden)
    }

    func testResetWithASurpriseModelOnStageUsesTheGarden() {
        var lighting = ViewerLighting()
        lighting.select(museum)
        lighting.reset(for: nil)
        XCTAssertEqual(lighting.environment, garden)
    }

    func testGlobalResetIsFirstRun() {
        var lighting = ViewerLighting()
        lighting.select(museum)
        lighting.pick(env("sunset"))
        lighting.resetAll()
        XCTAssertEqual(lighting.environment, garden)
        XCTAssertFalse(lighting.museumApplied)
        lighting.select(bundled)
        XCTAssertEqual(lighting.environment, garden)
    }

    /// `qa_mode` sets the store stage as a pick: no model switch drops it.
    func testStoreStageSurvivesModelSwitches() {
        var lighting = ViewerLighting()
        lighting.pick(env("studio_warm"))
        lighting.select(museum)
        lighting.select(bundled)
        XCTAssertEqual(lighting.environment, env("studio_warm"))
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
