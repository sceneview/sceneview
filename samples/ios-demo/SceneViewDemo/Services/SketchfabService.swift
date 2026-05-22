import Foundation

/// Errors surfaced by `SketchfabService`.
enum SketchfabError: Error, LocalizedError {
    case missingApiKey
    case requestFailed(statusCode: Int)
    case invalidResponse
    case downloadFailed
    case modelNotFound

    var errorDescription: String? {
        switch self {
        case .missingApiKey:
            return "SKETCHFAB_API_KEY environment variable is not set."
        case .requestFailed(let statusCode):
            return "Sketchfab request failed with HTTP \(statusCode)."
        case .invalidResponse:
            return "Sketchfab returned an unexpected response."
        case .downloadFailed:
            return "Failed to download model from Sketchfab CDN."
        case .modelNotFound:
            return "No downloadable format available for the requested model."
        }
    }
}

/// Thread-safe client for the Sketchfab Data API v3.
///
/// Responsibilities:
/// - Build authenticated `URLRequest`s against `SketchfabConfig.baseURL`.
/// - Decode JSON via `SketchfabModels.swift`.
/// - Stream binary downloads to an on-disk LRU cache under `Caches/sketchfab/`.
///
/// The class is an `actor` so concurrent calls (search-while-downloading) are
/// serialised through the same `URLSession` and cache pruner.
actor SketchfabService {
    static let shared = SketchfabService()

    private let session: URLSession
    private let decoder: JSONDecoder

    init(session: URLSession = .shared) {
        self.session = session
        self.decoder = JSONDecoder()
    }

    // MARK: - Public API

    /// Search models by free-text query.
    ///
    /// - Parameters:
    ///   - query: Search text (forwarded to `q=`).
    ///   - categories: Optional Sketchfab category slugs (`cars-vehicles`, …).
    ///   - downloadable: Restrict to models the API can serve as GLB/glTF.
    ///   - limit: Page size, max 24 per Sketchfab guidelines.
    func search(
        query: String,
        categories: [String]? = nil,
        downloadable: Bool = true,
        limit: Int = 24
    ) async throws -> [SketchfabModel] {
        var items: [URLQueryItem] = [
            URLQueryItem(name: "type", value: "models"),
            URLQueryItem(name: "q", value: query),
            URLQueryItem(name: "downloadable", value: downloadable ? "true" : "false"),
            URLQueryItem(name: "count", value: String(limit))
        ]
        if let categories, !categories.isEmpty {
            items.append(URLQueryItem(name: "categories", value: categories.joined(separator: ",")))
        }
        let response: SketchfabSearchResponse = try await get(path: "search", queryItems: items)
        return response.results
    }

    /// Most-liked downloadable models (alias `popular`), optionally filtered by category.
    func featured(animated: Bool? = nil, category: String? = nil, limit: Int = 6) async throws -> [SketchfabModel] {
        try await list(sortBy: "-likeCount", animated: animated, category: category, limit: limit)
    }

    /// Sketchfab "Staff Picks" — hand-curated by Sketchfab's editorial team.
    func staffPicks(animated: Bool? = nil, category: String? = nil, limit: Int = 6) async throws -> [SketchfabModel] {
        try await list(
            sortBy: "-staffPickedAt",
            staffPicked: true,
            animated: animated,
            category: category,
            limit: limit
        )
    }

    /// Most-viewed downloadable models — trending right now.
    func mostPopular(animated: Bool? = nil, category: String? = nil, limit: Int = 6) async throws -> [SketchfabModel] {
        try await list(sortBy: "-viewCount", animated: animated, category: category, limit: limit)
    }

    /// Recently published downloadable models, optionally filtered by category.
    func recentlyAdded(animated: Bool? = nil, category: String? = nil, limit: Int = 6) async throws -> [SketchfabModel] {
        try await list(sortBy: "-publishedAt", animated: animated, category: category, limit: limit)
    }

    /// Internal helper used by the curated-feed methods.
    private func list(
        sortBy: String,
        staffPicked: Bool = false,
        animated: Bool? = nil,
        category: String? = nil,
        limit: Int
    ) async throws -> [SketchfabModel] {
        var items: [URLQueryItem] = [
            URLQueryItem(name: "type", value: "models"),
            URLQueryItem(name: "sort_by", value: sortBy),
            URLQueryItem(name: "downloadable", value: "true"),
            URLQueryItem(name: "count", value: String(limit))
        ]
        if staffPicked {
            items.append(URLQueryItem(name: "staffpicked", value: "true"))
        }
        if let animated {
            items.append(URLQueryItem(name: "animated", value: animated ? "true" : "false"))
        }
        if let category {
            items.append(URLQueryItem(name: "categories", value: category))
        }
        let response: SketchfabSearchResponse = try await get(path: "models", queryItems: items)
        return response.results
    }

    /// Resolve the signed CDN URL for a model's preferred format (GLB > glTF > USDZ).
    func downloadURL(for uid: String) async throws -> URL {
        let response: SketchfabDownloadResponse = try await get(
            path: "models/\(uid)/download",
            queryItems: []
        )
        guard let preferred = response.preferred, let url = URL(string: preferred.url) else {
            throw SketchfabError.modelNotFound
        }
        return url
    }

    /// Download a model to the on-disk cache and return the local file URL.
    ///
    /// If the file is already cached its modification date is touched (LRU)
    /// and the cached path returned without hitting the network.
    func downloadModel(
        uid: String,
        progress: (@Sendable (Double) -> Void)? = nil
    ) async throws -> URL {
        let cacheURL = try cacheFileURL(for: uid)
        if FileManager.default.fileExists(atPath: cacheURL.path) {
            touch(cacheURL)
            return cacheURL
        }

        let remoteURL = try await downloadURL(for: uid)
        try await downloadBinary(from: remoteURL, to: cacheURL, progress: progress)
        pruneCacheIfNeeded()
        return cacheURL
    }

    // MARK: - Internal helpers

    /// Build the full URL for a given path + query.
    ///
    /// Exposed `internal` so tests can verify path construction without making
    /// network calls.
    nonisolated func buildURL(path: String, queryItems: [URLQueryItem]) -> URL? {
        let baseURL = SketchfabConfig.baseURL.appendingPathComponent(path)
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false)
        if !queryItems.isEmpty {
            components?.queryItems = queryItems
        }
        return components?.url
    }

    private func get<T: Decodable>(path: String, queryItems: [URLQueryItem]) async throws -> T {
        guard let apiKey = SketchfabConfig.apiKey else { throw SketchfabError.missingApiKey }
        guard let url = buildURL(path: path, queryItems: queryItems) else {
            throw SketchfabError.invalidResponse
        }
        var request = URLRequest(url: url)
        request.setValue("Token \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw SketchfabError.invalidResponse }
        guard (200..<300).contains(http.statusCode) else {
            throw SketchfabError.requestFailed(statusCode: http.statusCode)
        }
        do {
            return try decoder.decode(T.self, from: data)
        } catch let decodingError as DecodingError {
            // Log the actual decode failure so schema drift is debuggable —
            // without this, every API change shows up as a generic
            // "invalid response" with no clue what field broke.
            print("[Sketchfab] DecodingError on \(path): \(decodingError)")
            throw SketchfabError.invalidResponse
        } catch {
            print("[Sketchfab] Decode failed on \(path): \(error)")
            throw SketchfabError.invalidResponse
        }
    }

    private func downloadBinary(
        from remoteURL: URL,
        to destination: URL,
        progress: (@Sendable (Double) -> Void)?
    ) async throws {
        // Use URLSessionDownloadDelegate so intermediate progress values are
        // surfaced via the `progress` callback instead of always emitting 1.0
        // at completion (#982). `URLSession.download(from:)` in async mode does
        // not expose per-byte callbacks — the delegate path is the only way.
        let delegate = DownloadProgressDelegate(onProgress: progress)
        let delegateSession = URLSession(
            configuration: .default,
            delegate: delegate,
            delegateQueue: nil
        )
        defer { delegateSession.invalidateAndCancel() }

        // The signed CDN URL must NOT carry the Sketchfab auth header.
        let tempURL = try await withCheckedThrowingContinuation { continuation in
            delegate.continuation = continuation
            delegateSession.downloadTask(with: remoteURL).resume()
        }

        try FileManager.default.createDirectory(
            at: destination.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        if FileManager.default.fileExists(atPath: destination.path) {
            try FileManager.default.removeItem(at: destination)
        }
        try FileManager.default.moveItem(at: tempURL, to: destination)
    }

    // MARK: - Cache management

    private func cacheRoot() throws -> URL {
        let caches = try FileManager.default.url(
            for: .cachesDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let dir = caches.appendingPathComponent(SketchfabConfig.cacheDirectoryName, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func cacheFileURL(for uid: String) throws -> URL {
        try cacheRoot().appendingPathComponent("\(uid).glb")
    }

    private func touch(_ url: URL) {
        try? FileManager.default.setAttributes(
            [.modificationDate: Date()],
            ofItemAtPath: url.path
        )
    }

    /// Evict oldest files when total size exceeds `maxCacheBytes`.
    private func pruneCacheIfNeeded() {
        guard let root = try? cacheRoot() else { return }
        let fm = FileManager.default
        guard let entries = try? fm.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ) else { return }

        let files = entries.compactMap { url -> (URL, Int64, Date)? in
            let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
            let size = Int64(values?.fileSize ?? 0)
            let mtime = values?.contentModificationDate ?? .distantPast
            return (url, size, mtime)
        }
        let total = files.reduce(Int64(0)) { $0 + $1.1 }
        guard total > SketchfabConfig.maxCacheBytes else { return }

        // Oldest first.
        let sorted = files.sorted { $0.2 < $1.2 }
        var running = total
        for (url, size, _) in sorted {
            if running <= SketchfabConfig.maxCacheBytes { break }
            try? fm.removeItem(at: url)
            running -= size
        }
    }
}

// MARK: - DownloadProgressDelegate

/// `URLSessionDownloadDelegate` bridge that surfaces intermediate download
/// progress to the `SketchfabService.downloadBinary` caller via the `progress`
/// closure and completes the async continuation when the download finishes.
///
/// Required because `URLSession.download(from:)` in `async` mode does not
/// expose per-byte callbacks — `URLSessionDownloadDelegate` is the only path
/// for intermediate progress on Apple platforms (#982).
private final class DownloadProgressDelegate: NSObject, URLSessionDownloadDelegate {
    var continuation: CheckedContinuation<URL, Error>?
    private let onProgress: (@Sendable (Double) -> Void)?

    init(onProgress: (@Sendable (Double) -> Void)?) {
        self.onProgress = onProgress
    }

    // MARK: Progress

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        guard totalBytesExpectedToWrite > 0 else { return }
        let fraction = Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)
        onProgress?(max(0.0, min(1.0, fraction)))
    }

    // MARK: Completion

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        // Verify HTTP status before resolving the continuation.
        if let http = downloadTask.response as? HTTPURLResponse,
           !(200..<300).contains(http.statusCode) {
            continuation?.resume(throwing: SketchfabError.downloadFailed)
            continuation = nil
            return
        }
        onProgress?(1.0)
        continuation?.resume(returning: location)
        continuation = nil
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didCompleteWithError error: Error?
    ) {
        if let error {
            continuation?.resume(throwing: error)
            continuation = nil
        }
    }
}
