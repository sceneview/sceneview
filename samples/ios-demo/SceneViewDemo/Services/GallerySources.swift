import Foundation

// MARK: - Sketchfab source (iOS port of Android `SketchfabSource`, #2645 / #2700)

/// `ModelSource` backed by the Sketchfab Data API v3 — the original catalog, now
/// one implementation among several.
///
/// This adapter is deliberately thin: it maps `SketchfabModel` onto the
/// source-agnostic `GalleryModel` and delegates every network round-trip to the
/// existing `SketchfabService` (which owns the auth, cancellation, and streamed
/// USDZ download path proven in #2356 / #2662), so the multi-source refactor
/// never touched the Sketchfab client.
///
/// Sketchfab serves USDZ, which RealityKit can load, so its models render
/// in-app on Apple platforms (`rendersInApp = true`).
struct SketchfabSource: ModelSource {
    let id: ModelSourceId = .sketchfab

    /// Sketchfab needs an API key; without one the chip is hidden from the picker.
    var isAvailable: Bool { SketchfabConfig.apiKey != nil }

    let feedKinds: [FeedKind] = [.trending, .staffPicks, .recentlyAdded]

    /// Only Sketchfab's feed endpoints accept the `animated` flag.
    let supportsAnimatedFilter = true

    /// Sketchfab serves USDZ → RealityKit can render it in-app.
    let rendersInApp = true

    func feed(kind: FeedKind, animatedOnly: Bool, limit: Int) async throws -> [GalleryModel] {
        let animated: Bool? = animatedOnly ? true : nil
        let models: [SketchfabModel]
        switch kind {
        case .trending:
            // Deliberate: the "Trending" carousel maps to featured() (likeCount
            // ordering), NOT mostPopular() — strict parity with the merged
            // Android port (SketchfabSource.kt: TRENDING -> service.featured()).
            // Renaming the carousel or remapping it is a cross-platform decision,
            // not something to drift on one platform.
            models = try await SketchfabService.shared.featured(animated: animated, limit: limit)
        case .staffPicks:
            models = try await SketchfabService.shared.staffPicks(animated: animated, limit: limit)
        case .recentlyAdded:
            models = try await SketchfabService.shared.recentlyAdded(animated: animated, limit: limit)
        }
        return models.map { $0.toGalleryModel() }
    }

    func search(query: String, limit: Int) async throws -> [GalleryModel] {
        try await SketchfabService.shared.search(query: query, limit: limit).map { $0.toGalleryModel() }
    }

    func download(model: GalleryModel, progress: (@Sendable (Double) -> Void)?) async throws -> URL {
        try await SketchfabService.shared.downloadModel(uid: model.id, progress: progress)
    }
}

extension SketchfabModel {
    /// Map a Sketchfab wire model onto the source-agnostic `GalleryModel`.
    ///
    /// Author + license are left `nil`: the demo's `SketchfabModel` deliberately
    /// does not decode Sketchfab's per-model `user` / `license` blocks (keeping
    /// it in lock-step with the Android scaffold), and the download flow
    /// re-resolves the signed CDN URL from the uid, so no download reference is
    /// carried here.
    func toGalleryModel() -> GalleryModel {
        GalleryModel(
            sourceId: .sketchfab,
            id: uid,
            name: name,
            thumbnails: thumbnails.images.map { GalleryThumbnail(url: $0.url, width: $0.width, height: $0.height) },
            attribution: GalleryAttribution(sourceUrl: viewerUrl),
            tags: tags?.map { $0.name } ?? [],
            faceCount: faceCount,
            animationCount: animationCount,
            downloadable: downloadable
        )
    }
}

// MARK: - Icosa Gallery source (iOS port of Android `IcosaGalleryService`)

/// `ModelSource` for **Icosa Gallery** — the open-source Google Poly successor
/// (`api.icosa.gallery/v1`). Creative-Commons, glTF-native, no auth or free key.
///
/// **Hidden on iOS (#3789).** Icosa is glTF-native and RealityKit cannot load
/// glTF, so none of its entries can open in 3D here: `rendersInApp` stays
/// `false` and `download` throws `renderNotSupported`. Listing the catalog
/// anyway made every tap end on a disabled "3D preview coming soon", and on a
/// keyless build it was the source the user landed on. Some entries also list a
/// USDZ, but only as a web.archive.org copy, and a probe on 2026-09-26 reached
/// 14 of 26 of them (the rest 404 or rate-limited), which is not a path to
/// build on. `isAvailable` is therefore `false` and Poly Haven, whose USD export
/// RealityKit reads, is the keyless default. Flip it back when SceneViewSwift
/// gains a glTF loader (#3655). The Android demo keeps Icosa, since Filament
/// renders its GLB.
struct IcosaGallerySource: ModelSource {
    let id: ModelSourceId = .icosa
    let isAvailable = false
    let feedKinds: [FeedKind] = [.trending, .staffPicks, .recentlyAdded]

    private let baseURL = "https://api.icosa.gallery/v1/"

    private var decoder: JSONDecoder { JSONDecoder() }

