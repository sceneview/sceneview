// ExploreSearchModelTests.swift
//
// The Explore search state machine (#3586), tested with no view, no network and
// no API key: a stub `ModelSource` answers with whatever the case needs —
// models, nothing, a 403, a dead socket — and the assertion is which of the four
// states the user ends up looking at.
//
// The debounce is tested with a 20 ms injected delay rather than the shipped
// 350 ms, so the suite stays fast while the cancel-on-keystroke behaviour is the
// real one.

#if DEBUG

import XCTest
@testable import SceneViewDemo

// MARK: - Stub source

private struct StubSource: ModelSource {
    let id: ModelSourceId
    var isAvailable: Bool { true }
    var feedKinds: [FeedKind] { [.trending] }
    var rendersInApp: Bool { true }

    /// What `search` does: hand back models, or throw.
    let onSearch: @Sendable (String, Int) throws -> [GalleryModel]

    init(
        id: ModelSourceId = .sketchfab,
        onSearch: @escaping @Sendable (String, Int) throws -> [GalleryModel]
    ) {
        self.id = id
        self.onSearch = onSearch
    }

    func feed(kind: FeedKind, animatedOnly: Bool, limit: Int) async throws -> [GalleryModel] { [] }

    func search(query: String, limit: Int) async throws -> [GalleryModel] {
        try onSearch(query, limit)
    }

    func download(model: GalleryModel, progress: (@Sendable (Double) -> Void)?) async throws -> URL {
        throw GallerySourceError.noRenderableFormat
    }
}

private func model(_ id: String) -> GalleryModel {
    GalleryModel(sourceId: .sketchfab, id: id, name: id.capitalized)
}

// MARK: - Tests

@MainActor
final class ExploreSearchModelTests: XCTestCase {

    // MARK: Pure decisions

    func testNormalizeTrimsSurroundingWhitespace() {
        XCTAssertEqual(ExploreSearchModel.normalize("  helmet \n"), "helmet")
        XCTAssertEqual(ExploreSearchModel.normalize("   "), "")
    }

    func testSingleCharacterNeverAutoSearches() {
        XCTAssertFalse(ExploreSearchModel.shouldAutoSearch("h", activeQuery: ""))
        XCTAssertTrue(ExploreSearchModel.shouldAutoSearch("he", activeQuery: ""))
    }

    func testTrailingSpaceIsNotANewSearch() {
        XCTAssertFalse(ExploreSearchModel.shouldAutoSearch("helmet ", activeQuery: "helmet"))
        XCTAssertTrue(ExploreSearchModel.shouldAutoSearch("helmets", activeQuery: "helmet"))
    }

    // MARK: Field → query

    func testDebouncedKeystrokeActivatesTheQuery() async {
        let search = ExploreSearchModel(debounceDelay: .milliseconds(20))
        await search.debouncedActivate(text: "  helmet ")
        XCTAssertEqual(search.activeQuery, "helmet")
        XCTAssertTrue(search.isSearching)
    }

    func testDebouncedKeystrokeBelowMinimumDoesNothing() async {
        let search = ExploreSearchModel(debounceDelay: .milliseconds(20))
        await search.debouncedActivate(text: "h")
        XCTAssertEqual(search.activeQuery, "")
        XCTAssertFalse(search.isSearching)
    }

    /// A superseded keystroke is cancelled mid-sleep and must not activate: this
    /// is what stops every intermediate fragment from hitting the API.
    func testCancelledDebounceNeverActivates() async {
        let search = ExploreSearchModel(debounceDelay: .seconds(5))
        let task = Task { await search.debouncedActivate(text: "helmet") }
        task.cancel()
        await task.value
        XCTAssertEqual(search.activeQuery, "")
    }

    func testSubmitSkipsTheDebounceAndReturnsTheQueryToRecord() {
        let search = ExploreSearchModel()
        XCTAssertEqual(search.submit(text: "  damaged helmet "), "damaged helmet")
        XCTAssertEqual(search.activeQuery, "damaged helmet")
    }

