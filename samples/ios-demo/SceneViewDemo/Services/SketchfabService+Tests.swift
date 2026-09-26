#if DEBUG
import XCTest
@testable import SceneViewDemo

/// Unit tests for `SketchfabService`.
final class SketchfabServiceTests: XCTestCase {

    override func tearDown() {
        SearchStubURLProtocol.reset(json: Data())
        super.tearDown()
    }

    /// Offline: verify the URL builder produces the expected `/v3/search?...`.
    func testDownloadURLPath() async throws {
        let service = SketchfabService()
        let url = service.buildURL(
            path: "models/abc123/download",
            queryItems: []
        )
        XCTAssertEqual(
            url?.absoluteString,
            "https://api.sketchfab.com/v3/models/abc123/download"
        )

        let searchURL = service.buildURL(
            path: "search",
            queryItems: [
                URLQueryItem(name: "type", value: "models"),
                URLQueryItem(name: "q", value: "car"),
                URLQueryItem(name: "downloadable", value: "true"),
                URLQueryItem(name: "count", value: "24")
            ]
        )
        XCTAssertNotNil(searchURL)
        XCTAssertTrue(searchURL!.absoluteString.hasPrefix("https://api.sketchfab.com/v3/search?"))
        let query = searchURL!.query ?? ""
        XCTAssertTrue(query.contains("type=models"))
        XCTAssertTrue(query.contains("q=car"))
        XCTAssertTrue(query.contains("downloadable=true"))
        XCTAssertTrue(query.contains("count=24"))
    }

    /// Offline: hermetic parsing + mapping coverage for `search(query:)`. A
    /// stubbed `URLProtocol` (`SearchStubURLProtocol`) serves a recorded
    /// search-response fixture (`SketchfabSearchFixture`), so this exercises
    /// request construction, JSON decoding and model mapping with no live
    /// network and no real API key — a TLS hiccup or an outage on Sketchfab's
    /// side can no longer fail an unrelated PR (#3811). `SKETCHFAB_API_KEY` is
    /// only set here to satisfy `SketchfabService`'s missing-key guard before
    /// the (stubbed) transport is ever reached; the value itself is never
    /// sent anywhere real. Mirrors the existing `init(session:)` /
    /// `protocolClasses` seams the #2663 regression pin above already uses.
    func testSearchReturnsResults() async throws {
        setenv("SKETCHFAB_API_KEY", "test-fixture-key-not-live", 1)
        SearchStubURLProtocol.reset(json: SketchfabSearchFixture.carSearchResponseJSON)

        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [SearchStubURLProtocol.self]
        let service = SketchfabService(session: URLSession(configuration: configuration))

        let results = try await service.search(query: "car", limit: 5)
        XCTAssertEqual(results.count, 2, "Expected the two models from the recorded fixture")
        guard let first = results.first else { return }
        XCTAssertEqual(first.uid, "fixture-car-0001")
        XCTAssertEqual(first.name, "Classic Muscle Car")
        XCTAssertTrue(first.downloadable)
        XCTAssertEqual(first.thumbnails.images.count, 2)
        XCTAssertEqual(first.tags?.map(\.name), ["car", "vehicle"])
        // The second entry exercises the optional-field decoding paths
        // (`description: null`, `tags: null`) the first entry doesn't cover.
        let second = results[1]
        XCTAssertNil(second.description)
        XCTAssertNil(second.tags)
    }