    func feed(kind: FeedKind, animatedOnly: Bool, limit: Int) async throws -> [GalleryModel] {
        var items: [URLQueryItem] = [
            URLQueryItem(name: "format", value: "GLTF2"),
            URLQueryItem(name: "pageSize", value: String(limit)),
        ]
        switch kind {
        case .trending:
            items.append(URLQueryItem(name: "orderBy", value: "BEST"))
        case .staffPicks:
            items.append(URLQueryItem(name: "orderBy", value: "BEST"))
            items.append(URLQueryItem(name: "curated", value: "true"))
        case .recentlyAdded:
            items.append(URLQueryItem(name: "orderBy", value: "NEWEST"))
        }
        return try await assets(query: items)
    }

    func search(query: String, limit: Int) async throws -> [GalleryModel] {
        let items: [URLQueryItem] = [
            URLQueryItem(name: "format", value: "GLTF2"),
            URLQueryItem(name: "keywords", value: query),
            URLQueryItem(name: "pageSize", value: String(limit)),
        ]
        return try await assets(query: items)
    }

    func download(model: GalleryModel, progress: (@Sendable (Double) -> Void)?) async throws -> URL {
        throw GallerySourceError.renderNotSupported(sourceName: id.displayName)
    }

    private func assets(query: [URLQueryItem]) async throws -> [GalleryModel] {
        guard var components = URLComponents(string: baseURL + "assets") else {
            throw GallerySourceError.decodeFailed
        }
        components.queryItems = query
        guard let url = components.url else { throw GallerySourceError.decodeFailed }
        let data = try await fetchBoundedData(from: url)
        do {
            let response = try decoder.decode(IcosaListResponse.self, from: data)
            return response.assets.compactMap { $0.toGalleryModel() }
        } catch {
            throw GallerySourceError.decodeFailed
        }
    }
}

// Wire model (only the fields the demo needs).
private struct IcosaListResponse: Decodable {
    let assets: [IcosaAsset]
}

private struct IcosaAsset: Decodable {
    let assetId: String?
    let id: String?
    let name: String?
    let displayName: String?
    let authorName: String?
    let license: String?
    let triangleCount: Int?
    let tags: [String]?
    let thumbnail: IcosaFile?
    let formats: [IcosaFormat]?

    /// Resolve the id across the field names Icosa has used across API revisions.
    var resolvedId: String? { assetId ?? id ?? name }

    /// `true` when the asset carries at least one glTF/GLB format entry.
    var hasRenderableFormat: Bool {
        (formats ?? []).contains { ($0.root?.url) != nil }
    }

    func toGalleryModel() -> GalleryModel? {
        guard let resolvedId, hasRenderableFormat else { return nil }
        let thumbs: [GalleryThumbnail]
        if let thumbURL = thumbnail?.url {
            thumbs = [GalleryThumbnail(
                url: thumbURL,
                width: (thumbnail?.width ?? 0) > 0 ? (thumbnail?.width ?? 1024) : 1024,
                height: (thumbnail?.height ?? 0) > 0 ? (thumbnail?.height ?? 1024) : 1024
            )]
        } else {
            thumbs = []
        }
        return GalleryModel(
            sourceId: .icosa,
            id: resolvedId,
            name: displayName ?? name ?? "Untitled",
            thumbnails: thumbs,
            attribution: GalleryAttribution(
                authorName: authorName,
                license: galleryLicenseDisplayName(license),
                sourceUrl: "https://icosa.gallery/view/\(resolvedId)"
            ),
            tags: tags ?? [],
            faceCount: triangleCount ?? 0
        )
    }
}

private struct IcosaFormat: Decodable {
    let formatType: String?
    let root: IcosaFile?
}

private struct IcosaFile: Decodable {
    let url: String?
    let width: Int?
    let height: Int?
}

// MARK: - Poly Haven source (iOS port of Android `PolyHavenService`)