    func testSubmittingBlankTextIsRefusedSoHistoryStaysClean() {
        let search = ExploreSearchModel()
        XCTAssertNil(search.submit(text: "   "))
        XCTAssertEqual(search.activeQuery, "")
    }

    func testClearingTheFieldReturnsToTheBrowseFeeds() async {
        let search = ExploreSearchModel()
        search.submit(text: "helmet")
        await search.run(source: StubSource { _, _ in [model("a")] })
        XCTAssertEqual(search.state, .results([model("a")]))

        search.fieldChanged("")
        XCTAssertEqual(search.state, .idle)
        XCTAssertFalse(search.isSearching)
    }

    /// Deleting down to a single character can no longer produce a search, so the
    /// results of the longer query must not stay on screen under it.
    func testDeletingBelowTheMinimumClearsTheFinishedSearch() async {
        let search = ExploreSearchModel()
        search.submit(text: "helmet")
        await search.run(source: StubSource { _, _ in [model("a")] })
        XCTAssertEqual(search.state, .results([model("a")]))

        search.fieldChanged("h")
        XCTAssertEqual(search.state, .idle)
        XCTAssertEqual(search.activeQuery, "")
        XCTAssertFalse(search.isSearching)
    }

    // MARK: The four states

    func testIdleWhenNoQueryIsActive() async {
        let search = ExploreSearchModel()
        await search.run(source: StubSource { _, _ in [model("a")] })
        XCTAssertEqual(search.state, .idle)
    }

    func testResultsRenderWhenTheCatalogAnswersWithModels() async {
        let search = ExploreSearchModel()
        search.submit(text: "helmet")
        await search.run(source: StubSource { query, limit in
            XCTAssertEqual(query, "helmet")
            XCTAssertEqual(limit, ExploreSearchModel.resultLimit)
            return [model("a"), model("b")]
        })
        XCTAssertEqual(search.state, .results([model("a"), model("b")]))
        XCTAssertFalse(search.keyRejected)
    }

    func testNoResultsWhenTheCatalogAnswersWithNothing() async {
        let search = ExploreSearchModel()
        search.submit(text: "zzzzzz")
        await search.run(source: StubSource { _, _ in [] })
        XCTAssertEqual(search.state, .noResults(query: "zzzzzz"))
        // "Nothing found" is not a failure: nothing to retry, no banner.
        XCTAssertFalse(search.keyRejected)
    }

    func testNetworkFailureIsRetryableAndNotLatched() async {
        let search = ExploreSearchModel()
        search.submit(text: "helmet")
        await search.run(source: StubSource { _, _ in throw URLError(.notConnectedToInternet) })

        guard case .failed(let failure) = search.state else {
            return XCTFail("expected a failure state, got \(search.state)")
        }
        XCTAssertTrue(failure.canRetry)
        XCTAssertTrue(failure.title.contains("Sketchfab"))
        XCTAssertFalse(search.keyRejected)
    }

    func testServerErrorIsTreatedAsTransient() async {
        let search = ExploreSearchModel()
        search.submit(text: "helmet")
        await search.run(source: StubSource { _, _ in
            throw SketchfabError.requestFailed(statusCode: 503)
        })

        guard case .failed(let failure) = search.state else {
            return XCTFail("expected a failure state, got \(search.state)")
        }
        XCTAssertTrue(failure.canRetry)
        XCTAssertFalse(search.keyRejected)
    }

    func testRejectedKeyIsLatchedAndOffersNoPointlessRetry() async {
        let search = ExploreSearchModel()
        search.submit(text: "helmet")
        await search.run(source: StubSource { _, _ in
            throw SketchfabError.requestFailed(statusCode: 403)
        })

        guard case .failed(let failure) = search.state else {
            return XCTFail("expected a failure state, got \(search.state)")
        }
        XCTAssertFalse(failure.canRetry)
        XCTAssertTrue(search.keyRejected)
    }

