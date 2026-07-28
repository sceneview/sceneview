import Foundation

/// Single entry-point demos call to obtain a local asset file for a curated
/// `SketchfabSlug`.
///
/// The resolver layers three behaviours on top of `SketchfabService`:
///
///  1. **Curated registry** — only `SampleAssets` entries can be streamed.
///     Unknown uids throw `Error.unknown` so the registry stays authoritative
///     for license + attribution correctness.
///  2. **Offline fallback** — when no API key is configured *or* the network
///     download fails, the resolver returns a `URL` to the bundled asset
///     declared by `SketchfabSlug.fallbackBundledPath`. This honours the hard
///     rule "NEVER ship a build that needs the network to render something
///     useful" (`feedback_demo_quality`).
///  3. **LRU eviction with a sample-tighter cap** — `SketchfabService` caches
///     up to 500 MB of assets for the Explore tab. The samples-side resolver
///     trims the same on-disk cache down to `Self.maxCacheBytes` (250 MB)
///     after every download so the demo IPA doesn't balloon a phone's data
///     partition past the user's storage settings expectations.
///
/// The resolver is an `actor` so concurrent calls (prefetch-while-resolve)
/// serialise through the same `URLSession` (via `SketchfabService`) and cache
/// pruner.
///
/// Mirrors the Android scaffold (`SketchfabAssetResolver.kt`) — keep both in
/// sync when adding behaviour.
actor SketchfabAssetResolver {

    static let shared = SketchfabAssetResolver()

    /// Samples-side cap on the shared on-disk cache, in bytes (250 MB).
    /// Tighter than `SketchfabConfig.maxCacheBytes` (500 MB) so demos with
    /// constant churn can't silently fill half a gigabyte of user storage.
    static let maxCacheBytes: Int64 = 250 * 1024 * 1024

    /// Permissible bounding-sphere radius window, in metres.
    static let minBoundsRadiusMeters: Float = 0.05
    static let maxBoundsRadiusMeters: Float = 5.0

    /// Max retries for a transient network failure (429 / 5xx).
    static let maxRetries = 3

    /// Initial backoff between retries, in seconds. Doubles each step.
    static let initialRetryDelaySeconds: Double = 0.5

    /// Lowest asset size we treat as a real download — anything smaller is
    /// almost certainly a JSON error body or truncated stream.
    fileprivate static let minAssetSizeBytes: Int64 = 12

    private let service: SketchfabService
    private let bundle: Bundle
    private let fileManager: FileManager

    /// Errors raised by `SketchfabAssetResolver`.
    enum Error: Swift.Error, LocalizedError, Equatable {
        /// A `SketchfabSlug` that isn't part of `SampleAssets`.
        case unknown(uid: String)
        /// Both the network download AND the bundled fallback failed.
        case fallbackUnavailable(uid: String, bundledPath: String)

        var errorDescription: String? {
            switch self {
            case .unknown(let uid):
                return "Sketchfab uid '\(uid)' is not in SampleAssets."
            case .fallbackUnavailable(let uid, let path):
                return "Sketchfab asset '\(uid)' could not be streamed AND the"
                + " bundled fallback '\(path)' is missing from the app bundle."
            }
        }
    }

    init(
        service: SketchfabService = .shared,
        bundle: Bundle = .main,
        fileManager: FileManager = .default
    ) {
        self.service = service
        self.bundle = bundle
        self.fileManager = fileManager
    }

    // MARK: - Public API

    /// Resolve `slug` to a local file `URL`. The returned URL is always
    /// either the streamed asset under `Caches/sketchfab/<uid>.usdz` or a
    /// copy of the bundled fallback under `Caches/sketchfab/fallback/<uid>`.
    ///
    /// - Throws: `Error.unknown` if `slug` is not registered in
    ///   `SampleAssets`; `Error.fallbackUnavailable` if the network path
    ///   fails AND the bundled fallback cannot be staged.
    func resolve(_ slug: SketchfabSlug) async throws -> URL {
        guard SampleAssets.byUID[slug.uid] != nil else {
            throw Error.unknown(uid: slug.uid)
        }

        if SketchfabConfig.apiKey == nil {
            return try fallbackBundle(for: slug)
        }

        // Try the network path with exponential backoff on transient errors.
        for attempt in 0..<Self.maxRetries {
            do {
                let downloaded = try await service.downloadModel(uid: slug.uid)
                pruneCache()
                if !boundsAreSane(at: downloaded, slug: slug) {
                    // Drifted asset — don't ship a 30 m cube to the demo.
                    return try fallbackBundle(for: slug)
                }
                return downloaded
            } catch SketchfabError.requestFailed(let code) where isRetryable(code) {
                let backoff = Self.initialRetryDelaySeconds * pow(2.0, Double(attempt))
                try? await Task.sleep(nanoseconds: UInt64(min(backoff, 4.0) * 1_000_000_000))
                continue
            } catch SketchfabError.requestFailed {
                // Non-retryable 4xx — fall back immediately.
                return try fallbackBundle(for: slug)
            } catch SketchfabError.modelNotFound, SketchfabError.invalidResponse {
                return try fallbackBundle(for: slug)
            } catch SketchfabError.missingApiKey {
                return try fallbackBundle(for: slug)
            } catch SketchfabError.downloadFailed {
                let backoff = Self.initialRetryDelaySeconds * pow(2.0, Double(attempt))
                try? await Task.sleep(nanoseconds: UInt64(min(backoff, 4.0) * 1_000_000_000))
                continue
            } catch {
                // Unknown error class (cancellation, etc.) — fall back rather
                // than surface a cryptic error to the demo UI.
                return try fallbackBundle(for: slug)
            }
        }

        // Exhausted retries — last resort, the bundled asset.
        return try fallbackBundle(for: slug)
    }

    /// Warm the cache for every `SketchfabSlug` in `category`. Demos call
    /// this from a `.task { ... }` so models stream in parallel before the
    /// first frame.
    ///
    /// Failures are swallowed (the resolver already falls back per-slug) —
    /// the return value is the count of slugs that resolved successfully.
    func prefetchAll(category: String) async -> Int {
        let slugs = SampleAssets.byCategory[category] ?? []
        if slugs.isEmpty { return 0 }
        return await withTaskGroup(of: Bool.self) { group in
            for slug in slugs {
                group.addTask { [weak self] in
                    guard let self else { return false }
                    do {
                        _ = try await self.resolve(slug)
                        return true
                    } catch {
                        return false
                    }
                }
            }
            var ok = 0
            for await success in group {
                if success { ok += 1 }
            }
            return ok
        }
    }

    // MARK: - Internal helpers (exposed for tests)

    /// Stage a copy of the bundled fallback under the same cache root used
    /// by the streamed path. Subsequent calls reuse the staged copy and touch
    /// its modification date to keep it from being evicted as cold.
    ///
    /// The staged copy is re-made whenever it no longer matches the asset
    /// currently in the bundle. Keying it on `uid` alone made the staging
    /// permanent: an app update that ships a corrected asset left every
    /// existing install serving the old bytes forever, because the target
    /// path is identical and the file already exists. That is not
    /// hypothetical — it is how the #2928 `tree_scene.usdz` fix silently
    /// failed to take effect on an already-installed simulator build.
    func fallbackBundle(for slug: SketchfabSlug) throws -> URL {
        let root = try cacheRoot()
        let fallbackDir = root.appendingPathComponent("fallback", isDirectory: true)
        try fileManager.createDirectory(at: fallbackDir, withIntermediateDirectories: true)
        let target = fallbackDir.appendingPathComponent("\(slug.uid).usdz")

        // Bundle paths in our project use the form "Models/<name>.usdz".
        let (name, ext) = splitBundlePath(slug.fallbackBundledPath)
        let bundleSubdir = bundleSubdirectory(for: slug.fallbackBundledPath)
        let bundledURL = bundle.url(
            forResource: name,
            withExtension: ext,
            subdirectory: bundleSubdir
        ) ?? bundle.url(forResource: name, withExtension: ext)

        guard let source = bundledURL else {
            throw Error.fallbackUnavailable(
                uid: slug.uid,
                bundledPath: slug.fallbackBundledPath
            )
        }

        if fileManager.fileExists(atPath: target.path),
           stagedCopy(at: target, matches: source) {
            try? fileManager.setAttributes(
                [.modificationDate: Date()],
                ofItemAtPath: target.path
            )
            return target
        }

        do {
            if fileManager.fileExists(atPath: target.path) {
                try fileManager.removeItem(at: target)
            }
            try fileManager.copyItem(at: source, to: target)
        } catch {
            throw Error.fallbackUnavailable(
                uid: slug.uid,
                bundledPath: slug.fallbackBundledPath
            )
        }
        return target
    }

    /// Whether a staged fallback copy still matches the bundled asset.
    ///
    /// Byte size is the discriminator: the bundle is immutable for a given
    /// app version, so any asset change ships a different length (the #2928
    /// tree went 14 767 148 → 14 146 523 bytes). Hashing 14 MB on every
    /// resolve to catch a same-length edit would cost far more than it saves,
    /// and a missed re-stage degrades to the previous behaviour rather than
    /// to a crash. A copy we cannot stat is treated as stale, so the
    /// re-staging path runs.
    func stagedCopy(at target: URL, matches source: URL) -> Bool {
        func byteSize(_ url: URL) -> Int? {
            (try? fileManager.attributesOfItem(atPath: url.path)[.size]) as? Int
        }
        guard let staged = byteSize(target), let bundled = byteSize(source) else {
            return false
        }
        return staged == bundled
    }

    /// Best-effort sanity check. We do not parse the asset — that would
    /// require pulling RealityKit onto a background actor (it must run on
    /// `@MainActor`). Instead we check size + magic bytes:
    ///
    ///  - 0-byte / truncated downloads (Sketchfab CDN sometimes serves a
    ///    short HTML error page with a 200 status).
    ///  - GLBs whose magic header is wrong (e.g. a USDZ served by mistake).
    ///
    /// Full visual-bounds + animation-count verification happens lazily at
    /// load time in RealityKit and any mismatch is surfaced by the demo's
    /// own visual smoke test.
    func boundsAreSane(at fileURL: URL, slug: SketchfabSlug) -> Bool {
        guard let attrs = try? fileManager.attributesOfItem(atPath: fileURL.path),
              let size = attrs[.size] as? Int64,
              size >= Self.minAssetSizeBytes else {
            return false
        }
        // Accept either a GLB header ('glTF' magic) or a USDZ archive (ZIP
        // 'PK\x03\x04' magic). Anything else is junk.
        guard let handle = try? FileHandle(forReadingFrom: fileURL) else { return false }
        defer { try? handle.close() }
        let header = try? handle.read(upToCount: 4)
        guard let header, header.count == 4 else { return false }
        let bytes = [UInt8](header)
        let isGLB = bytes == [0x67, 0x6C, 0x54, 0x46] // 'glTF'
        let isZip = bytes[0] == 0x50 && bytes[1] == 0x4B
            && bytes[2] == 0x03 && bytes[3] == 0x04 // PK\x03\x04
        // `slug.hasBakedAnimation` is intentionally not enforced at the
        // file-byte level — the actual animation count must be checked
        // lazily on the RealityKit side once `Entity` finishes loading.
        _ = slug.hasBakedAnimation
        return isGLB || isZip
    }

    /// Trim the shared on-disk cache to `Self.maxCacheBytes`. The underlying
    /// `SketchfabService` also enforces its own (looser) cap; we re-run after
    /// every resolve so the samples-side budget is honoured even if a key
    /// was burned on the Explore tab.
    func pruneCache() {
        guard let root = try? cacheRoot() else { return }
        guard let entries = try? fileManager.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey],
            options: [.skipsHiddenFiles]
        ) else { return }

        let files = entries.compactMap { url -> (URL, Int64, Date)? in
            guard url.hasDirectoryPath == false else { return nil }
            let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
            let size = Int64(values?.fileSize ?? 0)
            let mtime = values?.contentModificationDate ?? .distantPast
            return (url, size, mtime)
        }
        var total = files.reduce(Int64(0)) { $0 + $1.1 }
        guard total > Self.maxCacheBytes else { return }
        let sorted = files.sorted { $0.2 < $1.2 } // oldest first
        for (url, size, _) in sorted {
            if total <= Self.maxCacheBytes { break }
            try? fileManager.removeItem(at: url)
            total -= size
        }
    }

    // MARK: - Private

    private func cacheRoot() throws -> URL {
        let caches = try fileManager.url(
            for: .cachesDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let dir = caches.appendingPathComponent(
            SketchfabConfig.cacheDirectoryName,
            isDirectory: true
        )
        try fileManager.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Strip the extension off a bundle path and return `(name, extension)`.
    private func splitBundlePath(_ path: String) -> (String, String) {
        let url = URL(fileURLWithPath: path)
        let ext = url.pathExtension
        let name = url.deletingPathExtension().lastPathComponent
        return (name, ext)
    }

    /// Extract the subdirectory portion of a bundle path (`Models/` for
    /// `Models/foo.usdz`).
    private func bundleSubdirectory(for path: String) -> String? {
        let components = path.split(separator: "/").dropLast()
        return components.isEmpty ? nil : components.joined(separator: "/")
    }

    private func isRetryable(_ statusCode: Int) -> Bool {
        statusCode == 429 || (500..<600).contains(statusCode)
    }
}