/// `ModelSource` for **Poly Haven** — CC0 assets with pristine PBR
/// (`api.polyhaven.com`), no auth.
///
/// Poly Haven has no editorial "staff picks" and no server-side keyword search,
/// so this source honestly exposes only Trending (by download count) and
/// Recently added (by publish date), and searches client-side over the models
/// index. An `actor` gives us the single-flight index fetch + in-memory TTL
/// cache for free (mirrors the Android `Mutex` + volatile cache): the Explore tab
/// fires TRENDING + RECENTLY_ADDED concurrently at cold open, and actor
/// serialisation collapses those to one catalog GET.
///
/// **Renders in-app on iOS (#3789).** Next to its glTF, Poly Haven publishes
/// every model as USD: a `.usdc` crate plus the `textures/` it references by
/// relative path. RealityKit reads that file set as it is, so `download` fetches
/// it into one folder per model and hands back the `.usdc` (see
/// `PolyHavenUSD`). That makes Poly Haven the source a keyless build opens on.
///
/// Feeds and search list only the models whose file set fits
/// `PolyHavenUSD.maxTotalBytes`. The size is known only from each model's
/// `/files` record, so the source reads the records of the candidates it is
/// about to show (small, CDN-cached JSON) and keeps them for the download.
actor PolyHavenSource: ModelSource {
    nonisolated let id: ModelSourceId = .polyHaven
    nonisolated let isAvailable = true
    nonisolated let feedKinds: [FeedKind] = [.trending, .recentlyAdded]
    nonisolated let rendersInApp = true

    private let baseURL = "https://api.polyhaven.com/"
    private let cdnBaseURL = "https://cdn.polyhaven.com/"
    /// In-memory TTL for the models index (5 minutes).
    private let indexTTL: TimeInterval = 5 * 60

    private var cachedIndex: [String: PolyHavenAsset]?
    private var cachedAt: Date = .distantPast
    /// USD file sets already resolved, by slug. `nil` marks a model with no
    /// usable USD, so it is not asked for again.
    private var plans: [String: PolyHavenUSD.Plan?] = [:]

    func feed(kind: FeedKind, animatedOnly: Bool, limit: Int) async throws -> [GalleryModel] {
        let index = try await modelsIndex()
        let sorted: [(key: String, value: PolyHavenAsset)]
        switch kind {
        // Ties broken by slug: Poly Haven publishes models in batches that
        // share a date, and the index is a dictionary, so without it the feed
        // reshuffled on every load.
        case .trending:
            sorted = index.sorted {
                ($0.value.downloadCountValue, $1.key) > ($1.value.downloadCountValue, $0.key)
            }
        case .recentlyAdded:
            sorted = index.sorted {
                ($0.value.datePublishedValue, $1.key) > ($1.value.datePublishedValue, $0.key)
            }
        case .staffPicks:
            sorted = Array(index) // unreachable: not in feedKinds
        }
        return try await renderable(sorted, limit: limit)
    }

    func search(query: String, limit: Int) async throws -> [GalleryModel] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !needle.isEmpty else { return [] }
        let index = try await modelsIndex()
        let matches = index
            .filter { $0.value.matches(slug: $0.key, needle: needle) }
            .sorted { ($0.value.downloadCountValue, $1.key) > ($1.value.downloadCountValue, $0.key) }
        return try await renderable(matches, limit: limit)
    }

    func download(model: GalleryModel, progress: (@Sendable (Double) -> Void)?) async throws -> URL {
        let resolved = try await resolvePlans([model.id])
        guard let found = resolved[model.id], let plan = found else {
            throw GallerySourceError.noRenderableFormat
        }
        guard plan.fitsBudget else {
            throw GallerySourceError.responseTooLarge(cap: Int(PolyHavenUSD.maxTotalBytes))
        }
        return try await PolyHavenUSD.download(slug: model.id, plan: plan, progress: progress)
    }

    /// The first `limit` candidates, in order, whose USD file set fits the
    /// budget. Reads the `/files` records one screenful at a time and stops once
    /// it has enough, or after `maxBatches` screenfuls.
    private func renderable(
        _ candidates: [(key: String, value: PolyHavenAsset)],
        limit: Int
    ) async throws -> [GalleryModel] {
        let maxBatches = 3
        var kept: [GalleryModel] = []
        var start = 0
        var batches = 0
        var answered = false
        while kept.count < limit, start < candidates.count, batches < maxBatches {
            let batch = candidates[start..<min(start + limit, candidates.count)]
            start += batch.count
            batches += 1
            let resolved = try await resolvePlans(batch.map { $0.key })
            for (slug, asset) in batch where kept.count < limit {
                guard let found = resolved[slug] else { continue }
                answered = true
                if let plan = found, plan.fitsBudget {
                    kept.append(asset.toGalleryModel(slug: slug, cdnBaseURL: cdnBaseURL))
                }
            }
            // Not one record came back: the files endpoint is down, and an empty
            // list would read as "nothing here" instead of "couldn't load".
            if !answered { throw GallerySourceError.requestFailed(statusCode: 0) }
        }
        return kept
    }

    /// Resolve `slugs` to their USD file sets, reading only the ones not cached.
    /// A slug missing from the result could not be fetched (network); a `nil`
    /// value means the record has no usable USD.
    private func resolvePlans(_ slugs: [String]) async throws -> [String: PolyHavenUSD.Plan?] {
        var result: [String: PolyHavenUSD.Plan?] = [:]
        var missing: [String] = []
        for slug in slugs {
            if let cached = plans[slug] { result[slug] = .some(cached) } else { missing.append(slug) }
        }
        guard !missing.isEmpty else { return result }
        let base = baseURL
        let fetched = await withTaskGroup(of: (String, PolyHavenUSD.Plan??).self) { group in
            for slug in missing {
                group.addTask {
                    guard let url = PolyHavenUSD.filesURL(base: base, slug: slug),
                          let data = try? await fetchBoundedData(from: url) else {
                        return (slug, .none)
                    }
                    return (slug, .some(try? PolyHavenUSD.plan(from: data)))
                }
            }
            var out: [(String, PolyHavenUSD.Plan??)] = []
            for await item in group { out.append(item) }
            return out
        }
        try Task.checkCancellation()
        for (slug, outcome) in fetched {
            guard let plan = outcome else { continue }
            plans[slug] = .some(plan)
            result[slug] = .some(plan)
        }
        return result
    }

    private func modelsIndex() async throws -> [String: PolyHavenAsset] {
        if let cachedIndex, Date().timeIntervalSince(cachedAt) < indexTTL {
            return cachedIndex
        }
        guard var components = URLComponents(string: baseURL + "assets") else {
            throw GallerySourceError.decodeFailed
        }
        components.queryItems = [URLQueryItem(name: "t", value: "models")]
        guard let url = components.url else { throw GallerySourceError.decodeFailed }
        let data = try await fetchBoundedData(from: url)
        let decoder = JSONDecoder()
        decoder.keyDecodingStrategy = .convertFromSnakeCase
        do {
            let index = try decoder.decode([String: PolyHavenAsset].self, from: data)
            cachedIndex = index
            cachedAt = Date()
            return index
        } catch {
            throw GallerySourceError.decodeFailed
        }
    }
}

