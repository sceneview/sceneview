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

#endif
