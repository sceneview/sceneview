import Foundation

// MARK: - Multi-source model abstraction (iOS port of Android #2645 / PR #2685)
//
// The Explore tab is source-agnostic: it browses whichever catalog the user
// picks (Sketchfab | Icosa Gallery | Poly Haven), rendering source-agnostic
// `GalleryModel`s. This mirrors the Android `ModelSource` abstraction merged in
// #2685 (Fixes #2645); keep the two in sync.
//
// **iOS subset boundary (honest degradation).** RealityKit's `Entity(contentsOf:)`
// loads only `.usdz` / `.reality`, so the in-app SceneView render only works for
// sources that serve USDZ (Sketchfab). The Creative-Commons sources (Icosa,
// Poly Haven) are glTF-native, so on Apple platforms they are fully browsable +
// searchable but their in-app 3D render is honestly deferred ("coming soon")
// rather than faked or crashed. `ModelSource.rendersInApp` encodes that boundary
// — it is the one property that has no Android analogue, because Filament renders
// glTF natively there. See #2700.

/// One curated feed of a [ModelSource], in browse-layout display order.
///
/// Not every catalog exposes all three taxonomies — Poly Haven has no editorial
/// "staff picks", for instance — so each source declares the subset it supports
/// via `ModelSource.feedKinds`. The Explore tab renders exactly the feeds the
/// selected source advertises.
enum FeedKind: CaseIterable, Hashable {
    /// Most-popular / trending right now.
    case trending
    /// Editorially curated (Sketchfab Staff Picks, Icosa curated flag).
    case staffPicks
    /// Most recently published.
    case recentlyAdded
}

/// The 3D-model catalogs the Explore tab can browse (#2645 / #2700).
///
/// Sketchfab (Epic Games / Fab) has a measurably degraded search backend and a
/// bounded contractual lifetime; Icosa Gallery and Poly Haven are verified-alive
/// free alternatives with real JSON APIs and no auth.
///
/// - `slug`: stable identifier persisted in `UserDefaults`.
/// - `displayName`: label shown on the source-picker chip.
enum ModelSourceId: String, CaseIterable, Hashable {
    case sketchfab
    case icosa
    case polyHaven

    var slug: String {
        switch self {
        case .sketchfab: return "sketchfab"
        case .icosa: return "icosa"
        case .polyHaven: return "polyhaven"
        }
    }

    var displayName: String {
        switch self {
        case .sketchfab: return "Sketchfab"
        case .icosa: return "Icosa Gallery"
        case .polyHaven: return "Poly Haven"
        }
    }

    /// Resolve a persisted `slug` back to its case, or `nil` when unknown.
    static func fromSlug(_ slug: String?) -> ModelSourceId? {
        guard let slug else { return nil }
        return allCases.first { $0.slug == slug }
    }
}

/// One thumbnail candidate at a specific pixel size.
struct GalleryThumbnail: Hashable {
    let url: String
    let width: Int
    let height: Int
}

/// Attribution + license metadata for a [GalleryModel].
///
/// Every source in scope serves Creative-Commons / CC0 content, so surfacing the
/// author and license is both a courtesy and, for CC-BY assets, a requirement.
///
/// - `authorName`: human-readable creator, or `nil` when the API omits it.
/// - `license`: short human-readable license, e.g. `"CC BY 4.0"` / `"CC0"`.
/// - `sourceUrl`: the model's page on the origin catalog. Recorded for credit
///   only — the demo never opens an external viewer (it always renders through
///   SceneView), so this is metadata, not a navigation target.
struct GalleryAttribution: Hashable {
    var authorName: String? = nil
    var license: String? = nil
    var sourceUrl: String? = nil
}

/// A source-agnostic model entry rendered by the Explore tab.
///
/// This is the single shape the carousels, the search results, the card and the
/// viewer speak, so adding a new `ModelSource` never touches the UI. Each source
/// maps its own wire format onto this type.
///
/// - `id`: source-specific identifier (Sketchfab uid, Icosa assetId, Poly Haven
///   slug) — unique only *within* `sourceId`. Use `cardKey` for SwiftUI list
///   keys, which are unique across sources.
/// - `downloadable`: `false` when the origin catalog exposes the model but not a
///   downloadable format the demo can render.
struct GalleryModel: Identifiable, Hashable {
    let sourceId: ModelSourceId
    let id: String
    let name: String
    var thumbnails: [GalleryThumbnail] = []
    var attribution: GalleryAttribution = GalleryAttribution()
    var tags: [String] = []
    var faceCount: Int = 0
    var animationCount: Int = 0
    var downloadable: Bool = true

    /// True when the model carries one or more skeletal animations.
    var isAnimated: Bool { animationCount > 0 }