    /// Online: real round-trip against the live Sketchfab API. Opt-in only —
    /// gated behind `SKETCHFAB_LIVE_INTEGRATION_TEST=1`, which the CI iOS
    /// workflow (`.github/workflows/ios.yml`) never sets, so this can never
    /// fail an unrelated PR the way the unconditional live call used to
    /// (#3811). Run it locally with a real key when validating the live
    /// integration:
    ///
    ///   SKETCHFAB_LIVE_INTEGRATION_TEST=1 SKETCHFAB_API_KEY=<token> \
    ///     xcodebuild test -scheme SceneViewDemo ...
    func testLiveSearchReturnsResults() async throws {
        try XCTSkipUnless(
            ProcessInfo.processInfo.environment["SKETCHFAB_LIVE_INTEGRATION_TEST"] == "1",
            "Live Sketchfab integration test is opt-in — set SKETCHFAB_LIVE_INTEGRATION_TEST=1 to run it"
        )
        try XCTSkipIf(
            SketchfabConfig.apiKey == nil,
            "SKETCHFAB_API_KEY not set — skipping live network test"
        )
        let service = SketchfabService()
        let results = try await service.search(query: "car", limit: 5)
        XCTAssertGreaterThan(results.count, 0, "Expected at least one model for 'car'")
        if let first = results.first {
            XCTAssertFalse(first.uid.isEmpty)
            XCTAssertFalse(first.name.isEmpty)
        }
    }
}

// MARK: - SearchStubURLProtocol

/// Minimal `URLProtocol` that serves a fixed JSON payload for **data** tasks —
/// the transport `SketchfabService.get` uses for `search`/`models`/
/// `download` requests via `session.data(for:)`. Mirrors `StreamStubURLProtocol`
/// below, which does the same for the **download** task used by
/// `downloadBinary`. Configured via `reset(...)` before each test.
private final class SearchStubURLProtocol: URLProtocol {

    /// JSON payload served on success. `nonisolated(unsafe)` is safe here:
    /// tests run serially and mutate this only through `reset(...)` between
    /// requests.
    nonisolated(unsafe) static var json = Data()
    nonisolated(unsafe) static var statusCode = 200

    static func reset(json: Data, statusCode: Int = 200) {
        self.json = json
        self.statusCode = statusCode
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        guard let client else { return }
        let url = request.url ?? URL(string: "https://stub.invalid")!
        let response = HTTPURLResponse(
            url: url,
            statusCode: Self.statusCode,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        client.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client.urlProtocol(self, didLoad: Self.json)
        client.urlProtocolDidFinishLoading(self)
    }
}

// MARK: - SketchfabSearchFixture

/// Recorded `GET /v3/search?type=models&q=car...` response fixture, shaped
/// exactly like the live Sketchfab Data API v3 payload decoded by
/// `SketchfabSearchResponse` (`SketchfabModels.swift`) — two models covering
/// both the populated and the null-optional-field decoding paths
/// (`description`, `tags`). Used by `testSearchReturnsResults` so the test
/// never depends on network access or a real API key (#3811).
private enum SketchfabSearchFixture {
    static let carSearchResponseJSON: Data = Data("""
    {
      "results": [
        {
          "uid": "fixture-car-0001",
          "name": "Classic Muscle Car",
          "description": "A low-poly classic muscle car, stubbed for offline tests.",
          "thumbnails": {
            "images": [
              { "url": "https://media.sketchfab.com/models/fixture-car-0001/thumbnails/fixture/car-1024.jpg", "width": 1024, "height": 576 },
              { "url": "https://media.sketchfab.com/models/fixture-car-0001/thumbnails/fixture/car-256.jpg", "width": 256, "height": 144 }
            ]
          },
          "viewerUrl": "https://sketchfab.com/3d-models/fixture-car-0001",
          "isDownloadable": true,
          "tags": [ { "name": "car" }, { "name": "vehicle" } ],
          "faceCount": 12000,
          "animationCount": 0,
          "likeCount": 42,
          "viewCount": 1337
        },
        {
          "uid": "fixture-car-0002",
          "name": "Rusty Pickup Truck",
          "description": null,
          "thumbnails": {
            "images": [
              { "url": "https://media.sketchfab.com/models/fixture-car-0002/thumbnails/fixture/truck-1024.jpg", "width": 1024, "height": 576 }
            ]
          },
          "viewerUrl": "https://sketchfab.com/3d-models/fixture-car-0002",
          "isDownloadable": true,
          "tags": null,
          "faceCount": 8500,
          "animationCount": 1,
          "likeCount": 7,
          "viewCount": 210
        }
      ],
      "next": null,
      "previous": null
    }
    """.utf8)
}

// MARK: - Streamed-download persistence pin (#2663)

/// Regression pin for the streamed-download -> on-disk-cache persistence path.
///
/// This is the exact code path that was **silently dead from #2252 until
/// #2662**: `SketchfabService.downloadBinary` resumed its continuation with the
/// `URLSession` delegate's temporary file, which Apple deletes the instant the
/// delegate method returns — so the subsequent `moveItem` into the cache threw
/// `NSFileNoSuchFile` on *every* download. `SketchfabAssetResolver` swallowed
/// the throw into its bundled fallback, so every streamed Sketchfab model
/// silently rendered the BUNDLED asset. Compile, unit tests, and screenshot QA
/// all stayed green while the feature was dead for users — this bug class
/// (network succeeds, bytes discarded, feature falls back without error) is
/// invisible to every other gate.
///
/// The network is stubbed with a `URLProtocol` (`StreamStubURLProtocol`) that
/// serves a deterministic 2 MB payload for a **download** task, so no live key
/// and no network are needed and the test runs on keyless CI. Injection uses
/// the minimal `resolvedURL` / `protocolClasses` seams on
/// `downloadModel(uid:)` / `downloadBinary(...)` (both default to `nil` in
/// production).
final class SketchfabServiceStreamPersistenceTests: XCTestCase {

