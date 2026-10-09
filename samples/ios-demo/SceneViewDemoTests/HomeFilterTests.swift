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

    func testWhatsNewKeepsOnlyNewAndUpdatedDemos() {
        let marked = [
            HomeSearchEntry(id: "fog", title: "Fog", subtitle: "", section: .create, category: .lighting,
                            order: 13, freshness: .updated),
            HomeSearchEntry(id: "cosmos", title: "Cosmos", subtitle: "", section: .view3d, category: .advanced,
                            order: 2, freshness: .new),
            HomeSearchEntry(id: "physics", title: "Physics", subtitle: "", section: .view3d,
                            category: .interaction, order: 5),
        ]
        XCTAssertEqual(filterDemos(marked, section: nil, query: "", whatsNew: true).map(\.id), ["cosmos", "fog"])
        XCTAssertEqual(filterDemos(marked, section: .view3d, query: "", whatsNew: true).map(\.id), ["cosmos"])
        XCTAssertEqual(filterDemos(marked, section: nil, query: "fog", whatsNew: true).map(\.id), ["fog"])
        XCTAssertEqual(filterDemos(marked, section: nil, query: "").count, 3)
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

    func testWindowCoversTheBuildAndTheTwoPreviousMinors() {
        XCTAssertEqual(DemoFreshness.windowMinors, 2, "keep equal to Android's FRESHNESS_WINDOW_MINORS")
        XCTAssertTrue(recent("4.35.0", "4.35.0"))
        XCTAssertTrue(recent("4.34.0", "4.35.0"))
        XCTAssertTrue(recent("4.33.0", "4.35.0"))
        XCTAssertFalse(recent("4.32.0", "4.35.0"))
        XCTAssertFalse(recent("4.32.9", "4.35.2"))
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
        XCTAssertFalse(recent("4.31.0", "4.35.0"))
        XCTAssertTrue(recent("4.31.0", "4.35.0", window: 4))
    }

    func testAComingSoonDemoNeverEarnsAChip() {
        let soon = DemoItem(sceneId: "soon", comingSoonTitle: "Soon", icon: "clock", subtitle: "",
                            order: 1, tags: [], addedIn: "4.35.0", section: .create, category: .advanced)
        XCTAssertEqual(DemoFreshness.of(soon, buildVersion: "4.35.0"), .none)
    }

    func testADeclarationMayNotBeAheadOfTheBuild() {
        XCTAssertTrue(DemoFreshness.isDeclarable("4.51.0", buildVersion: "4.51.0"))
        XCTAssertTrue(DemoFreshness.isDeclarable("4.9.3", buildVersion: "4.51.0"))
        XCTAssertTrue(DemoFreshness.isDeclarable("4.51", buildVersion: "4.51.2"))
        XCTAssertFalse(DemoFreshness.isDeclarable("4.51.1", buildVersion: "4.51.0"))
        XCTAssertFalse(DemoFreshness.isDeclarable("4.52.0", buildVersion: "4.51.0"))
        XCTAssertFalse(DemoFreshness.isDeclarable("5.0.0", buildVersion: "4.51.0"))
        for bad in [nil, "", "v4.35.0", "4", "main"] as [String?] {
            XCTAssertFalse(DemoFreshness.isDeclarable(bad, buildVersion: "4.51.0"), "\(bad ?? "nil")")
        }
    }

    /// The registry rule, on the real scenes: every demo declares the release it
    /// first shipped in (`// @addedIn`), and neither version is ahead of this
    /// iOS target's `MARKETING_VERSION`.
    func testEverySceneDeclaresAnAddedInThatIsNotAheadOfTheBuild() {
        let scenes = GeneratedScenes.all()
        XCTAssertFalse(scenes.isEmpty)
        let build = DemoFreshness.appVersion
        for item in scenes {
            XCTAssertNotNil(item.addedIn, "\(item.sceneId) has no // @addedIn")
            XCTAssertTrue(DemoFreshness.isDeclarable(item.addedIn, buildVersion: build),
                          "\(item.sceneId) @addedIn \(item.addedIn ?? "nil") is missing or newer than \(build)")
            if let updated = item.updatedIn {
                XCTAssertTrue(DemoFreshness.isDeclarable(updated, buildVersion: build),
                              "\(item.sceneId) @updatedIn \(updated) is newer than \(build)")
                XCTAssertTrue(DemoFreshness.isDeclarable(item.addedIn, buildVersion: updated),
                              "\(item.sceneId) @updatedIn \(updated) predates @addedIn")
            }
        }
    }

    func testNewWinsOverUpdated() {
        XCTAssertEqual(DemoFreshness.of(addedIn: nil, updatedIn: nil, buildVersion: "4.35.0"), .none)
        XCTAssertEqual(DemoFreshness.of(addedIn: "4.35.0", updatedIn: nil, buildVersion: "4.35.0"), .new)
        XCTAssertEqual(DemoFreshness.of(addedIn: nil, updatedIn: "4.35.0", buildVersion: "4.35.0"), .updated)
        XCTAssertEqual(DemoFreshness.of(addedIn: "4.35.0", updatedIn: "4.35.0", buildVersion: "4.35.0"), .new)
        XCTAssertEqual(DemoFreshness.of(addedIn: "4.20.0", updatedIn: "4.35.0", buildVersion: "4.35.0"), .updated)
    }

    /// Every available scene earns its chip in the build its declaration names.
    func testEverySceneDeclarationEarnsItsChipInItsOwnVersion() {
        let declared = GeneratedScenes.all().filter { $0.status.isAvailable }
        XCTAssertFalse(declared.isEmpty)
        for item in declared {
            if let since = item.addedIn {
                XCTAssertEqual(DemoFreshness.of(item, buildVersion: since), .new, item.sceneId)
            }
            // "Updated" in its own version — or still "New", when the rework
            // landed inside the window that also covers its first release.
            if let updated = item.updatedIn {
                XCTAssertNotEqual(DemoFreshness.of(item, buildVersion: updated), .none, item.sceneId)
            }
        }
    }

    /// The What's new row's "since": the oldest release still in the window —
    /// Android's `freshnessWindowStart`.
    func testTheWindowStartsTwoMinorsBeforeTheBuild() {
        XCTAssertEqual(DemoFreshness.windowStart(buildVersion: "4.51.0"), "4.49")
        XCTAssertEqual(DemoFreshness.windowStart(buildVersion: "4.51.2-rc1"), "4.49")
        XCTAssertEqual(DemoFreshness.windowStart(buildVersion: "5.1.0"), "5.0")
        XCTAssertEqual(DemoFreshness.windowStart(buildVersion: "dev"), "dev")
    }

    /// Materials is a procedural sphere wall, as on Android: it needs no
    /// Sketchfab key, so it stays on the home of a keyless build too.
    func testMaterialsIsOnTheHomeWithOrWithoutAKey() {
        XCTAssertTrue(HomeCatalogue.isOnHome("materials", hasSketchfabKey: true))
        XCTAssertTrue(HomeCatalogue.isOnHome("materials", hasSketchfabKey: false))
        XCTAssertTrue(HomeCatalogue.isOnHome("model-viewer", hasSketchfabKey: false))
    }
}