// Wire model (only the fields the demo needs). `convertFromSnakeCase` maps
// `date_published` → `datePublished`, `download_count` → `downloadCount`.
private struct PolyHavenAsset: Decodable {
    let name: String?
    let datePublished: Double?
    let downloadCount: Double?
    let authors: [String: String]?
    let categories: [String]?
    let tags: [String]?

    var datePublishedValue: Double { datePublished ?? 0 }
    var downloadCountValue: Double { downloadCount ?? 0 }

    func matches(slug: String, needle: String) -> Bool {
        (name?.lowercased().contains(needle) ?? false)
            || slug.lowercased().contains(needle)
            || (tags ?? []).contains { $0.lowercased().contains(needle) }
            || (categories ?? []).contains { $0.lowercased().contains(needle) }
    }

    func toGalleryModel(slug: String, cdnBaseURL: String) -> GalleryModel {
        let thumbBase = "\(cdnBaseURL)asset_img/thumbs/\(slug).png"
        let fallbackName: String = {
            let spaced = slug.replacingOccurrences(of: "_", with: " ")
            return spaced.prefix(1).uppercased() + spaced.dropFirst()
        }()
        return GalleryModel(
            sourceId: .polyHaven,
            id: slug,
            name: name ?? fallbackName,
            thumbnails: [
                GalleryThumbnail(url: "\(thumbBase)?height=360", width: 640, height: 360),
                GalleryThumbnail(url: "\(thumbBase)?height=720", width: 1280, height: 720),
            ],
            attribution: GalleryAttribution(
                authorName: (authors ?? [:]).keys.sorted().first,
                license: "CC0",
                sourceUrl: "https://polyhaven.com/a/\(slug)"
            ),
            tags: Array(Set((categories ?? []) + (tags ?? []))).sorted()
        )
    }
}

// MARK: - Poly Haven USD file set (#3789)

/// Downloads a Poly Haven model's USD export so RealityKit can open it.
///
/// The `/files/{slug}` record lists, per resolution, a `.usdc` crate and an
/// `include` map of the textures it references by relative path
/// (`@./textures/…@`). Writing the crate at the root of a folder and every
/// include at its relative path under it rebuilds the layout the crate
/// expects, and `ModelNode.load(contentsOf:)` then reads it as it is.
///
/// Measured over the whole catalog on 2026-09-26 (521 models): every model
/// ships USD at 1k, every file is served from `dl.polyhaven.org`, the median
/// 1k set is 3.7 MB, and 20 sets are over 64 MB (trees and cliffs, up to
/// 1.2 GB).
enum PolyHavenUSD {
    /// Largest file set the demo downloads: 64 MB, the ceiling SceneViewSwift
    /// itself puts on a remote model (`ModelNode.load(from:)`). The feeds and
    /// search leave heavier models out instead of listing entries that would
    /// fail after a long download.
    static let maxTotalBytes: Int64 = 64 * 1024 * 1024

    /// The only host the file set may be fetched from.
    static let allowedHosts: Set<String> = ["dl.polyhaven.org"]

    /// The lightest resolution is enough for a phone and keeps the download short.
    static let preferredResolution = "1k"

    /// Cache folder under `Caches/`, one sub-folder per model.
    static let cacheDirectoryName = "polyhaven-usd"

    /// Oldest models are evicted once the folder grows past this.
    static let maxCacheBytes: Int64 = 250 * 1024 * 1024

    /// Written last, so a folder without it is an interrupted download.
    static let completeMarker = ".complete"

    struct File: Equatable, Sendable {
        /// Path under the model folder: the crate's file name, or `textures/…`.
        let relativePath: String
        let url: URL
        /// Size announced by the record. `0` when the record does not say.
        let bytes: Int64
    }

    struct Plan: Equatable, Sendable {
        /// The `.usdc` crate, written at the root of the model folder.
        let root: File
        /// The textures the crate references.
        let includes: [File]