    override func tearDown() {
        StreamStubURLProtocol.reset()
        super.tearDown()
    }

    /// (a) + (b): `downloadModel(uid:)` — the real user-facing entry point —
    /// streams the bytes and returns a cache URL whose contents byte-for-byte
    /// match the source. This assertion is what would have caught #2662: on the
    /// pre-#2662 code the persist throws and no file exists at the returned URL.
    func testDownloadModelPersistsStreamedBytesToCache() async throws {
        let payload = Self.deterministicPayload(byteCount: 2 * 1024 * 1024, seed: 0x2663_2663)
        StreamStubURLProtocol.reset(payload: payload)

        let service = SketchfabService()
        let uid = "pin-2663-ok-\(UUID().uuidString)"
        let remoteURL = URL(string: "https://cdn.test.invalid/\(uid).usdz")!

        let cacheURL = try await service.downloadModel(
            uid: uid,
            resolvedURL: remoteURL,
            protocolClasses: [StreamStubURLProtocol.self]
        )
        defer { try? FileManager.default.removeItem(at: cacheURL) }

        XCTAssertTrue(
            FileManager.default.fileExists(atPath: cacheURL.path),
            "downloadModel must persist the streamed bytes to the cache — the #2662 bug left this file missing"
        )
        let persisted = try Data(contentsOf: cacheURL)
        XCTAssertEqual(
            persisted, payload,
            "cached USDZ bytes must byte-for-byte match the streamed source"
        )
    }

    /// (c): re-downloading over an existing cache entry replaces it — exercises
    /// `downloadBinary`'s `removeItem`-before-`moveItem` branch, which
    /// `downloadModel` never reaches on its own (it short-circuits on a cache
    /// hit). Driven through the smallest internal entry, `downloadBinary`.
    func testReDownloadOverExistingCacheEntryReplacesBytes() async throws {
        let service = SketchfabService()
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("pin-2663-redl-\(UUID().uuidString).usdz")
        defer { try? FileManager.default.removeItem(at: destination) }

        let first = Self.deterministicPayload(byteCount: 512 * 1024, seed: 0x0000_AAAA)
        StreamStubURLProtocol.reset(payload: first)
        try await service.downloadBinary(
            from: URL(string: "https://cdn.test.invalid/first.usdz")!,
            to: destination,
            progress: nil,
            protocolClasses: [StreamStubURLProtocol.self]
        )
        XCTAssertEqual(try Data(contentsOf: destination), first)

        let second = Self.deterministicPayload(byteCount: 512 * 1024, seed: 0x0000_BBBB)
        StreamStubURLProtocol.reset(payload: second)
        try await service.downloadBinary(
            from: URL(string: "https://cdn.test.invalid/second.usdz")!,
            to: destination,
            progress: nil,
            protocolClasses: [StreamStubURLProtocol.self]
        )
        XCTAssertEqual(
            try Data(contentsOf: destination), second,
            "re-download over an existing cache entry must replace its bytes (removeItem branch)"
        )
    }