/// Home list rows (#4186) — Android's `HomeListRowTest`: the column
/// arithmetic, and the colour each row takes from its picture.
final class HomeListRowTests: XCTestCase {

    func testListColumnsFollowTheMinimumRowWidth() {
        XCTAssertEqual(homeListColumns(width: 0), 1)
        XCTAssertEqual(homeListColumns(width: 353), 1)   // iPhone 17 Pro
        XCTAssertEqual(homeListColumns(width: 690), 2)   // 340 · 2 + one 10 pt gap
        XCTAssertEqual(homeListColumns(width: 1_040), 3) // 3 × 340 + 2 gaps
    }

    func testTheAmbientTintLandsOnTheAppearanceLuminanceWhateverThePicture() {
        let seeds: [HomeAmbient.RGB] = [
            .init(r: 1, g: 1, b: 1), .init(r: 0, g: 0, b: 0),
            .init(r: 0xE2 / 255.0, g: 0x73 / 255.0, b: 0x4F / 255.0),
            .init(r: 0x1E / 255.0, g: 0x3A / 255.0, b: 0x8A / 255.0),
            .init(r: 1, g: 0xEB / 255.0, b: 0x3B / 255.0),
        ]
        for seed in seeds {
            XCTAssertEqual(HomeAmbient.luminance(HomeAmbient.tint(seed: seed, dark: true)),
                           HomeAmbient.luminanceDark, accuracy: 0.002)
            XCTAssertEqual(HomeAmbient.luminance(HomeAmbient.tint(seed: seed, dark: false)),
                           HomeAmbient.luminanceLight, accuracy: 0.002)
        }
    }

