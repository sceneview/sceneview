import CoreML
import CryptoKit
import Foundation

/// One file of a pinned remote model, verified by SHA-256 before use.
public struct PinnedModelFile: Sendable, Hashable {
    /// Path inside the `.mlpackage`.
    public let path: String
    public let sha256: String
    public let size: Int64

    public init(path: String, sha256: String, size: Int64) {
        self.path = path
        self.sha256 = sha256
        self.size = size
    }
}

/// A Core ML package fetched file by file from an immutable revision, every
/// file checked against its SHA-256 before the model is compiled.
public struct PinnedRemoteModel: Sendable, Hashable {
    /// Package name without extension.
    public let name: String
    /// The immutable revision the files are pinned to (a commit, never a branch).
    public let revision: String
    /// URL of the `.mlpackage` folder at ``revision``.
    public let packageURL: URL
    public let files: [PinnedModelFile]
    /// Where the model comes from and under which licence, for credits screens.
    public let attribution: String

    public init(name: String, revision: String, packageURL: URL, files: [PinnedModelFile], attribution: String) {
        self.name = name
        self.revision = revision
        self.packageURL = packageURL
        self.files = files
        self.attribution = attribution
    }

    public var totalBytes: Int64 { files.reduce(0) { $0 + $1.size } }

    /// The cache folder name: model name **and** revision, so a new pin never
    /// reuses a model compiled from an older one.
    public var cacheKey: String { "\(name)@\(revision.prefix(12))" }

    static let appleRevision = "cfef6f6f2a70783dedc0bfae40cecbc2052285d3"
    static let appleAttribution = "Depth Anything V2 Small (Yang et al., 2024), Core ML conversion by Apple, Apache-2.0. "
        + "huggingface.co/apple/coreml-depth-anything-v2-small"

    /// The default: Apple's Core ML build of Depth Anything V2 **Small** with
    /// 8-bit palettised weights and F16 activations, 518×392, 25.4 MB,
    /// Apache-2.0 (Base and Large are CC-BY-NC-4.0 and not supported). The
    /// counterpart of Android's int8 LiteRT model (27.7 MB). Pinned to
    /// revision `cfef6f6f2a70783dedc0bfae40cecbc2052285d3` of
    /// `huggingface.co/apple/coreml-depth-anything-v2-small`.
    public static let depthAnythingV2SmallF16INT8 = PinnedRemoteModel(
        name: "DepthAnythingV2SmallF16INT8",
        revision: appleRevision,
        packageURL: URL(string: "https://huggingface.co/apple/coreml-depth-anything-v2-small/resolve/\(appleRevision)/DepthAnythingV2SmallF16INT8.mlpackage/")!,
        files: [
            PinnedModelFile(path: "Manifest.json",
                            sha256: "4b0fe646aab84e5a50d0f75e4ec7c68f0a0552c917856eaeb9b56376f43adcbb",
                            size: 617),
            PinnedModelFile(path: "Data/com.apple.CoreML/model.mlmodel",
                            sha256: "9fa7a0f68615638a8f2d25ba79d323cf3ac70b8fe1f69a3a9c7b43b022a3cacd",
                            size: 427_587),
            PinnedModelFile(path: "Data/com.apple.CoreML/weights/weight.bin",
                            sha256: "a8b775b4f0f1f843d6d7252eb4487ad2ebbcbcf7e35f3ddab3d98f030b9cbeb5",
                            size: 24_967_424)
        ],
        attribution: appleAttribution
    )

    /// The same model with full F16 weights, 49.8 MB, from the same revision —
    /// for comparing quality against the default.
    public static let depthAnythingV2SmallF16 = PinnedRemoteModel(
        name: "DepthAnythingV2SmallF16",
        revision: appleRevision,
        packageURL: URL(string: "https://huggingface.co/apple/coreml-depth-anything-v2-small/resolve/\(appleRevision)/DepthAnythingV2SmallF16.mlpackage/")!,
        files: [
            PinnedModelFile(path: "Manifest.json",
                            sha256: "2883ae290c48fe916dc5ececac03a7d847fa277165a49ef5652fa1d2b9cb55f7",
                            size: 617),
            PinnedModelFile(path: "Data/com.apple.CoreML/model.mlmodel",
                            sha256: "44ac97a3efcfd52113183fb2862ff59cd0368e9ec2e30a90a54980dd11407042",
                            size: 399_433),
            PinnedModelFile(path: "Data/com.apple.CoreML/weights/weight.bin",
                            sha256: "fa60d9b6a155734f59029ebb882fd54e549bfaee3539c1a9cbd2cbbab64a0fed",
                            size: 49_419_072)
        ],
        attribution: appleAttribution
    )
}

