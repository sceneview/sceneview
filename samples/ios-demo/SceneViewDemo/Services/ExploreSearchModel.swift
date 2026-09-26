// ExploreSearchModel.swift
//
// The query + state logic behind the Explore tab's search field (#3586).
//
// It lives outside `ExploreTab.swift` on purpose: the four states the search can
// be in (default feed, loading, no results, unreachable catalog) used to be four
// implicit combinations of three `@State` properties inside the view body, which
// no test could reach. Here they are one enum, driven by three entry points
// (`fieldChanged`, `debouncedActivate`, `submit`) and one executor (`run`), all
// unit-tested in `SceneViewDemoTests/ExploreSearchModelTests.swift`.
//
// Behaviour parity with Android's `ExploreTabScreen.kt` (#2228 / #1239):
// - 350 ms debounce after the last keystroke, 2-character minimum;
// - only an explicit submit records the query into the recent-search history;
// - clearing the field cancels the search and restores the browse feeds;
// - a rejected key (401/403) is latched and named; anything else is transient
//   and offers a retry.

import Foundation

/// Why a search could not produce a list, in the user's terms.
///
/// Kept apart from "the catalog answered with nothing": only one of the two is
/// worth a Retry button, and they need different sentences.
struct ExploreSearchFailure: Equatable {
    /// One short line naming what happened.
    let title: String
    /// One line telling the user what to do next.
    let message: String
    /// SF Symbol for the leading glyph.
    let icon: String
    /// `true` when running the same query again can plausibly succeed.
    let canRetry: Bool

    /// The catalog was never reached — offline, DNS, timeout, 5xx, rate limit.
    static func unreachable(_ sourceName: String) -> ExploreSearchFailure {
        ExploreSearchFailure(
            title: "Couldn't reach \(sourceName)",
            message: "The search never made it out. Check your connection, then try again.",
            icon: "wifi.exclamationmark",
            canRetry: true
        )
    }

    /// The catalog answered 401/403 — the key is present but refused. Retrying
    /// the same call cannot fix that, so the copy points at the source picker.
    static func keyRejected(_ sourceName: String) -> ExploreSearchFailure {
        ExploreSearchFailure(
            title: "\(sourceName) search is unavailable",
            message: "The app's API key was rejected. Pick Poly Haven above — it needs no key.",
            icon: "key.slash",
            canRetry: false
        )
    }
}

/// The four states the Explore search can be in, and nothing else.
enum ExploreSearchState: Equatable {
    /// No active query — the tab shows its browse feeds.
    case idle
    /// A query is in flight.
    case loading
    /// The catalog answered with models.
    case results([GalleryModel])
    /// The catalog answered, with nothing, for this query.
    case noResults(query: String)
    /// The catalog never answered usably.
    case failed(ExploreSearchFailure)
}

/// Owns the Explore tab's search text, its debounce, and its four states.
@MainActor
@Observable
final class ExploreSearchModel {

    /// Page size asked of the source (Sketchfab caps at 24).
    nonisolated static let resultLimit = 24
    /// Keystrokes below this length never fire a live search — Android parity.
    nonisolated static let minimumQueryLength = 2
    /// Pause after the last keystroke before a live search fires — Android parity.
    nonisolated static let debounceDelay: Duration = .milliseconds(350)

    /// The query currently being executed / displayed. Empty means "browse".
    private(set) var activeQuery: String = ""
    /// What the results area renders.
    private(set) var state: ExploreSearchState = .idle
    /// Latched once a source answered 401/403, so the tab can show its banner.
    private(set) var keyRejected = false
    /// Bumped by `retry()`. Part of the view's `.task(id:)` key, because
    /// re-assigning the same query within one run loop does not re-key a task.
    private(set) var retryToken = 0

    /// Test seam: a shorter debounce so the suite doesn't sleep for real.
    private let delay: Duration

    init(debounceDelay: Duration = ExploreSearchModel.debounceDelay) {
        self.delay = debounceDelay
    }

    /// `true` while a query drives the screen — the browse feeds stay hidden.
    var isSearching: Bool { !activeQuery.isEmpty }

    // MARK: - Pure decisions (unit-tested without a network or a view)

