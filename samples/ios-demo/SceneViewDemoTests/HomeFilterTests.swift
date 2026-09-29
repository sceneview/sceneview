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

    /// Every scene declaration earns its chip in the build it names. The values
    /// themselves are checked against the Android fragments, through
    /// `parity-manifest.yml`, by `collate-ios-demos.sh` on every build: a
    /// declaration that drifts from Android fails the build, not this test.
    func testEverySceneDeclarationEarnsItsChipInItsOwnVersion() {
        let declared = GeneratedScenes.all().filter { $0.sinceVersion != nil || $0.updatedIn != nil }
        XCTAssertFalse(declared.isEmpty)
        for item in declared {
            if let since = item.sinceVersion {
                XCTAssertEqual(DemoFreshness.of(item, buildVersion: since), .new, item.sceneId)
            }
            if let updated = item.updatedIn, item.sinceVersion == nil {
                XCTAssertEqual(DemoFreshness.of(item, buildVersion: updated), .updated, item.sceneId)
            }
        }
    }

    /// Materials is a procedural sphere wall, as on Android: it needs no
    /// Sketchfab key, so it stays on the home of a keyless build too.
    func testMaterialsIsOnTheHomeWithOrWithoutAKey() {
        XCTAssertTrue(HomeCatalogue.isOnHome("materials", hasSketchfabKey: true))
        XCTAssertTrue(HomeCatalogue.isOnHome("materials", hasSketchfabKey: false))
        XCTAssertTrue(HomeCatalogue.isOnHome("model-viewer", hasSketchfabKey: false))
    }
}

/// Home list rows (#4186) — Android's `HomeListRowTest`.
final class HomeListRowTests: XCTestCase {

    func testOneColumnGroupRoundsOnlyItsOuterCorners() {
        let first = HomeRowCorners(index: 0, count: 3, columns: 1)
        let middle = HomeRowCorners(index: 1, count: 3, columns: 1)
        let last = HomeRowCorners(index: 2, count: 3, columns: 1)
        XCTAssertEqual(first, HomeRowCorners(topLeading: true, topTrailing: true, bottomTrailing: false, bottomLeading: false))
        XCTAssertEqual(middle, HomeRowCorners(topLeading: false, topTrailing: false, bottomTrailing: false, bottomLeading: false))
        XCTAssertEqual(last, HomeRowCorners(topLeading: false, topTrailing: false, bottomTrailing: true, bottomLeading: true))
        XCTAssertEqual(HomeRowCorners(index: 0, count: 1, columns: 1), .lone)
    }

    func testShortLastLineEndsInAStep() {
        // 5 rows, 2 across: the last line holds one row, so the row above the
        // step owns the block's bottom-trailing corner.
        XCTAssertEqual(HomeRowCorners(index: 1, count: 5, columns: 2),
                       HomeRowCorners(topLeading: false, topTrailing: true, bottomTrailing: false, bottomLeading: false))
        XCTAssertEqual(HomeRowCorners(index: 3, count: 5, columns: 2),
                       HomeRowCorners(topLeading: false, topTrailing: false, bottomTrailing: true, bottomLeading: false))
        XCTAssertEqual(HomeRowCorners(index: 4, count: 5, columns: 2),
                       HomeRowCorners(topLeading: false, topTrailing: false, bottomTrailing: true, bottomLeading: true))
    }

    func testListColumnsFollowTheMinimumRowWidth() {
        XCTAssertEqual(homeListColumns(width: 0), 1)
        XCTAssertEqual(homeListColumns(width: 353), 1)   // iPhone 17 Pro
        XCTAssertEqual(homeListColumns(width: 682), 2)   // 340 · 2 + one seam
        XCTAssertEqual(homeListColumns(width: 1_024), 3) // 3 × 340 + 2 seams
    }
}

#endif