/// Where a ``DepthModelStore`` gets the model from. A library cannot declare
/// On-Demand Resources or Background Assets itself: the app chooses.
public enum DepthModelSource: Sendable {
    /// A `.mlmodelc` already compiled (e.g. shipped in the app bundle).
    case compiledModel(URL)
    /// A `.mlpackage` or `.mlmodel` compiled on the device on first use.
    case modelPackage(URL)
    /// Downloaded once from a pinned revision, checksummed, then cached.
    case pinnedDownload(PinnedRemoteModel = .depthAnythingV2SmallF16INT8)
    /// An On-Demand Resources tag declared by the app, holding
    /// `<resourceName>.mlpackage` (or `.mlmodelc`).
    case onDemandResource(tag: String, resourceName: String = "DepthAnythingV2SmallF16INT8")

    /// The cache folder this source compiles into, or nil when the model is
    /// used in place.
    var cacheKey: String? {
        switch self {
        case .compiledModel: return nil
        case .modelPackage(let url): return url.deletingPathExtension().lastPathComponent
        case .pinnedDownload(let remote): return remote.cacheKey
        case .onDemandResource(_, let resourceName): return resourceName
        }
    }
}

/// Fetches, verifies, compiles and caches depth models in
/// `Application Support/SceneViewDepthML` (excluded from backups). Nothing is
/// downloaded unless the app asks for ``DepthModelSource/pinnedDownload(_:)``.
public final class DepthModelStore: @unchecked Sendable {
    public static let shared = DepthModelStore()

    public let directory: URL
    private let fileManager = FileManager.default

    public init(directory: URL? = nil) {
        self.directory = directory ?? FileManager.default
            .urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("SceneViewDepthML", isDirectory: true)
    }

    /// The compiled model cached for `key` (a ``PinnedRemoteModel/cacheKey``,
    /// an On-Demand Resources name or a package name), if it is on disk.
    public func cachedCompiledModel(named key: String = PinnedRemoteModel.depthAnythingV2SmallF16INT8.cacheKey) -> URL? {
        let url = compiledURL(named: key)
        return fileManager.fileExists(atPath: url.path) ? url : nil
    }

    /// Deletes the cached compiled model (and any interrupted download).
    public func removeCachedModel(named key: String = PinnedRemoteModel.depthAnythingV2SmallF16INT8.cacheKey) throws {
        for url in [compiledURL(named: key), stagingURL(named: key)] where fileManager.fileExists(atPath: url.path) {
            try fileManager.removeItem(at: url)
        }
    }

    /// Fetches the model if needed and loads it. When a cached model fails to
    /// load (a damaged cache, an interrupted OS update), the cache is purged
    /// and the model fetched again, once.
    ///
    /// - Parameter progress: download fraction 0…1, called on an arbitrary queue.
    public func estimator(
        from source: DepthModelSource = .pinnedDownload(),
        computeUnits: MLComputeUnits = .cpuAndNeuralEngine,
        progress: (@Sendable (Double) -> Void)? = nil
    ) async throws -> DepthAnythingV2Estimator {
        let url = try await compiledModel(from: source, progress: progress)
        do {
            return try DepthAnythingV2Estimator(compiledModelURL: url, computeUnits: computeUnits)
        } catch {
            guard let key = source.cacheKey else { throw error }
            try? removeCachedModel(named: key)
            let fresh = try await compiledModel(from: source, progress: progress)
            return try DepthAnythingV2Estimator(compiledModelURL: fresh, computeUnits: computeUnits)
        }
    }

    /// Returns a compiled model ready for `MLModel(contentsOf:)`, fetching and
    /// compiling it first if needed.
    ///
    /// - Parameter progress: download fraction 0…1, called on an arbitrary queue.
    public func compiledModel(
        from source: DepthModelSource,
        progress: (@Sendable (Double) -> Void)? = nil
    ) async throws -> URL {
        switch source {
        case .compiledModel(let url):
            return url
        case .modelPackage(let url):
            return try await compileAndCache(url, name: url.deletingPathExtension().lastPathComponent)
        case .pinnedDownload(let remote):
            if let cached = cachedCompiledModel(named: remote.cacheKey) { return cached }
            let package = try await download(remote, progress: progress)
            defer { try? fileManager.removeItem(at: stagingURL(named: remote.cacheKey)) }
            return try await compileAndCache(package, name: remote.cacheKey)
        case .onDemandResource(let tag, let resourceName):
            if let cached = cachedCompiledModel(named: resourceName) { return cached }
            #if os(iOS) || os(visionOS) || os(tvOS)
            let request = NSBundleResourceRequest(tags: [tag])
            let observation = progress.map { report in
                request.progress.observe(\.fractionCompleted) { p, _ in report(p.fractionCompleted) }
            }
            defer {
                observation?.invalidate()
                request.endAccessingResources()
            }
            try await request.beginAccessingResources()
            if let compiled = request.bundle.url(forResource: resourceName, withExtension: "mlmodelc") {
                return try copyIntoCache(compiled, name: resourceName)
            }
            guard let package = request.bundle.url(forResource: resourceName, withExtension: "mlpackage")
                ?? request.bundle.url(forResource: resourceName, withExtension: "mlmodel") else {
                throw DepthModelError.onDemandResourceMissing(tag: tag)
            }
            return try await compileAndCache(package, name: resourceName)
            #else
            throw DepthModelError.onDemandResourceMissing(tag: tag)
            #endif
        }
    }

