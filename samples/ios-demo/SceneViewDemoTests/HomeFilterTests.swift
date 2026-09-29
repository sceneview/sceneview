// HomeFilterTests.swift
//
// Pure-function tests for the Showcase home filter (`HomeFilter.swift`) —
// the iOS mirror of Android's `HomeFilterTest`. No SwiftUI, no registry:
// `filterDemos` is fed hand-built `HomeSearchEntry` rows.

#if DEBUG

import XCTest
@testable import SceneViewDemo

final class HomeFilterTests: XCTestCase {

    private let entries: [HomeSearchEntry] = [
        HomeSearchEntry(id: "fog", title: "Fog", subtitle: "Height fog and atmosphere",
                        section: .create, category: .lighting, tags: ["fog", "atmosphere"], order: 13),
        HomeSearchEntry(id: "model-viewer", title: "Model Viewer", subtitle: "Load and display 3D models",
                        section: .view3d, category: .basics3D, tags: ["gltf", "hdr", "ar"], order: 1),
        HomeSearchEntry(id: "ar-placement", title: "Tap to Place", subtitle: "Tap a detected plane to place a model",
                        section: .placeAR, category: .ar, tags: ["ar", "plane"], order: 6),
        HomeSearchEntry(id: "physics", title: "Physics", subtitle: "Rigid bodies",
                        section: .view3d, category: .interaction, tags: [], order: 999),
    ]

    func testBlankQueryAndAllCategoryReturnsEverythingInEditorialOrder() {
        let ids = filterDemos(entries, section: nil, query: "   ").map(\.id)
        XCTAssertEqual(ids, ["model-viewer", "ar-placement", "fog", "physics"])
    }

    func testSectionRestrictsToThatSection() {
        XCTAssertEqual(filterDemos(entries, section: .placeAR, query: "").map(\.id), ["ar-placement"])
        XCTAssertEqual(filterDemos(entries, section: .view3d, query: "").map(\.id), ["model-viewer", "physics"])
    }

    func testQueryMatchesTitleSubtitleSectionCategoryAndTagsCaseInsensitively() {
        XCTAssertEqual(filterDemos(entries, section: nil, query: "FOG").map(\.id), ["fog"])
        XCTAssertEqual(filterDemos(entries, section: nil, query: "rigid").map(\.id), ["physics"])
        XCTAssertEqual(filterDemos(entries, section: nil, query: "lighting").map(\.id), ["fog"])
        XCTAssertEqual(filterDemos(entries, section: nil, query: "gltf").map(\.id), ["model-viewer"])
        XCTAssertEqual(filterDemos(entries, section: nil, query: "record").map(\.id), ["fog"])
    }

    func testEveryWordMustMatch() {
        XCTAssertEqual(filterDemos(entries, section: nil, query: "ar plane").map(\.id), ["ar-placement"])
        XCTAssertTrue(filterDemos(entries, section: nil, query: "ar nothing-here").isEmpty)
    }

    func testSectionAndQueryCombine() {
        XCTAssertTrue(filterDemos(entries, section: .create, query: "model").isEmpty)
        XCTAssertEqual(filterDemos(entries, section: .view3d, query: "model").map(\.id), ["model-viewer"])
    }

    func testEntriesWithoutOrderSortLast() {
        let ids = filterDemos(entries, section: nil, query: "").map(\.id)
        XCTAssertEqual(ids.last, "physics")
    }
}

/// `DemoFreshness` — the same cases as Android's `DemoFreshnessTest`, so the
/// two platforms flag the same cards from the same declarations.
@MainActor
final class DemoFreshnessTests: XCTestCase {
    private func recent(_ version: String?, _ build: String, window: Int = DemoFreshness.windowMinors) -> Bool {
        DemoFreshness.isRecent(version, buildVersion: build, window: window)
    }

    func testWindowCoversTheBuildAndThePreviousMinorOnly() {
        XCTAssertTrue(recent("4.35.0", "4.35.0"))
        XCTAssertTrue(recent("4.34.0", "4.35.0"))
        XCTAssertFalse(recent("4.33.0", "4.35.0"))
        XCTAssertFalse(recent("4.33.9", "4.35.2"))
    }