    /// The query form sent to a catalog: trimmed, never padded.
    nonisolated static func normalize(_ raw: String) -> String {
        raw.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Should a keystroke of `raw` start a live search, given what is running?
    ///
    /// Blocks single letters (noise, and a full page of unrelated hits) and
    /// re-runs of the query already on screen (a trailing space is not a new
    /// search).
    nonisolated static func shouldAutoSearch(_ raw: String, activeQuery: String) -> Bool {
        let trimmed = normalize(raw)
        return trimmed.count >= minimumQueryLength && trimmed != activeQuery
    }

    // MARK: - Entry points

    /// The field's text changed. Falling below `minimumQueryLength` cancels the
    /// search immediately — waiting out the debounce would leave stale results
    /// under a field that can no longer produce them. Emptying is only the
    /// common case: `debouncedActivate` refuses anything shorter than the
    /// minimum, so deleting "helmet" down to "h" used to keep the helmet
    /// results on screen forever.
    func fieldChanged(_ raw: String) {
        if Self.normalize(raw).count < Self.minimumQueryLength { cancel() }
    }

    /// Debounced live search, driven by the view's `.task(id: searchText)`:
    /// each keystroke cancels the previous run mid-sleep, so `activeQuery` only
    /// moves once the user pauses.
    func debouncedActivate(text raw: String) async {
        guard Self.shouldAutoSearch(raw, activeQuery: activeQuery) else { return }
        do { try await Task.sleep(for: delay) } catch { return }
        guard !Task.isCancelled else { return }
        activeQuery = Self.normalize(raw)
    }

    /// Explicit submit (Return key). Skips the debounce and returns the query so
    /// the caller can record it in the recent-search history — live keystroke
    /// fragments must never land there.
    @discardableResult
    func submit(text raw: String) -> String? {
        let query = Self.normalize(raw)
        guard !query.isEmpty else { return nil }
        activeQuery = query
        return query
    }

    /// Run the same query again after a failure.
    func retry() {
        guard !activeQuery.isEmpty else { return }
        retryToken += 1
    }

    /// Back to the browse feeds.
    func cancel() {
        activeQuery = ""
        state = .idle
    }

    /// Source switch: drop the query and everything the previous catalog said.
    func reset() {
        cancel()
        keyRejected = false
    }

    // MARK: - Execution

    /// Execute `activeQuery` against `source` and land on one of the four states.
    ///
    /// Called from a `.task(id:)` keyed on query + source + `retryToken`, so a
    /// superseded run is cancelled rather than racing the new one; the guard
    /// after the await covers the window where cancellation lands late.
    func run(source: any ModelSource) async {
        let query = activeQuery
        guard !query.isEmpty else {
            state = .idle
            return
        }
        state = .loading
        do {
            let models = try await source.search(query: query, limit: Self.resultLimit)
            guard !Task.isCancelled, query == activeQuery else { return }
            state = models.isEmpty ? .noResults(query: query) : .results(models)
        } catch {
            guard !Task.isCancelled, query == activeQuery else { return }
            guard !Self.isCancellation(error) else { return }
            if Self.isKeyRejection(error) {
                keyRejected = true
                state = .failed(.keyRejected(source.id.displayName))
            } else {
                // Transient by default — a blip, a 5xx, a rate limit. It clears
                // on the next query, so nothing is latched here.
                state = .failed(.unreachable(source.id.displayName))
            }
        }
    }

    // MARK: - Error classification

    /// A superseded call, not a failure: it must not paint an error over the
    /// results the newer query is about to deliver.
    nonisolated static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let urlError = error as? URLError, urlError.code == .cancelled { return true }
        return false
    }

    /// 401/403 from either the keyed source or the shared source errors — the
    /// key exists and was refused, which no retry of the same call can fix.
    nonisolated static func isKeyRejection(_ error: Error) -> Bool {
        if let sketchfab = error as? SketchfabError {
            switch sketchfab {
            case .requestFailed(let status): return status == 401 || status == 403
            case .missingApiKey: return true
            default: return false
            }
        }
        if let gallery = error as? GallerySourceError,
           case .requestFailed(let status) = gallery {
            return status == 401 || status == 403
        }
        return false
    }
}

// MARK: - Feed loading

/// The two pure rules behind the browse feeds' "couldn't reach" card (#3766
/// P2 §2), kept out of the view so they can be tested without a network.
enum ExploreFeedLoad {
    /// Runs `operation`, or throws `CancellationError` once `timeout` elapses
    /// first. The losing side is cancelled — a stalled `URLSession` request
    /// gets torn down instead of lingering until its own 60 s idle limit.
    static func withTimeout<T: Sendable>(
        _ timeout: Duration,
        _ operation: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        try await withThrowingTaskGroup(of: T.self) { group in
            group.addTask { try await operation() }
            group.addTask {
                try await Task.sleep(for: timeout)
                throw CancellationError()
            }
            let first = try await group.next()!
            group.cancelAll()
            return first
        }
    }

    /// "Unreachable" means every feed the source advertises failed *and* none
    /// of them failed because the key was refused — that case has its own
    /// banner and the card must not double up on it.
    static func isUnreachable(feedCount: Int, failures: Int, rejected: Bool) -> Bool {
        feedCount > 0 && failures == feedCount && !rejected
    }

    /// What one feed carousel shows (#3789). A feed used to disappear once it
    /// came back empty, so a failed Trending looked like no Trending at all.
    enum SectionState: Equatable {
        /// Cards, or placeholders while the first load runs.
        case models
        case loading
        /// The heading, then a line saying the feed could not load, with Retry.
        case failed
        /// The heading, then a line saying the feed answered with nothing.
        case empty
        /// Nothing: a message elsewhere already covers it — the one "couldn't
        /// reach" card when every feed failed, or the rejected-key banner.
        case hidden
    }

    static func sectionState(
        hasModels: Bool,
        isLoading: Bool,
        failed: Bool,
        unreachable: Bool,
        keyRejected: Bool
    ) -> SectionState {
        if hasModels { return .models }
        if isLoading { return .loading }
        if unreachable || keyRejected { return .hidden }
        return failed ? .failed : .empty
    }
}