    // MARK: - Internals

    private func compiledURL(named name: String) -> URL {
        directory.appendingPathComponent("\(name).mlmodelc", isDirectory: true)
    }

    private func stagingURL(named name: String) -> URL {
        directory.appendingPathComponent("staging-\(name).mlpackage", isDirectory: true)
    }

    private func prepareDirectory() throws {
        try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = directory
        try? url.setResourceValues(values)
    }

    private func compileAndCache(_ package: URL, name: String) async throws -> URL {
        let compiled = try await MLModel.compileModel(at: package)
        defer { try? fileManager.removeItem(at: compiled) }
        return try copyIntoCache(compiled, name: name)
    }

    /// Copies next to the cache first, then swaps it in with one rename: a
    /// crash or a full disk mid-copy leaves the previous model (or nothing),
    /// never half a model under the final name.
    private func copyIntoCache(_ compiled: URL, name: String) throws -> URL {
        try prepareDirectory()
        let destination = compiledURL(named: name)
        let temporary = directory.appendingPathComponent(".incoming-\(UUID().uuidString).mlmodelc", isDirectory: true)
        try fileManager.copyItem(at: compiled, to: temporary)
        do {
            if fileManager.fileExists(atPath: destination.path) {
                _ = try fileManager.replaceItemAt(destination, withItemAt: temporary)
            } else {
                try fileManager.moveItem(at: temporary, to: destination)
            }
        } catch {
            try? fileManager.removeItem(at: temporary)
            throw error
        }
        return destination
    }

    private func download(_ remote: PinnedRemoteModel, progress: (@Sendable (Double) -> Void)?) async throws -> URL {
        try prepareDirectory()
        let staging = stagingURL(named: remote.cacheKey)
        let total = Double(max(1, remote.totalBytes))
        var done: Int64 = 0
        for file in remote.files {
            let destination = staging.appendingPathComponent(file.path)
            if fileManager.fileExists(atPath: destination.path),
               (try? Self.sha256(of: destination)) == file.sha256 {
                done += file.size
                progress?(Double(done) / total)
                continue
            }
            let offset = done
            let delegate = DownloadProgressDelegate { fraction in
                progress?((Double(offset) + fraction * Double(file.size)) / total)
            }
            let (temporary, response) = try await URLSession.shared.download(
                from: remote.packageURL.appendingPathComponent(file.path), delegate: delegate)
            guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                try? fileManager.removeItem(at: temporary)
                throw DepthModelError.download("HTTP \((response as? HTTPURLResponse)?.statusCode ?? -1) for \(file.path)")
            }
            guard try Self.sha256(of: temporary) == file.sha256 else {
                try? fileManager.removeItem(at: temporary)
                throw DepthModelError.checksumMismatch(file: file.path)
            }
            try fileManager.createDirectory(at: destination.deletingLastPathComponent(),
                                            withIntermediateDirectories: true)
            if fileManager.fileExists(atPath: destination.path) { try fileManager.removeItem(at: destination) }
            try fileManager.moveItem(at: temporary, to: destination)
            done += file.size
            progress?(Double(done) / total)
        }
        return staging
    }

    /// Streamed SHA-256, lowercase hex.
    static func sha256(of url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}

/// Reports a download task's progress (the async `download(from:delegate:)`
/// API does not expose the task otherwise).
private final class DownloadProgressDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let report: @Sendable (Double) -> Void
    private var observation: NSKeyValueObservation?

    init(report: @escaping @Sendable (Double) -> Void) {
        self.report = report
    }

    func urlSession(_ session: URLSession, didCreateTask task: URLSessionTask) {
        observation = task.progress.observe(\.fractionCompleted) { [report] progress, _ in
            report(progress.fractionCompleted)
        }
    }

    deinit { observation?.invalidate() }
}