        var files: [File] { [root] + includes }
        var totalBytes: Int64 { files.reduce(0) { $0 + $1.bytes } }
        var fitsBudget: Bool { totalBytes <= PolyHavenUSD.maxTotalBytes }
    }

    enum PlanError: Error, Equatable {
        /// The record has no USD entry.
        case noUSD
        /// A URL or path in the record is outside what the demo accepts.
        case rejected(String)
    }

    /// `https://api.polyhaven.com/files/{slug}`, with the slug as one path segment.
    static func filesURL(base: String, slug: String) -> URL? {
        guard !slug.isEmpty, !slug.contains("/"), let url = URL(string: base) else { return nil }
        return url.appendingPathComponent("files").appendingPathComponent(slug)
    }

    /// Build the download plan from a `/files/{slug}` response body.
    ///
    /// Pure, so the rules are testable from raw JSON: the preferred resolution
    /// (else the smallest), HTTPS on an allowed host only, a crate with a USD
    /// extension, and include paths that stay inside the model folder.
    static func plan(from data: Data) throws -> Plan {
        let record: FilesRecord
        do {
            record = try JSONDecoder().decode(FilesRecord.self, from: data)
        } catch {
            throw PlanError.noUSD
        }
        guard let resolutions = record.usd, !resolutions.isEmpty else { throw PlanError.noUSD }
        let key = resolutions[preferredResolution] != nil
            ? preferredResolution
            : resolutions.keys.min { resolutionRank($0) < resolutionRank($1) }!
        guard let formats = resolutions[key],
              let entry = formats["usd"] ?? formats.values.first else {
            throw PlanError.noUSD
        }

        let rootURL = try acceptedURL(entry.url)
        let rootName = rootURL.lastPathComponent
        guard isSafeRelativePath(rootName),
              ["usdc", "usd", "usda"].contains((rootName as NSString).pathExtension.lowercased()) else {
            throw PlanError.rejected(rootName)
        }
        let root = File(relativePath: rootName, url: rootURL, bytes: entry.size ?? 0)

        let includes = try (entry.include ?? [:])
            .sorted { $0.key < $1.key }
            .map { path, file -> File in
                guard isSafeRelativePath(path), path != rootName else {
                    throw PlanError.rejected(path)
                }
                return File(relativePath: path, url: try acceptedURL(file.url), bytes: file.size ?? 0)
            }
        return Plan(root: root, includes: includes)
    }

    /// `true` for a relative path whose every segment is a plain file or folder
    /// name, so it cannot climb out of, or point outside, the model folder.
    static func isSafeRelativePath(_ path: String) -> Bool {
        guard !path.isEmpty, !path.hasPrefix("/"), !path.contains("\\") else { return false }
        return path.split(separator: "/", omittingEmptySubsequences: false).allSatisfy { segment in
            !segment.isEmpty && segment != "." && segment != ".."
                && segment.allSatisfy { $0.isLetter || $0.isNumber || "-_.".contains($0) }
        }
    }

    /// Download `plan` into the cache and return the crate's local URL.
    ///
    /// A complete earlier download is reused. Files are fetched in parallel
    /// into a staging folder that replaces the model folder only once every
    /// file has arrived, so an interrupted download never leaves a half model
    /// behind. The transfer stops as soon as the bytes received pass
    /// `maxTotalBytes`, whatever the record announced.
    static func download(
        slug: String,
        plan: Plan,
        progress: (@Sendable (Double) -> Void)?
    ) async throws -> URL {
        let fm = FileManager.default
        let root = try cacheRoot()
        let modelDir = root.appendingPathComponent(GalleryCache.sanitize(slug), isDirectory: true)
        let crate = modelDir.appendingPathComponent(plan.root.relativePath)
        if fm.fileExists(atPath: modelDir.appendingPathComponent(completeMarker).path),
           fm.fileExists(atPath: crate.path) {
            try? fm.setAttributes([.modificationDate: Date()], ofItemAtPath: modelDir.path)
            progress?(1)
            return crate
        }

        let staging = root.appendingPathComponent(".staging-\(UUID().uuidString)", isDirectory: true)
        try fm.createDirectory(at: staging, withIntermediateDirectories: true)
        let transfer = CappedTransfer(
            expectedBytes: max(plan.totalBytes, 1),
            maxBytes: maxTotalBytes,
            onProgress: progress
        )
        do {
            try await withThrowingTaskGroup(of: Void.self) { group in
                for file in plan.files {
                    group.addTask {
                        try await fetch(file, into: staging, transfer: transfer)
                    }
                }
                try await group.waitForAll()
            }
            repairMaterials(
                inCrateAt: staging.appendingPathComponent(plan.root.relativePath),
                textures: plan.includes.map(\.relativePath)
            )
            fm.createFile(atPath: staging.appendingPathComponent(completeMarker).path, contents: Data())
            if fm.fileExists(atPath: modelDir.path) {
                try fm.removeItem(at: modelDir)
            }
            try fm.moveItem(at: staging, to: modelDir)
        } catch {
            try? fm.removeItem(at: staging)
            if transfer.didExceed {
                throw GallerySourceError.responseTooLarge(cap: Int(maxTotalBytes))
            }
            throw error
        }
        progress?(1)
        pruneCache(keeping: modelDir)
        return crate
    }