    /// Stable, cross-source-unique key for SwiftUI `ForEach(_, id:)`.
    ///
    /// A raw `id` is only unique within one source, and the same model can even
    /// appear in two feeds of the same source — the `sourceId` prefix guarantees
    /// no key collision when the picker switches catalogs or a model surfaces in
    /// two feeds.
    var cardKey: String { "\(sourceId.slug):\(id)" }

    // Hashable/Equatable on the stable cross-source key only.
    func hash(into hasher: inout Hasher) { hasher.combine(cardKey) }
    static func == (lhs: GalleryModel, rhs: GalleryModel) -> Bool { lhs.cardKey == rhs.cardKey }
}

extension GalleryModel {
    /// Pick a thumbnail close to the card's render size, falling back to the
    /// largest available (then the first).
    func preferredThumbnailURL(minWidth: Int = 320, maxWidth: Int = 640) -> URL? {
        let sweetSpot = thumbnails.first { $0.width >= minWidth && $0.width <= maxWidth }
        let chosen = sweetSpot ?? thumbnails.max(by: { $0.width < $1.width }) ?? thumbnails.first
        return chosen.flatMap { URL(string: $0.url) }
    }

    /// First tag in Title Case, or a generic fallback.
    var primaryTagDisplay: String {
        tags.first.map { $0.prefix(1).uppercased() + $0.dropFirst() } ?? "3D Model"
    }

    /// Compact human-readable face count: `1.2k`, `3.4M`, or the raw number.
    var formattedFaceCount: String {
        if faceCount >= 1_000_000 { return String(format: "%.1fM", Double(faceCount) / 1_000_000) }
        if faceCount >= 1_000 { return String(format: "%.1fk", Double(faceCount) / 1_000) }
        return "\(faceCount)"
    }

    /// `"by Ada · CC BY 4.0 · via Icosa Gallery"`, omitting the parts a source
    /// can't fill.
    var attributionLine: String {
        var parts: [String] = []
        if let author = attribution.authorName, !author.isEmpty { parts.append("by \(author)") }
        if let license = attribution.license, !license.isEmpty { parts.append(license) }
        parts.append("via \(sourceId.displayName)")
        return parts.joined(separator: " · ")
    }
}

/// A 3D-model catalog the Explore tab can browse, search, and (when the platform
/// supports the served format) stream from.
///
/// Implementations wrap a network client and map the catalog's wire format onto
/// the source-agnostic `GalleryModel`. The tab never references a concrete
/// source: it iterates `feedKinds`, calls `feed` / `search`, renders the
/// resulting `GalleryModel`s, and — for the render-capable sources — streams the
/// chosen one through `download`.
///
/// **Resilience contract.** A single degraded source must never blank the tab.
/// `feed` and `search` may throw (network blip, rate limit, decode error); the
/// caller catches per-feed so surviving feeds — and the always-present source
/// picker — keep the tab usable and let the user switch catalogs.
protocol ModelSource: Sendable {
    /// Which catalog this source represents (drives the picker chip + persistence).
    var id: ModelSourceId { get }

    /// `true` when the source can be used in the current build. Sketchfab needs
    /// an API key; the CC0 / CC-BY sources are always available. Unavailable
    /// sources are hidden from the picker.
    var isAvailable: Bool { get }

    /// Feeds this source exposes, in display order.
    var feedKinds: [FeedKind] { get }

    /// `true` when the "Animated" filter chip is meaningful for this source. Only
    /// Sketchfab's feed endpoints accept an `animated` flag.
    var supportsAnimatedFilter: Bool { get }

    /// `true` when a model from this source can be rendered *in-app* through
    /// SceneView (RealityKit) on Apple platforms.
    ///
    /// This is the iOS subset boundary: RealityKit loads only USDZ, so Sketchfab
    /// (which serves USDZ) renders, while the glTF-native CC sources are browse +
    /// search only and surface an honest "3D preview coming soon" state in the
    /// viewer. There is no Android analogue — Filament renders glTF natively.
    var rendersInApp: Bool { get }

    /// Load one curated `kind` feed.
    func feed(kind: FeedKind, animatedOnly: Bool, limit: Int) async throws -> [GalleryModel]

    /// Free-text search over the catalog. Same failure contract as `feed`.
    func search(query: String, limit: Int) async throws -> [GalleryModel]

    /// Stream `model`'s preferred format to the on-disk cache and return the
    /// local file URL, ready to hand to `ModelNode.load(contentsOf:)`.
    ///
    /// Only meaningful when `rendersInApp` is `true`; the CC sources throw
    /// `GallerySourceError.renderNotSupported` because RealityKit cannot load
    /// their glTF output.
    func download(model: GalleryModel, progress: (@Sendable (Double) -> Void)?) async throws -> URL
}

extension ModelSource {
    var supportsAnimatedFilter: Bool { false }
    var rendersInApp: Bool { false }
}

// MARK: - Shared source errors

