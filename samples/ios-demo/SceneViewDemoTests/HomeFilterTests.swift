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
                        category: .lighting, tags: ["fog", "atmosphere"], order: 13),
        HomeSearchEntry(id: "model-viewer", title: "Model Viewer", subtitle: "Load and display 3D models",
                        category: .basics3D, tags: ["gltf", "hdr", "ar"], order: 1),
        HomeSearchEntry(id: "ar-placement", title: "Tap to Place", subtitle: "Tap a detected plane to place a model",
                        category: .ar, tags: ["ar", "plane"], order: 6),
        HomeSearchEntry(id: "physics", title: "Physics", subtitle: "Rigid bodies",
                        category: .interaction, tags: [], order: 999),
    ]

    func testBlankQueryAndAllCategoryReturnsEverythingInEditorialOrder() {
        let ids = filterDemos(entries, category: nil, query: "   ").map(\.id)
        XCTAssertEqual(ids, ["model-viewer", "ar-placement", "fog", "physics"])
    }

    func testCategoryRestrictsToThatCategory() {
        let ids = filterDemos(entries, category: .ar, query: "").map(\.id)
        XCTAssertEqual(ids, ["ar-placement"])
    }

    func testQueryMatchesTitleSubtitleCategoryAndTagsCaseInsensitively() {
        XCTAssertEqual(filterDemos(entries, category: nil, query: "FOG").map(\.id), ["fog"])
        XCTAssertEqual(filterDemos(entries, category: nil, query: "rigid").map(\.id), ["physics"])
        XCTAssertEqual(filterDemos(entries, category: nil, query: "lighting").map(\.id), ["fog"])
        XCTAssertEqual(filterDemos(entries, category: nil, query: "gltf").map(\.id), ["model-viewer"])
    }

    func testEveryWordMustMatch() {
        XCTAssertEqual(filterDemos(entries, category: nil, query: "ar plane").map(\.id), ["ar-placement"])
        XCTAssertTrue(filterDemos(entries, category: nil, query: "ar nothing-here").isEmpty)
    }

    func testCategoryAndQueryCombine() {
        XCTAssertTrue(filterDemos(entries, category: .lighting, query: "model").isEmpty)
        XCTAssertEqual(filterDemos(entries, category: .basics3D, query: "model").map(\.id), ["model-viewer"])
    }

    func testEntriesWithoutOrderSortLast() {
        let ids = filterDemos(entries, category: nil, query: "").map(\.id)
        XCTAssertEqual(ids.last, "physics")
    }
}

#endif