    // MARK: Material repair

    /// As published, a Poly Haven crate opens in RealityKit untextured, for
    /// two reasons that both live in the crate's token table:
    ///
    /// - Every material carries two networks: `UsdPreviewSurface` on
    ///   `outputs:surface`, and Blender's MaterialX graph on
    ///   `outputs:mtlx:surface`. RealityKit picks the MaterialX one and cannot
    ///   read it, so the model came out flat black. Renaming that output to a
    ///   render context nobody knows makes RealityKit use the preview surface.
    /// - The newer exports (crate 0.8.0, every "Recently Added" model on
    ///   2026-09-26) point at their textures by an absolute path on Poly
    ///   Haven's build machine (`/mnt/prod/…/textures/x.jpg`). Pointing those at
    ///   `./textures/x.jpg`, where `download` writes them, lets them resolve.
    ///
    /// Checked on the simulator on 2026-09-26: `Camera_01` (crate 0.9.0) and
    /// `pastic_torch_6v` (crate 0.8.0) render with every texture afterwards.
    static let materialXSurfacePrefix = "outputs:mtlx:"
    static let disabledSurfacePrefix = "outputs:mtlx_off:"

    enum CrateError: Error, Equatable {
        /// The file is not a crate layout this code knows how to rewrite.
        case unsupported(String)
    }

    /// Bytes to append to a crate, and the offset its header must then point
    /// at: the rewritten token table followed by a new table of contents.
    struct CratePatch: Equatable {
        let appended: [UInt8]
        let tocOffset: Int
    }

    /// Rewrite the crate at `url` so RealityKit binds its textures, which sit
    /// at `textures` (paths relative to the crate). Leaves the file as it is
    /// when there is nothing to fix or its layout is not understood: the model
    /// then still opens, untextured. The header moves last, so a write cut
    /// short leaves the original crate readable.
    static func repairMaterials(inCrateAt url: URL, textures: [String]) {
        guard let crate = try? Data(contentsOf: url, options: .alwaysMapped),
              let patch = try? materialPatch(forCrate: crate, textures: textures),
              let handle = try? FileHandle(forUpdating: url) else { return }
        defer { try? handle.close() }
        do {
            try handle.seekToEnd()
            try handle.write(contentsOf: patch.appended)
            try handle.seek(toOffset: 16)
            try handle.write(contentsOf: littleEndian(patch.tocOffset))
        } catch {
            return
        }
    }

    /// The patch that renames every `outputs:mtlx:…` token and points every
    /// absolute texture path at its downloaded copy, or `nil` when the crate
    /// needs neither.
    ///
    /// A crate (`.usdc`) stores property names and asset paths in one token
    /// table, so renaming a token renames it everywhere it is used. The table
    /// is LZ4-compressed, so the rewritten one goes at the end of the file with
    /// a new table of contents; the old sections stay, unreferenced.
    static func materialPatch(forCrate crate: Data, textures: [String]) throws -> CratePatch? {
        let layout = try CrateLayout(crate)
        var tokens = layout.tokens
        var renamed = false
        for index in tokens.indices {
            if let fixed = repairedToken(tokens[index], textures: textures) {
                tokens[index] = fixed
                renamed = true
            }
        }
        guard renamed else { return nil }

        let raw: [UInt8] = tokens.flatMap { $0 + [UInt8(0)] }
        // `TfFastCompression`: a leading 0 announces a single LZ4 block.
        let block: [UInt8] = [0] + lz4LiteralBlock(raw)
        var appended = littleEndian(tokens.count)
        appended += littleEndian(raw.count)
        appended += littleEndian(block.count)
        appended += block
        var sections = layout.sections
        sections[layout.tokensIndex].start = crate.count
        sections[layout.tokensIndex].size = appended.count
        let tocOffset = crate.count + appended.count
        appended += littleEndian(sections.count)
        for section in sections {
            appended += section.name + littleEndian(section.start) + littleEndian(section.size)
        }
        return CratePatch(appended: appended, tocOffset: tocOffset)
    }

    /// The replacement for one token, or `nil` to keep it.
    static func repairedToken(_ token: [UInt8], textures: [String]) -> [UInt8]? {
        let materialX = Array(materialXSurfacePrefix.utf8)
        if token.starts(with: materialX) {
            return Array(disabledSurfacePrefix.utf8) + token.dropFirst(materialX.count)
        }
        guard token.first == UInt8(ascii: "/") else { return nil }
        let path = String(decoding: token, as: UTF8.self)
        guard let texture = textures.first(where: { path.hasSuffix("/" + $0) }) else { return nil }
        return Array("./\(texture)".utf8)
    }

    /// Where a crate keeps its sections, and its decoded token table.
    struct CrateLayout {
        var sections: [(name: [UInt8], start: Int, size: Int)] = []
        var tokensIndex = 0
        var tokens: [[UInt8]] = []