    func testKeylessSourceRejectionIsClassifiedToo() async {
        let search = ExploreSearchModel()
        search.submit(text: "helmet")
        await search.run(source: StubSource(id: .icosa) { _, _ in
            throw GallerySourceError.requestFailed(statusCode: 401)
        })
        XCTAssertTrue(search.keyRejected)
    }

    /// A cancelled call is a superseded call, never an error on screen.
    func testCancellationLeavesTheStateAlone() async {
        let search = ExploreSearchModel()
        search.submit(text: "helmet")
        await search.run(source: StubSource { _, _ in throw CancellationError() })
        XCTAssertEqual(search.state, .loading)
    }

    // MARK: Retry and reset

    /// The retry token is what the view's `.task(id:)` keys on. Without it,
    /// re-assigning the same query inside one run loop restarts nothing and the
    /// button is decorative — which is exactly how it shipped.
    func testRetryBumpsTheTaskKeyAndRerunsTheSameQuery() async {
        let search = ExploreSearchModel()
        search.submit(text: "helmet")
        let before = search.retryToken
        search.retry()
        XCTAssertEqual(search.retryToken, before + 1)
        XCTAssertEqual(search.activeQuery, "helmet")

        await search.run(source: StubSource { _, _ in [model("a")] })
        XCTAssertEqual(search.state, .results([model("a")]))
    }

    func testRetryWithoutAQueryDoesNothing() {
        let search = ExploreSearchModel()
        search.retry()
        XCTAssertEqual(search.retryToken, 0)
    }

    func testResetOnSourceSwitchDropsQueryAndRejectionLatch() async {
        let search = ExploreSearchModel()
        search.submit(text: "helmet")
        await search.run(source: StubSource { _, _ in
            throw SketchfabError.requestFailed(statusCode: 401)
        })
        XCTAssertTrue(search.keyRejected)

        search.reset()
        XCTAssertEqual(search.activeQuery, "")
        XCTAssertEqual(search.state, .idle)
        XCTAssertFalse(search.keyRejected)
    }
}

/// The browse feeds' bounded wait and "couldn't reach" rule (#3766 P2 §2).
final class ExploreFeedLoadTests: XCTestCase {
    func testAnOperationThatAnswersInTimeReturnsItsValue() async throws {
        let value = try await ExploreFeedLoad.withTimeout(.seconds(2)) { 42 }
        XCTAssertEqual(value, 42)
    }

    func testAStalledOperationIsAbandonedAtTheDeadline() async {
        let clock = ContinuousClock()
        let start = clock.now
        do {
            _ = try await ExploreFeedLoad.withTimeout(.milliseconds(100)) {
                try await Task.sleep(for: .seconds(30))
                return 0
            }
            XCTFail("expected the deadline to win")
        } catch {
            XCTAssertTrue(error is CancellationError)
        }
        XCTAssertLessThan(clock.now - start, .seconds(5))
    }

    func testEveryFeedFailingIsUnreachable() {
        XCTAssertTrue(ExploreFeedLoad.isUnreachable(feedCount: 3, failures: 3, rejected: false))
    }

    func testOneFeedAnsweringIsNotUnreachable() {
        XCTAssertFalse(ExploreFeedLoad.isUnreachable(feedCount: 3, failures: 2, rejected: false))
    }

    func testARejectedKeyKeepsItsOwnBanner() {
        XCTAssertFalse(ExploreFeedLoad.isUnreachable(feedCount: 2, failures: 2, rejected: true))
    }

    func testASourceWithNoFeedsIsNeverUnreachable() {
        XCTAssertFalse(ExploreFeedLoad.isUnreachable(feedCount: 0, failures: 0, rejected: false))
    }

    // MARK: One carousel's state (#3789)

    private func section(
        models: Bool = false, loading: Bool = false, failed: Bool = false,
        unreachable: Bool = false, rejected: Bool = false
    ) -> ExploreFeedLoad.SectionState {
        ExploreFeedLoad.sectionState(
            hasModels: models, isLoading: loading, failed: failed,
            unreachable: unreachable, keyRejected: rejected
        )
    }