    /// (d): a mid-stream transfer failure surfaces as a thrown error and leaves
    /// NO bogus cache file — the failure must not be swallowed into a partial /
    /// zero-byte entry that later masquerades as a valid model.
    func testMidStreamErrorThrowsAndLeavesNoCacheFile() async throws {
        let service = SketchfabService()
        let destination = FileManager.default.temporaryDirectory
            .appendingPathComponent("pin-2663-err-\(UUID().uuidString).usdz")
        defer { try? FileManager.default.removeItem(at: destination) }

        let payload = Self.deterministicPayload(byteCount: 1024 * 1024, seed: 0x0000_CAFE)
        StreamStubURLProtocol.reset(payload: payload, failMidStream: true)

        do {
            try await service.downloadBinary(
                from: URL(string: "https://cdn.test.invalid/broken.usdz")!,
                to: destination,
                progress: nil,
                protocolClasses: [StreamStubURLProtocol.self]
            )
            XCTFail("A mid-stream transfer failure must surface as a thrown error, not a silent success")
        } catch {
            // Expected — the download failed mid-transfer.
        }
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: destination.path),
            "a failed download must not leave a bogus (partial) cache file behind"
        )
    }

    // MARK: Helpers

    /// Deterministic pseudo-random payload (xorshift64) so the byte-exact match
    /// is reproducible without bundling a fixture file.
    static func deterministicPayload(byteCount: Int, seed: UInt64) -> Data {
        var state = seed == 0 ? 0x9E37_79B9_7F4A_7C15 : seed
        var bytes = [UInt8]()
        bytes.reserveCapacity(byteCount)
        for _ in 0..<byteCount {
            state ^= state << 13
            state ^= state >> 7
            state ^= state << 17
            bytes.append(UInt8(truncatingIfNeeded: state))
        }
        return Data(bytes)
    }
}

// MARK: - StreamStubURLProtocol

/// Minimal `URLProtocol` that serves a fixed byte payload (or a mid-stream
/// failure) for **download** tasks. `URLSession` accumulates the `didLoad`
/// bytes into the temporary file it hands to
/// `URLSessionDownloadDelegate.didFinishDownloadingTo`, exactly as a real CDN
/// transfer would — so the streamed-persist path is exercised end-to-end with
/// no live network. Configured via `reset(...)` before each test.
private final class StreamStubURLProtocol: URLProtocol {

    /// Payload served on success. `nonisolated(unsafe)` is safe here: tests run
    /// serially and mutate this only through `reset(...)` between requests.
    nonisolated(unsafe) static var payload = Data()

    /// When `true`, serve half the payload then fail — a dropped connection
    /// mid-transfer.
    nonisolated(unsafe) static var failMidStream = false

    static func reset(payload: Data = Data(), failMidStream: Bool = false) {
        self.payload = payload
        self.failMidStream = failMidStream
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        guard let client else { return }
        let url = request.url ?? URL(string: "https://stub.invalid")!
        let response = HTTPURLResponse(
            url: url,
            statusCode: 200,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Length": String(Self.payload.count)]
        )!
        client.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)

        if Self.failMidStream {
            let half = Self.payload.prefix(Self.payload.count / 2)
            client.urlProtocol(self, didLoad: Data(half))
            client.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost))
            return
        }

        client.urlProtocol(self, didLoad: Self.payload)
        client.urlProtocolDidFinishLoading(self)
    }
}
#endif