    func testTextKeepsItsContrastOnEveryTint() {
        let onSurfaceDark = HomeAmbient.RGB(r: 0xF3 / 255.0, g: 0xF4 / 255.0, b: 0xF6 / 255.0)
        let onSurfaceDimDark = HomeAmbient.RGB(r: 0xA4 / 255.0, g: 0xAB / 255.0, b: 0xB7 / 255.0)
        let onSurfaceDimLight = HomeAmbient.RGB(r: 0x3D / 255.0, g: 0x46 / 255.0, b: 0x54 / 255.0)
        let seeds: [HomeAmbient.RGB] = [
            .init(r: 1, g: 1, b: 1),
            .init(r: 0xE2 / 255.0, g: 0x73 / 255.0, b: 0x4F / 255.0),
            .init(r: 0x1E / 255.0, g: 0x3A / 255.0, b: 0x8A / 255.0),
            .init(r: 0, g: 0xC8 / 255.0, b: 0x53 / 255.0),
        ]
        for seed in seeds {
            let dark = HomeAmbient.tint(seed: seed, dark: true)
            let light = HomeAmbient.tint(seed: seed, dark: false)
            XCTAssertGreaterThanOrEqual(contrast(onSurfaceDark, dark), 7)
            XCTAssertGreaterThanOrEqual(contrast(onSurfaceDimDark, dark), 4.5)
            XCTAssertGreaterThanOrEqual(contrast(onSurfaceDimLight, light), 4.5)
        }
    }

    func testAColouredSubjectOnAGreyFloorReadsAsItsColour() {
        // Nine grey pixels and one orange one: the chroma weighting keeps the hue.
        let grey: [UInt8] = [0x80, 0x80, 0x80, 0xFF]
        let orange: [UInt8] = [0xFF, 0x7A, 0x1A, 0xFF]
        let seed = HomeAmbient.seed(rgba: Array(repeating: grey, count: 9).flatMap { $0 } + orange)
        XCTAssertGreaterThan(seed.r, seed.b + 0.05)
    }

    private func contrast(_ a: HomeAmbient.RGB, _ b: HomeAmbient.RGB) -> Double {
        let la = HomeAmbient.luminance(a), lb = HomeAmbient.luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}

/// Android's HomeTopSections tests, plus the iOS chip and Show all decisions.
final class HomeTopSectionsTests: XCTestCase {
    private let entries: [HomeSearchEntry] = [
        .init(id: "hero", title: "Models", subtitle: "Viewer", section: .view3d,
              category: .basics3D, order: 1),
        .init(id: "banner", title: "Materials", subtitle: "Sphere wall", section: .create,
              category: .basics3D, order: 2, freshness: .updated),
        .init(id: "other", title: "Geometry", subtitle: "Shapes", section: .create,
              category: .basics3D, order: 3)
    ]

    private var top: HomeTopSections {
        HomeTopSections(hero: ["hero"], featured: ["hero", "banner"], fresh: ["banner", "other"])
    }

    func testHeroDoesNotComeBackAsABanner() {
        XCTAssertEqual(top.hero, ["hero"])
        XCTAssertEqual(top.featured, ["banner"])
    }

    func testEarlierGroupsDoNotComeBackInFreshPictures() {
        let top = HomeTopSections(hero: ["a"], featured: ["b", "c"], fresh: ["c", "e", "a", "f"])
        XCTAssertEqual(top.whatsNew, ["e", "f"])
    }

    func testEmptyGroupsAreNotShown() {
        let top = HomeTopSections(hero: ["a", "b", "c"], featured: ["b", "c"], fresh: [])
        XCTAssertTrue(top.featured.isEmpty)
        XCTAssertTrue(top.whatsNew.isEmpty)
        XCTAssertFalse(top.showsWhatsNew(searching: false))
        XCTAssertTrue(HomeTopSections(hero: [], featured: [], fresh: []).hero.isEmpty)
    }