    func testAFeedWithModelsShowsThem() {
        XCTAssertEqual(section(models: true), .models)
        XCTAssertEqual(section(models: true, failed: true), .models)
    }

    func testAFeedStillLoadingShowsPlaceholders() {
        XCTAssertEqual(section(loading: true), .loading)
    }

    func testAFailedFeedSaysSoInsteadOfVanishing() {
        XCTAssertEqual(section(failed: true), .failed)
    }

    func testAnEmptyFeedSaysSoInsteadOfVanishing() {
        XCTAssertEqual(section(), .empty)
    }

    func testAFailedFeedDefersToTheUnreachableCardAndTheKeyBanner() {
        XCTAssertEqual(section(failed: true, unreachable: true), .hidden)
        XCTAssertEqual(section(failed: true, rejected: true), .hidden)
    }
}

/// Poly Haven's USD file set, read from a `/files/{slug}` record (#3789).
final class PolyHavenUSDPlanTests: XCTestCase {
    private func entry(_ url: String, size: Int = 1_000, include: String = "") -> String {
        let includes = include.isEmpty ? "" : #", "include": {\#(include)}"#
        return #"{"url": "\#(url)", "size": \#(size)\#(includes)}"#
    }

    private func record(_ resolutions: [String: String]) -> Data {
        let body = resolutions
            .map { #""\#($0.key)": {"usd": \#($0.value)}"# }
            .joined(separator: ", ")
        return Data(#"{"usd": {\#(body)}, "gltf": {}}"#.utf8)
    }

    private let crate1k = "https://dl.polyhaven.org/file/ph-assets/Models/usd/1k/sofa_02_1k.usdc"
    private let texture = "https://dl.polyhaven.org/file/ph-assets/Models/jpg/1k/sofa_02/sofa_02_diff_1k.jpg"

    func testThe1kSetIsPreferredWithItsTexturesAtTheirRelativePaths() throws {
        let data = record([
            "4k": entry("https://dl.polyhaven.org/file/ph-assets/Models/usd/4k/sofa_02_4k.usdc", size: 9_000),
            "1k": entry(crate1k, size: 2_000, include: #""textures/sofa_02_diff_1k.jpg": \#(entry(texture, size: 500))"#),
        ])
        let plan = try PolyHavenUSD.plan(from: data)
        XCTAssertEqual(plan.root.relativePath, "sofa_02_1k.usdc")
        XCTAssertEqual(plan.includes.map(\.relativePath), ["textures/sofa_02_diff_1k.jpg"])
        XCTAssertEqual(plan.totalBytes, 2_500)
        XCTAssertTrue(plan.fitsBudget)
    }

    func testWithout1kTheSmallestResolutionIsUsed() throws {
        let data = record([
            "8k": entry("https://dl.polyhaven.org/file/ph-assets/Models/usd/8k/a_8k.usdc"),
            "2k": entry("https://dl.polyhaven.org/file/ph-assets/Models/usd/2k/a_2k.usdc"),
        ])
        XCTAssertEqual(try PolyHavenUSD.plan(from: data).root.relativePath, "a_2k.usdc")
    }

    func testASetOverTheBudgetDoesNotFit() throws {
        let heavy = Int(PolyHavenUSD.maxTotalBytes) + 1
        let plan = try PolyHavenUSD.plan(from: record(["1k": entry(crate1k, size: heavy)]))
        XCTAssertFalse(plan.fitsBudget)
    }

    func testARecordWithoutUSDIsRefused() {
        XCTAssertThrowsError(try PolyHavenUSD.plan(from: Data(#"{"gltf": {}}"#.utf8))) {
            XCTAssertEqual($0 as? PolyHavenUSD.PlanError, .noUSD)
        }
    }

    func testPlainHTTPAndForeignHostsAreRefused() {
        for url in [
            "http://dl.polyhaven.org/file/a_1k.usdc",
            "https://evil.example/a_1k.usdc",
        ] {
            XCTAssertThrowsError(try PolyHavenUSD.plan(from: record(["1k": entry(url)])), url)
        }
        let foreignTexture = entry(crate1k, include: #""textures/a.jpg": \#(entry("https://evil.example/a.jpg"))"#)
        XCTAssertThrowsError(try PolyHavenUSD.plan(from: record(["1k": foreignTexture])))
    }

    func testACrateThatIsNotUSDIsRefused() {
        let glb = "https://dl.polyhaven.org/file/ph-assets/Models/gltf/1k/a_1k.glb"
        XCTAssertThrowsError(try PolyHavenUSD.plan(from: record(["1k": entry(glb)])))
    }

    func testIncludePathsThatLeaveTheModelFolderAreRefused() {
        for path in ["../escape.jpg", "/etc/passwd", "textures/../../x.jpg", "textures//a.jpg"] {
            let data = record(["1k": entry(crate1k, include: #""\#(path)": \#(entry(texture))"#)])
            XCTAssertThrowsError(try PolyHavenUSD.plan(from: data), path)
        }
    }

    func testSafeRelativePaths() {
        XCTAssertTrue(PolyHavenUSD.isSafeRelativePath("textures/sofa_02_diff_1k.jpg"))
        XCTAssertTrue(PolyHavenUSD.isSafeRelativePath("sofa-02.usdc"))
        for path in ["", ".", "..", "/a", "a/..", "a\\b", "a/./b", "a b.jpg", "a/"] {
            XCTAssertFalse(PolyHavenUSD.isSafeRelativePath(path), path)
        }
    }

    func testTheFilesURLKeepsTheSlugAsOneSegment() {
        XCTAssertEqual(
            PolyHavenUSD.filesURL(base: "https://api.polyhaven.com/", slug: "sofa_02")?.absoluteString,
            "https://api.polyhaven.com/files/sofa_02"
        )
        XCTAssertNil(PolyHavenUSD.filesURL(base: "https://api.polyhaven.com/", slug: "../assets"))
        XCTAssertNil(PolyHavenUSD.filesURL(base: "https://api.polyhaven.com/", slug: ""))
    }
}

/// The crate rewrite that lets RealityKit bind Poly Haven's textures (#3789):
/// preview surface instead of the MaterialX graph, and texture paths that
/// point at the downloaded copies.
final class PolyHavenCratePatchTests: XCTestCase {
    private let le = PolyHavenUSD.littleEndian

    /// A minimal crate: header, a TOKENS section, a STRINGS section, a table
    /// of contents — the layout `usdcat` reads from Poly Haven's files.
    private func crate(tokens: [String]) -> Data {
        let raw: [UInt8] = tokens.flatMap { Array($0.utf8) + [UInt8(0)] }
        let block: [UInt8] = [0] + PolyHavenUSD.lz4LiteralBlock(raw)
        var tokenSection = le(tokens.count)
        tokenSection += le(raw.count)
        tokenSection += le(block.count)
        tokenSection += block
        let stringSection: [UInt8] = le(0)
        let tokensStart = 88
        let stringsStart = tokensStart + tokenSection.count
        let tocOffset = stringsStart + stringSection.count
        func name(_ text: String) -> [UInt8] { Array(text.utf8) + [UInt8](repeating: 0, count: 16 - text.utf8.count) }
        let version: [UInt8] = [0, 9, 0, 0, 0, 0, 0, 0]
        var bytes: [UInt8] = Array("PXR-USDC".utf8)
        bytes += version
        bytes += le(tocOffset)
        bytes += [UInt8](repeating: 0, count: 64)
        bytes += tokenSection
        bytes += stringSection
        bytes += le(2)
        bytes += name("TOKENS") + le(tokensStart) + le(tokenSection.count)
        bytes += name("STRINGS") + le(stringsStart) + le(stringSection.count)
        return Data(bytes)
    }

    private func apply(_ patch: PolyHavenUSD.CratePatch, to crate: Data) -> Data {
        var bytes = [UInt8](crate) + patch.appended
        bytes.replaceSubrange(16..<24, with: le(patch.tocOffset))
        return Data(bytes)
    }

    func testTheMaterialXSurfaceIsRenamedAndEverythingElseKept() throws {
        let original = crate(tokens: ["", "Camera_01", "outputs:surface", "outputs:mtlx:surface", "Principled_BSDF_mtlx1"])
        let patch = try XCTUnwrap(PolyHavenUSD.materialPatch(forCrate: original, textures: []))
        let patched = try PolyHavenUSD.CrateLayout(apply(patch, to: original))
        XCTAssertEqual(
            patched.tokens.map { String(decoding: $0, as: UTF8.self) },
            ["", "Camera_01", "outputs:surface", "outputs:mtlx_off:surface", "Principled_BSDF_mtlx1"]
        )
        let before = try PolyHavenUSD.CrateLayout(original)
        XCTAssertEqual(patched.sections[1].start, before.sections[1].start, "other sections stay where they were")
        XCTAssertEqual(patched.sections[1].size, before.sections[1].size)
        XCTAssertEqual(patched.sections[0].start, original.count, "the new token table is appended")
    }

    func testAbsoluteTexturePathsPointAtTheDownloadedCopies() throws {
        let absolute = "/mnt/prod/Assets/Models/torch/staging/textures/torch_diff_1k.jpg"
        let original = crate(tokens: ["", absolute, "/mnt/prod/other/unknown.jpg", "./textures/torch_rough_1k.exr"])
        let textures = ["textures/torch_diff_1k.jpg", "textures/torch_rough_1k.exr"]
        let patch = try XCTUnwrap(PolyHavenUSD.materialPatch(forCrate: original, textures: textures))
        let patched = try PolyHavenUSD.CrateLayout(apply(patch, to: original))
        XCTAssertEqual(
            patched.tokens.map { String(decoding: $0, as: UTF8.self) },
            ["", "./textures/torch_diff_1k.jpg", "/mnt/prod/other/unknown.jpg", "./textures/torch_rough_1k.exr"],
            "only a path ending in a downloaded texture is rewritten"
        )
    }

    func testACrateWithNothingToRepairIsLeftAlone() throws {
        let clean = crate(tokens: ["", "outputs:surface", "./textures/a_diff_1k.jpg"])
        XCTAssertNil(try PolyHavenUSD.materialPatch(forCrate: clean, textures: ["textures/a_diff_1k.jpg"]))
    }

    func testAFileThatIsNotACrateIsRefused() {
        XCTAssertThrowsError(try PolyHavenUSD.materialPatch(forCrate: Data("#usda 1.0\n".utf8), textures: []))
        XCTAssertThrowsError(try PolyHavenUSD.materialPatch(forCrate: Data(), textures: []))
    }

    func testLZ4CopiesOverlappingMatches() throws {
        // "abc", then 9 bytes copied from 3 back, then a closing literal "x".
        let block: [UInt8] = [0x35, 97, 98, 99, 0x03, 0x00, 0x10, 120]
        let decoded = try PolyHavenUSD.lz4Decode(block[...], expectedSize: 13)
        XCTAssertEqual(String(decoding: decoded, as: UTF8.self), "abcabcabcabcx")
    }

    func testLZ4RefusesAMatchBeforeTheStartOfTheOutput() {
        let block: [UInt8] = [0x10, 97, 0x05, 0x00]
        XCTAssertThrowsError(try PolyHavenUSD.lz4Decode(block[...], expectedSize: 5))
    }

    func testALiteralBlockDecodesToItsInputAtEveryLengthBoundary() throws {
        for count in [0, 14, 15, 269, 270, 600] {
            let input = (0..<count).map { UInt8(truncatingIfNeeded: $0) }
            let block = PolyHavenUSD.lz4LiteralBlock(input)
            XCTAssertEqual(try PolyHavenUSD.lz4Decode(block[...], expectedSize: count), input, "\(count)")
        }
    }
}

#endif
