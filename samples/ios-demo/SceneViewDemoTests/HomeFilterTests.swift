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

#endif