    func testEachGroupKeepsOrderAndDeduplicates() {
        let top = HomeTopSections(hero: ["b", "a", "b"], featured: ["d", "c", "d"], fresh: ["f", "e", "f"])
        XCTAssertEqual(top.hero, ["b", "a"])
        XCTAssertEqual(top.featured, ["d", "c"])
        XCTAssertEqual(top.whatsNew, ["f", "e"])
        let all = top.hero + top.featured + top.whatsNew
        XCTAssertEqual(all.count, Set(all).count)
    }

    func testNoHeroLeavesTheFollowingGroupsIntact() {
        let top = HomeTopSections(hero: [], featured: ["a"], fresh: ["b"])
        XCTAssertEqual(top.featured, ["a"])
        XCTAssertEqual(top.whatsNew, ["b"])
    }

    func testAllOmitsBannersButKeepsTheHero() {
        XCTAssertEqual(top.catalogue(entries, selection: HomeSelection()).map(\.id), ["hero", "other"])
        XCTAssertEqual(top.catalogue(entries, selection: HomeSelection(query: " \n ")).map(\.id), ["hero", "other"])
    }

    func testChipOrSearchIncludesEveryMatch() {
        XCTAssertEqual(top.catalogue(entries, selection: HomeSelection(section: .create)).map(\.id),
                       ["banner", "other"])
        XCTAssertEqual(top.catalogue(entries, selection: HomeSelection(query: "Materials")).map(\.id), ["banner"])
        XCTAssertEqual(top.catalogue(entries, selection: HomeSelection(whatsNew: true)).map(\.id), ["banner"])
    }

    func testPictureFreeWhatsNewMetadataStillDescribesAllFreshDemos() {
        let top = HomeTopSections(hero: ["a"], featured: ["b"], fresh: ["a", "b", "b"])
        XCTAssertTrue(top.whatsNew.isEmpty)
        XCTAssertEqual(top.freshCount, 2)
        XCTAssertTrue(top.showsWhatsNew(searching: false))
        XCTAssertFalse(top.showsWhatsNew(searching: true))
    }

    func testSelectedSectionAndWhatsNewToggleBackToAll() {
        var selection = HomeSelection()
        selection.select(.create)
        XCTAssertEqual(selection.section, .create)
        selection.select(.create)
        XCTAssertFalse(selection.isFiltered)
        selection.toggleWhatsNew()
        XCTAssertTrue(selection.whatsNew)
        selection.toggleWhatsNew()
        XCTAssertFalse(selection.isFiltered)
        selection.toggleWhatsNew()
        selection.select(.create)
        XCTAssertFalse(selection.whatsNew)
        XCTAssertEqual(selection.section, .create)
        selection.select(nil)
        XCTAssertFalse(selection.isFiltered)
    }

    func testShowAllNamesTheWholeCatalogueUnderAChipOnly() {
        XCTAssertNil(HomeSelection().showAllCount(total: entries.count))
        XCTAssertEqual(HomeSelection(section: .create).showAllCount(total: entries.count), 3)
        XCTAssertEqual(HomeSelection(whatsNew: true).showAllCount(total: entries.count), 3)
    }

    /// Android's `activeCategory != null && !searching`: a query is its own
    /// filter, with its own "Clear".
    func testShowAllStepsAsideWhileSearching() {
        XCTAssertNil(HomeSelection(query: "no match").showAllCount(total: entries.count))
        XCTAssertNil(HomeSelection(section: .create, query: "Materials").showAllCount(total: entries.count))
        XCTAssertNil(HomeSelection(whatsNew: true, query: "fog").showAllCount(total: entries.count))
        XCTAssertEqual(HomeSelection(section: .create, query: " \n ").showAllCount(total: entries.count), 3)
    }

    func testShowAllClearsTheChipAndLeavesTheQuery() {
        var selection = HomeSelection(section: .create, query: "Materials")
        XCTAssertEqual(top.catalogue(entries, selection: selection).count, 1)
        selection.showAll()
        XCTAssertEqual(selection, HomeSelection(query: "Materials"))

        selection = HomeSelection(whatsNew: true)
        selection.showAll()
        XCTAssertEqual(selection, HomeSelection())
        XCTAssertNil(selection.showAllCount(total: entries.count))
        XCTAssertEqual(top.catalogue(entries, selection: selection).map(\.id), ["hero", "other"])
    }
}

#endif