        init(_ crate: Data) throws {
            let bytes = { (range: Range<Int>) throws -> [UInt8] in
                guard range.lowerBound >= 0, range.upperBound <= crate.count else {
                    throw CrateError.unsupported("read past end")
                }
                return [UInt8](crate[(crate.startIndex + range.lowerBound)..<(crate.startIndex + range.upperBound)])
            }
            let header = try bytes(0..<24)
            guard header.starts(with: Array("PXR-USDC".utf8)) else {
                throw CrateError.unsupported("not a USD crate")
            }
            // Token tables are compressed from crate version 0.4.0 on.
            guard (Int(header[8]), Int(header[9])) >= (0, 4) else {
                throw CrateError.unsupported("crate version \(header[8]).\(header[9])")
            }
            let tocOffset = PolyHavenUSD.int(header, at: 16)
            let count = PolyHavenUSD.int(try bytes(tocOffset..<tocOffset + 8), at: 0)
            guard count > 0, count < 64 else { throw CrateError.unsupported("table of contents") }
            let toc = try bytes(tocOffset + 8..<tocOffset + 8 + count * 32)
            for index in 0..<count {
                let entry = index * 32
                sections.append((
                    Array(toc[entry..<entry + 16]),
                    PolyHavenUSD.int(toc, at: entry + 16),
                    PolyHavenUSD.int(toc, at: entry + 24)
                ))
            }
            let tokensName = Array("TOKENS".utf8) + [UInt8](repeating: 0, count: 10)
            guard let found = sections.firstIndex(where: { $0.name == tokensName }) else {
                throw CrateError.unsupported("no token table")
            }
            tokensIndex = found
            let start = sections[found].start
            let sizes = try bytes(start..<start + 24)
            let tokenCount = PolyHavenUSD.int(sizes, at: 0)
            let rawSize = PolyHavenUSD.int(sizes, at: 8)
            let compressedSize = PolyHavenUSD.int(sizes, at: 16)
            guard compressedSize > 1, rawSize < 64 * 1024 * 1024 else {
                throw CrateError.unsupported("token table size")
            }
            let compressed = try bytes(start + 24..<start + 24 + compressedSize)
            guard compressed[0] == 0 else { throw CrateError.unsupported("chunked token table") }
            let raw = try PolyHavenUSD.lz4Decode(compressed[1...], expectedSize: rawSize)
            var split = raw.split(separator: 0, omittingEmptySubsequences: false).map(Array.init)
            guard split.last?.isEmpty == true else { throw CrateError.unsupported("token table end") }
            split.removeLast()
            guard split.count == tokenCount else { throw CrateError.unsupported("token count") }
            tokens = split
        }
    }

    /// Decode one raw LZ4 block, refusing anything that reads or writes out of
    /// bounds.
    static func lz4Decode(_ source: ArraySlice<UInt8>, expectedSize: Int) throws -> [UInt8] {
        let src = Array(source)
        var out: [UInt8] = []
        out.reserveCapacity(expectedSize)
        var i = 0
        func length(_ base: Int) throws -> Int {
            var total = base
            guard base == 15 else { return total }
            while true {
                guard i < src.count else { throw CrateError.unsupported("LZ4 length") }
                let byte = Int(src[i])
                i += 1
                total += byte
                if byte != 255 { return total }
            }
        }
        while i < src.count {
            let token = Int(src[i])
            i += 1
            let literals = try length(token >> 4)
            guard i + literals <= src.count, out.count + literals <= expectedSize else {
                throw CrateError.unsupported("LZ4 literals")
            }
            out += src[i..<i + literals]
            i += literals
            if i == src.count { break }
            guard i + 2 <= src.count else { throw CrateError.unsupported("LZ4 offset") }
            let offset = Int(src[i]) | Int(src[i + 1]) << 8
            i += 2
            let match = try length(token & 15) + 4
            guard offset > 0, offset <= out.count, out.count + match <= expectedSize else {
                throw CrateError.unsupported("LZ4 match")
            }
            let origin = out.count - offset
            for k in 0..<match { out.append(out[origin + k]) }
        }
        guard out.count == expectedSize else { throw CrateError.unsupported("LZ4 size") }
        return out
    }

    /// An LZ4 block made of one literal run: valid for any LZ4 decoder, and
    /// the token table is a few kilobytes, so compressing it buys nothing.
    static func lz4LiteralBlock(_ bytes: [UInt8]) -> [UInt8] {
        guard bytes.count >= 15 else { return [UInt8(bytes.count << 4)] + bytes }
        var header: [UInt8] = [0xF0]
        var rest = bytes.count - 15
        while rest >= 255 {
            header.append(255)
            rest -= 255
        }
        header.append(UInt8(rest))
        return header + bytes
    }

    /// Little-endian 64-bit value at `offset`; callers bound `bytes` first.
    fileprivate static func int(_ bytes: [UInt8], at offset: Int) -> Int {
        var value: UInt64 = 0
        for k in (0..<8).reversed() { value = value << 8 | UInt64(bytes[offset + k]) }
        return Int(truncatingIfNeeded: value)
    }

