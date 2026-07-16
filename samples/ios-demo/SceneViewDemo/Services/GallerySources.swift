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
/// Sketchfab serves USDZ, which RealityKit can load, so this is the one source
/// whose models render in-app on Apple platforms (`rendersInApp = true`).
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
/// **iOS subset:** Icosa is glTF-native, and RealityKit cannot load glTF, so this
/// source is fully browsable + searchable but its in-app 3D render is honestly
/// deferred (`rendersInApp` stays `false`; `download` throws
/// `renderNotSupported`). The Android demo renders these via Filament; keep the
/// two feeds in sync when Apple-side glTF support lands. See #2700.
struct IcosaGallerySource: ModelSource {
    let id: ModelSourceId = .icosa
    let isAvailable = true
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
/// **iOS subset:** Poly Haven ships multi-file glTF, which RealityKit cannot
/// load, so — like Icosa — it is browse + search only, with an honest deferred
/// render (`rendersInApp` stays `false`). See #2700.
actor PolyHavenSource: ModelSource {
    nonisolated let id: ModelSourceId = .polyHaven
    nonisolated let isAvailable = true
    nonisolated let feedKinds: [FeedKind] = [.trending, .recentlyAdded]

    private let baseURL = "https://api.polyhaven.com/"
    private let cdnBaseURL = "https://cdn.polyhaven.com/"
    /// In-memory TTL for the models index (5 minutes).
    private let indexTTL: TimeInterval = 5 * 60

    private var cachedIndex: [String: PolyHavenAsset]?
    private var cachedAt: Date = .distantPast

    func feed(kind: FeedKind, animatedOnly: Bool, limit: Int) async throws -> [GalleryModel] {
        let index = try await modelsIndex()
        let sorted: [(key: String, value: PolyHavenAsset)]
        switch kind {
        case .trending:
            sorted = index.sorted { $0.value.downloadCountValue > $1.value.downloadCountValue }
        case .recentlyAdded:
            sorted = index.sorted { $0.value.datePublishedValue > $1.value.datePublishedValue }
        case .staffPicks:
            sorted = Array(index) // unreachable: not in feedKinds
        }
        return sorted.prefix(limit).map { $0.value.toGalleryModel(slug: $0.key, cdnBaseURL: cdnBaseURL) }
    }

    func search(query: String, limit: Int) async throws -> [GalleryModel] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !needle.isEmpty else { return [] }
        let index = try await modelsIndex()
        return index
            .filter { $0.value.matches(slug: $0.key, needle: needle) }
            .sorted { $0.value.downloadCountValue > $1.value.downloadCountValue }
            .prefix(limit)
            .map { $0.value.toGalleryModel(slug: $0.key, cdnBaseURL: cdnBaseURL) }
    }

    nonisolated func download(model: GalleryModel, progress: (@Sendable (Double) -> Void)?) async throws -> URL {
        throw GallerySourceError.renderNotSupported(sourceName: id.displayName)
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
