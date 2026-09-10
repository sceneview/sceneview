// ViewerAssetTests.swift
//
// Guards the Model Viewer's bundled catalog against the silent-regression class
// of #3584: `BundledViewerModel.thumbnailName` probes `UIImage(named:)` and
// returns `nil` when the imageset is absent, so a model added without its
// `model_thumb_<asset>` tile renders as an anonymous `cube.transparent`
// placeholder and nothing fails. Cyberpunk Hovercar — the iOS App Store hero —
// and Butterfly shipped that way. These assert the whole list resolves.

#if DEBUG

import XCTest
@testable import SceneViewDemo

final class ViewerAssetTests: XCTestCase {

    /// The catalog under test, mirroring `ModelViewerDemo.bundledModels`.
    private let models: [BundledViewerModel] = [
        BundledViewerModel(assetName: "khronos_damaged_helmet", displayName: "Damaged Helmet"),
        BundledViewerModel(assetName: "khronos_fox", displayName: "Fox"),
        BundledViewerModel(assetName: "khronos_lantern", displayName: "Lantern"),
        BundledViewerModel(assetName: "khronos_toy_car", displayName: "Toy Car"),
        BundledViewerModel(assetName: "cyberpunk_hovercar", displayName: "Cyberpunk Hovercar"),
        BundledViewerModel(assetName: "animated_butterfly", displayName: "Butterfly"),
    ]

    private let environments: [ViewerEnvironment] = [
        ViewerEnvironment(assetName: "studio", displayName: "Studio", authoredAsPlace: false),
        ViewerEnvironment(assetName: "studio_warm", displayName: "Studio Warm", authoredAsPlace: false),
        ViewerEnvironment(assetName: "sunset", displayName: "Sunset", authoredAsPlace: true),
        ViewerEnvironment(assetName: "outdoor_cloudy", displayName: "Outdoor Cloudy", authoredAsPlace: true),
        ViewerEnvironment(assetName: "night_sky", displayName: "Night Sky", authoredAsPlace: true),
        ViewerEnvironment(assetName: "rooftop_night", displayName: "Rooftop Night", authoredAsPlace: true),
    ]

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

    func testEveryBundledModelShipsItsUSDZ() {
        for model in models {
            XCTAssertNotNil(
                Bundle.main.url(forResource: model.assetName, withExtension: "usdz"),
                "\(model.displayName) is listed in the picker but \(model.assetName).usdz is not in the bundle."
            )
        }
    }

    /// The #3583 smart default: a studio rig is a light source, so its backdrop
    /// stays hidden; an environment authored as a place is meant to be seen.
    func testOnlyStudioRigsHideTheirBackdropByDefault() {
        let hidden = environments.filter { !$0.authoredAsPlace }.map(\.assetName)
        XCTAssertEqual(hidden, ["studio", "studio_warm"])
    }

    /// A first run must land on an environment whose backdrop is worth drawing,
    /// otherwise "show the environment by default" (#3583) resolves to nothing:
    /// the smart default above would hide the backdrop of a studio rig forever.
    func testFirstRunEnvironmentIsAPlace() {
        let first = environments.first { $0.assetName == "outdoor_cloudy" }
        XCTAssertNotNil(first, "the first-run environment left the catalog")
        XCTAssertEqual(first?.authoredAsPlace, true)
    }
}

#endif