    static func littleEndian(_ value: Int) -> [UInt8] {
        (0..<8).map { UInt8(truncatingIfNeeded: UInt64(value) >> (8 * UInt64($0))) }
    }

    // MARK: Internals

    /// Sort key for a resolution label: `1k` < `2k` < `4k`; unknown labels last.
    private static func resolutionRank(_ label: String) -> Int {
        Int(label.lowercased().replacingOccurrences(of: "k", with: "")) ?? .max
    }

    private static func acceptedURL(_ string: String) throws -> URL {
        guard let url = URL(string: string),
              url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased(), allowedHosts.contains(host) else {
            throw PlanError.rejected(string)
        }
        return url
    }

    private static func fetch(_ file: File, into folder: URL, transfer: CappedTransfer) async throws {
        var request = URLRequest(url: file.url)
        // Poly Haven answers 403 to clients that do not name themselves.
        request.setValue("SceneViewDemo/iOS", forHTTPHeaderField: "User-Agent")
        let (temp, response) = try await URLSession.shared.download(for: request, delegate: transfer)
        defer { try? FileManager.default.removeItem(at: temp) }
        guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
            throw GallerySourceError.requestFailed(statusCode: (response as? HTTPURLResponse)?.statusCode ?? 0)
        }
        let destination = folder.appendingPathComponent(file.relativePath)
        try FileManager.default.createDirectory(
            at: destination.deletingLastPathComponent(),
            withIntermediateDirectories: true
        )
        try FileManager.default.moveItem(at: temp, to: destination)
    }

    private static func cacheRoot() throws -> URL {
        let caches = try FileManager.default.url(
            for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )
        let dir = caches.appendingPathComponent(cacheDirectoryName, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Evict the least recently opened models once the cache passes
    /// `maxCacheBytes`. The model just opened is never evicted.
    private static func pruneCache(keeping current: URL) {
        let fm = FileManager.default
        guard let root = try? cacheRoot(),
              let folders = try? fm.contentsOfDirectory(
                at: root,
                includingPropertiesForKeys: [.contentModificationDateKey],
                options: [.skipsHiddenFiles]
              ) else { return }
        func size(of folder: URL) -> Int64 {
            let files = fm.enumerator(at: folder, includingPropertiesForKeys: [.fileSizeKey])
            var total: Int64 = 0
            while let file = files?.nextObject() as? URL {
                total += Int64((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            }
            return total
        }
        var entries = folders.map { folder -> (URL, Int64, Date) in
            let date = (try? folder.resourceValues(forKeys: [.contentModificationDateKey])
                .contentModificationDate) ?? .distantPast
            return (folder, size(of: folder), date)
        }
        var total = entries.reduce(Int64(0)) { $0 + $1.1 }
        entries.sort { $0.2 < $1.2 }
        for (folder, bytes, _) in entries where total > maxCacheBytes {
            guard folder.standardizedFileURL != current.standardizedFileURL else { continue }
            try? fm.removeItem(at: folder)
            total -= bytes
        }
    }

    // Wire model: only the `usd` block of `/files/{slug}`.
    private struct FilesRecord: Decodable {
        let usd: [String: [String: Entry]]?
    }

    private struct Entry: Decodable {
        let url: String
        let size: Int64?
        let include: [String: Entry]?
    }
}

/// Per-task delegate shared by the files of one model download: sums the
/// bytes received across them for the progress bar, and cancels any transfer
/// that pushes the total past the cap. Same idea as SceneViewSwift's
/// `SizeCappedDownload`, which is private to the library.
private final class CappedTransfer: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let expectedBytes: Int64
    private let maxBytes: Int64
    private let onProgress: (@Sendable (Double) -> Void)?
    private let lock = NSLock()
    private var received: [Int: Int64] = [:]
    private var exceeded = false

    init(expectedBytes: Int64, maxBytes: Int64, onProgress: (@Sendable (Double) -> Void)?) {
        self.expectedBytes = expectedBytes
        self.maxBytes = maxBytes
        self.onProgress = onProgress
    }

    var didExceed: Bool {
        lock.lock(); defer { lock.unlock() }
        return exceeded
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didWriteData bytesWritten: Int64,
        totalBytesWritten: Int64,
        totalBytesExpectedToWrite: Int64
    ) {
        lock.lock()
        received[downloadTask.taskIdentifier] = totalBytesWritten
        let total = received.values.reduce(0, +)
        let over = total > maxBytes
        if over { exceeded = true }
        lock.unlock()
        if over {
            downloadTask.cancel()
        } else {
            onProgress?(min(1, Double(total) / Double(expectedBytes)))
        }
    }

    func urlSession(
        _ session: URLSession,
        downloadTask: URLSessionDownloadTask,
        didFinishDownloadingTo location: URL
    ) {
        // Required by the protocol. The async `download(for:delegate:)` hands the
        // file to its caller, so there is nothing to do here.
    }
}