enum GallerySourceError: Error, LocalizedError {
    case requestFailed(statusCode: Int)
    case responseTooLarge(cap: Int)
    case decodeFailed
    case noRenderableFormat
    /// Thrown by the glTF-native CC sources: RealityKit cannot render their
    /// output on Apple platforms, so the in-app render is honestly deferred.
    case renderNotSupported(sourceName: String)

    var errorDescription: String? {
        switch self {
        case .requestFailed(let code):
            return "Request failed with HTTP \(code)."
        case .responseTooLarge(let cap):
            return "Response body exceeds the \(cap)-byte cap."
        case .decodeFailed:
            return "The catalog returned an unexpected response."
        case .noRenderableFormat:
            return "No renderable format available for this model."
        case .renderNotSupported(let sourceName):
            return "3D preview for \(sourceName) models is coming soon on iOS."
        }
    }
}

// MARK: - Bounded network reads (mirrors Android `readBoundedBody`, #2645)

/// Upper bound on the JSON body a keyless source will accept from an index /
/// feed / detail endpoint (32 MB). The catalogs return compact JSON; a body this
/// large means a hostile or misbehaving endpoint, so we refuse it instead of
/// buffering an unbounded response into memory (mirrors the Android OOM guard).
let maxSourceJSONBytes = 32 * 1024 * 1024

/// GET `url` and return its body, refusing bodies larger than `maxBytes`.
///
/// Fast-fails on an advertised over-cap `Content-Length` before reading, and
/// refuses a body that materialises over the cap — the pragmatic Apple-platform
/// equivalent of okio's bounded `request()` used by the Android sources.
func fetchBoundedData(
    from url: URL,
    session: URLSession = .shared,
    maxBytes: Int = maxSourceJSONBytes
) async throws -> Data {
    var request = URLRequest(url: url)
    request.setValue("application/json", forHTTPHeaderField: "Accept")
    request.setValue("SceneViewDemo/iOS", forHTTPHeaderField: "User-Agent")

    let (bytes, response) = try await session.bytes(for: request)
    guard let http = response as? HTTPURLResponse else {
        throw GallerySourceError.decodeFailed
    }
    guard (200..<300).contains(http.statusCode) else {
        throw GallerySourceError.requestFailed(statusCode: http.statusCode)
    }
    // Reject an honestly-advertised oversize body up front, then enforce the cap
    // DURING transfer: the body is streamed and abandoned the moment it exceeds
    // `maxBytes`, so a chunked / no-Content-Length response can never materialise
    // an unbounded in-memory buffer. This matches the okio bounded read the
    // Android port uses (#2685) — not just a post-buffer size check.
    if http.expectedContentLength > Int64(maxBytes) {
        throw GallerySourceError.responseTooLarge(cap: maxBytes)
    }
    var data = Data()
    if http.expectedContentLength > 0 {
        data.reserveCapacity(Int(http.expectedContentLength))
    }
    for try await byte in bytes {
        data.append(byte)
        if data.count > maxBytes {
            throw GallerySourceError.responseTooLarge(cap: maxBytes)
        }
    }
    return data
}

// MARK: - Path-segment sanitization (mirrors Android `NetworkModelDownloader.sanitize`)

enum GalleryCache {
    /// Flatten an (untrusted, server-supplied) id into a single safe path
    /// segment before it becomes a cache filename / directory.
    ///
    /// Carries forward the Android hardening from #2645: a raw remote asset id
    /// must never become a traversal (`../…`) or absolute path when mapped onto
    /// an on-disk cache file.
    static func sanitize(_ id: String) -> String {
        let cleaned = id.map { ch -> Character in
            (ch.isLetter || ch.isNumber || ch == "-" || ch == "_") ? ch : "_"
        }
        let result = String(cleaned)
        return result.isEmpty ? "asset" : result
    }
}

/// Map an Icosa / Poly-style license enum onto a short human-readable label.
/// Shared by the CC sources, which use the same CC vocabulary.
func galleryLicenseDisplayName(_ raw: String?) -> String? {
    guard let raw, !raw.isEmpty else { return nil }
    switch raw.uppercased() {
    case "CREATIVE_COMMONS_BY", "CC-BY", "CC_BY": return "CC BY 4.0"
    case "CREATIVE_COMMONS_BY_ND", "CC-BY-ND": return "CC BY-ND 4.0"
    case "CREATIVE_COMMONS_BY_SA", "CC-BY-SA": return "CC BY-SA 4.0"
    case "CREATIVE_COMMONS_0", "CC0", "CC_0": return "CC0"
    case "ALL_RIGHTS_RESERVED", "RESERVED": return "All rights reserved"
    default:
        let spaced = raw.replacingOccurrences(of: "_", with: " ").lowercased()
        return spaced.prefix(1).uppercased() + spaced.dropFirst()
    }
}