    func testAheadOfTheBuildIsFreshAndAnOlderMajorIsNot() {
        XCTAssertTrue(recent("4.35.0", "4.34.0"))
        XCTAssertTrue(recent("5.0.0", "4.34.0"))
        XCTAssertFalse(recent("4.99.0", "5.0.0"))
    }

    func testSuffixesAreIgnoredAndMalformedVersionsNeverEarnAChip() {
        XCTAssertTrue(recent("4.35.0", "4.35.0-main.abc1234"))
        XCTAssertTrue(recent("4.35.0", "4.35.0+ci.7"))
        for bad in [nil, "", "v4.35.0", "4", "main"] as [String?] {
            XCTAssertFalse(recent(bad, "4.35.0"), "\(bad ?? "nil")")
        }
        XCTAssertFalse(recent("4.35.0", "not-a-version"))
    }

    func testAWiderWindowCanBeAskedFor() {
        XCTAssertFalse(recent("4.32.0", "4.35.0"))
        XCTAssertTrue(recent("4.32.0", "4.35.0", window: 3))
    }

    func testNewWinsOverUpdated() {
        XCTAssertEqual(DemoFreshness.of(sinceVersion: nil, updatedIn: nil, buildVersion: "4.35.0"), .none)
        XCTAssertEqual(DemoFreshness.of(sinceVersion: "4.35.0", updatedIn: nil, buildVersion: "4.35.0"), .new)
        XCTAssertEqual(DemoFreshness.of(sinceVersion: nil, updatedIn: "4.35.0", buildVersion: "4.35.0"), .updated)
        XCTAssertEqual(DemoFreshness.of(sinceVersion: "4.35.0", updatedIn: "4.35.0", buildVersion: "4.35.0"), .new)
        XCTAssertEqual(DemoFreshness.of(sinceVersion: "4.20.0", updatedIn: "4.35.0", buildVersion: "4.35.0"), .updated)
    }

    /// The declarations mirrored from the Android fragments (`sinceVersion` /
    /// `updatedIn` in `RollingBallsFragment.kt`, `AnimationPhysicsFragment.kt`
    /// — `animation` is its iOS half in `parity-manifest.yml`). Update both
    /// sides together.
    func testSceneDeclarationsMirrorAndroid() {
        let byId = Dictionary(GeneratedScenes.all().map { ($0.sceneId, $0) },
                              uniquingKeysWith: { first, _ in first })
        XCTAssertEqual(byId["rolling-balls"]?.sinceVersion, "4.48.0")
        XCTAssertEqual(byId["animation"]?.updatedIn, "4.48.0")
        XCTAssertEqual(byId["model-viewer"]?.updatedIn, "4.35.0")
        XCTAssertEqual(byId["materials"]?.updatedIn, "4.35.0")
        XCTAssertEqual(byId["ar-rerun"]?.updatedIn, "4.46.0")
        // At 4.48 that is exactly Android's pair of chips.
        let fresh = byId.values
            .map { ($0.sceneId, DemoFreshness.of($0, buildVersion: "4.48.0")) }
            .filter { $0.1 != .none }
            .sorted { $0.0 < $1.0 }
        XCTAssertEqual(fresh.map(\.0), ["animation", "rolling-balls"])
        XCTAssertEqual(fresh.map(\.1), [.updated, .new])
    }

    /// Materials streams its subject: listed on a keyed build's home, as on
    /// Android, and kept off a keyless one, where it has only the placeholder.
    func testMaterialsIsOnTheHomeOfAKeyedBuildOnly() {
        XCTAssertTrue(HomeCatalogue.isOnHome("materials", hasSketchfabKey: true))
        XCTAssertFalse(HomeCatalogue.isOnHome("materials", hasSketchfabKey: false))
        XCTAssertTrue(HomeCatalogue.isOnHome("model-viewer", hasSketchfabKey: false))
    }
}

#endif
