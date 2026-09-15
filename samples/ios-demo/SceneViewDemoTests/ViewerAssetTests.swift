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
