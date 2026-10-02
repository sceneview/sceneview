import CoreML
import CoreVideo
import Foundation
import XCTest
@testable import SceneViewDepthML
import SceneViewSwift

/// Serves files from memory for `https://models.test/…` and counts requests.
final class StubModelServer: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var files: [String: Data] = [:]
    nonisolated(unsafe) private static var counts: [String: Int] = [:]
    /// Delay before each response, so concurrent callers really overlap.
    nonisolated(unsafe) static var latency: TimeInterval = 0.05

    static func serve(_ files: [String: Data], under name: String) {
        lock.withLock {
            for (path, data) in files { self.files["/\(name)/\(path)"] = data }
        }
    }

    static func reset() {
        lock.withLock {
            files = [:]
            counts = [:]
        }
    }

    static func requests(for name: String, _ path: String) -> Int {
        lock.withLock { counts["/\(name)/\(path)"] ?? 0 }
    }

    static var totalRequests: Int { lock.withLock { counts.values.reduce(0, +) } }

    static func session() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubModelServer.self]
        return URLSession(configuration: configuration)
    }

    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "models.test" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        guard let url = request.url else { return }
        let path = url.path
        let data = Self.lock.withLock { () -> Data? in
            Self.counts[path, default: 0] += 1
            return Self.files[path]
        }
        let latency = Self.lock.withLock { Self.latency }
        DispatchQueue.global().asyncAfter(deadline: .now() + latency) { [self] in
            let response = HTTPURLResponse(url: url, statusCode: data == nil ? 404 : 200,
                                           httpVersion: "HTTP/1.1",
                                           headerFields: ["Content-Length": "\(data?.count ?? 0)"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            if let data { client?.urlProtocol(self, didLoad: data) }
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() {}
}

final class DepthModelStoreTests: XCTestCase {
    private var directory: URL!
    private var store: DepthModelStore!

    override func setUpWithError() throws {
        StubModelServer.reset()
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("DepthModelStoreTests-\(UUID().uuidString)", isDirectory: true)
        store = DepthModelStore(directory: directory, session: StubModelServer.session())
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
        StubModelServer.reset()
    }

    private func entries() -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []).sorted()
    }

    func testWrongHashIsRejectedAndNothingIsCached() async throws {
        let files = TinyDepthModel.packageFiles()
        StubModelServer.serve(files, under: "tampered")
        let remote = TinyDepthModel.remote(name: "tampered", files: files,
                                           corrupt: "Data/com.apple.CoreML/model.mlmodel")
        do {
            _ = try await store.estimator(from: .pinnedDownload(remote), computeUnits: .cpuOnly)
            XCTFail("a file with the wrong SHA-256 must not be used")
        } catch DepthModelError.checksumMismatch(let file) {
            XCTAssertEqual(file, "Data/com.apple.CoreML/model.mlmodel")
        }
        XCTAssertNil(store.cachedCompiledModel(named: remote.cacheKey))
        XCTAssertEqual(entries(), [], "no compiled model, no staging folder left behind")
    }

    func testStaleLeftoversAreSweptAndTheModelStillInstalls() async throws {
        let files = TinyDepthModel.packageFiles()
        StubModelServer.serve(files, under: "resumed")
        let remote = TinyDepthModel.remote(name: "resumed", files: files)
        // What a killed app leaves: a half-written staging folder and a
        // half-copied compiled model, 20 minutes old; plus a fresh staging
        // folder another process may still be writing.
        let fileManager = FileManager.default
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        let stale = directory.appendingPathComponent("staging-\(remote.cacheKey)-OLD.mlpackage")
        try fileManager.createDirectory(at: stale, withIntermediateDirectories: true)
        try Data("{\"partial".utf8).write(to: stale.appendingPathComponent("Manifest.json"))
        let incoming = directory.appendingPathComponent(".incoming-OLD.mlmodelc")
        try fileManager.createDirectory(at: incoming, withIntermediateDirectories: true)
        let recent = directory.appendingPathComponent("staging-other-NEW.mlpackage")
        try fileManager.createDirectory(at: recent, withIntermediateDirectories: true)
        let old = Date().addingTimeInterval(-20 * 60)
        for url in [stale, incoming] {
            try fileManager.setAttributes([.modificationDate: old], ofItemAtPath: url.path)
        }

        let estimator = try await store.estimator(from: .pinnedDownload(remote), computeUnits: .cpuOnly)

        XCTAssertEqual(estimator.inputSize.width, TinyDepthModel.width)
        XCTAssertEqual(entries(), ["\(remote.cacheKey).mlmodelc", "staging-other-NEW.mlpackage"])
        // The partial Manifest.json was not reused: every file came fresh.
        XCTAssertEqual(StubModelServer.requests(for: "resumed", "Manifest.json"), 1)
    }

    func testConcurrentRequestsShareOneDownload() async throws {
        let files = TinyDepthModel.packageFiles()
        StubModelServer.serve(files, under: "shared")
        let remote = TinyDepthModel.remote(name: "shared", files: files)
        let store = self.store!
        let progressCalls = Counter()
        let urls = try await withThrowingTaskGroup(of: String.self) { group in
            for _ in 0..<8 {
                group.addTask {
                    let estimator = try await store.estimator(
                        from: .pinnedDownload(remote), computeUnits: .cpuOnly,
                        progress: { _ in progressCalls.increment() })
                    return estimator.identifier
                }
            }
            return try await group.reduce(into: [String]()) { $0.append($1) }
        }
        XCTAssertEqual(urls.count, 8)
        XCTAssertEqual(Set(urls).count, 1)
        for path in files.keys {
            XCTAssertEqual(StubModelServer.requests(for: "shared", path), 1, "\(path) downloaded once")
        }
        XCTAssertGreaterThan(progressCalls.value, 0)
        XCTAssertEqual(entries(), ["\(remote.cacheKey).mlmodelc"])

        // Cached now: no new request.
        _ = try await store.estimator(from: .pinnedDownload(remote), computeUnits: .cpuOnly)
        XCTAssertEqual(StubModelServer.totalRequests, files.count)
    }

    func testUnreadableCacheIsPurgedAndFetchedAgain() async throws {
        let files = TinyDepthModel.packageFiles()
        StubModelServer.serve(files, under: "damaged")
        let remote = TinyDepthModel.remote(name: "damaged", files: files)
        let cached = directory.appendingPathComponent("\(remote.cacheKey).mlmodelc", isDirectory: true)
        try FileManager.default.createDirectory(at: cached, withIntermediateDirectories: true)
        try Data("not a model".utf8).write(to: cached.appendingPathComponent("coremldata.bin"))

        let estimator = try await store.estimator(from: .pinnedDownload(remote), computeUnits: .cpuOnly)
        XCTAssertEqual(estimator.inputSize.height, TinyDepthModel.height)
        XCTAssertEqual(StubModelServer.totalRequests, files.count, "fetched once, after the purge")
    }

    func testAModelThatIsNotADepthModelIsNotFetchedAgain() async throws {
        let files = TinyDepthModel.packageFiles(imageInput: false)
        StubModelServer.serve(files, under: "arrayin")
        let remote = TinyDepthModel.remote(name: "arrayin", files: files)
        do {
            _ = try await store.estimator(from: .pinnedDownload(remote), computeUnits: .cpuOnly)
            XCTFail("a model without an image input is not a depth model")
        } catch DepthModelError.unexpectedModelInterface {
            // Expected.
        }
        XCTAssertEqual(StubModelServer.totalRequests, files.count, "no purge-and-retry for an interface error")
        XCTAssertNotNil(store.cachedCompiledModel(named: remote.cacheKey))
    }

    func testEstimateOnAFixedImage() async throws {
        let package = try TinyDepthModel.writePackage(named: "Tiny", in: directory)
        let estimator = try await store.estimator(from: .modelPackage(package), computeUnits: .cpuOnly)
        XCTAssertEqual(estimator.outputKind, .affineInverse)
        XCTAssertEqual(estimator.identifier, "depth-anything-v2-small:Tiny@4x3")
        XCTAssertEqual(estimator.inputPixelFormat, kCVPixelFormatType_OneComponent8)
        try estimator.warmUp()

        var buffer: CVPixelBuffer?
        CVPixelBufferCreate(nil, TinyDepthModel.width, TinyDepthModel.height, kCVPixelFormatType_OneComponent8,
                            nil, &buffer)
        let image = try XCTUnwrap(buffer)
        let pixels: [UInt8] = (0..<12).map { UInt8($0 * 10) }
        CVPixelBufferLockBaseAddress(image, [])
        let base = try XCTUnwrap(CVPixelBufferGetBaseAddress(image))
        let rowBytes = CVPixelBufferGetBytesPerRow(image)
        for y in 0..<TinyDepthModel.height {
            for x in 0..<TinyDepthModel.width {
                base.storeBytes(of: pixels[y * TinyDepthModel.width + x], toByteOffset: y * rowBytes + x,
                                as: UInt8.self)
            }
        }
        CVPixelBufferUnlockBaseAddress(image, [])

        let estimate = try estimator.estimate(image)
        XCTAssertEqual(estimate.width, TinyDepthModel.width)
        XCTAssertEqual(estimate.height, TinyDepthModel.height)
        let expected = pixels.map { TinyDepthModel.alpha * Float($0) + TinyDepthModel.beta }
        for (value, want) in zip(estimate.values, expected) {
            XCTAssertEqual(value, want, accuracy: 1e-3)
        }
    }
}

final class Counter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    func increment() { lock.withLock { count += 1 } }
    var value: Int { lock.withLock { count } }
}
