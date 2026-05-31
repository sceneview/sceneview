import Foundation

/// A Sketchfab model entry returned by `/v3/search` and `/v3/models`.
struct SketchfabModel: Codable, Identifiable, Hashable {
    let uid: String
    let name: String
    let description: String?
    let thumbnails: SketchfabThumbnails
    let viewerUrl: String
    let downloadable: Bool
    let tags: [SketchfabTag]?
    /// Number of GPU triangles. Useful for surfacing "12k polys" badges.
    let faceCount: Int
    /// Number of skeletal animations. `> 0` means we can show an "Animated" pill.
    let animationCount: Int
    /// Number of likes / views on the Sketchfab page.
    let likeCount: Int
    let viewCount: Int

    var id: String { uid }

    enum CodingKeys: String, CodingKey {
        case uid, name, description, thumbnails, tags
        case viewerUrl
        case downloadable = "isDownloadable"
        case faceCount, animationCount, likeCount, viewCount
    }
}

/// Wrapper around the `images` array returned by Sketchfab for each model.
struct SketchfabThumbnails: Codable, Hashable {
    let images: [SketchfabThumbnail]
}

/// A single thumbnail at a specific resolution.
struct SketchfabThumbnail: Codable, Hashable {
    let url: String
    let width: Int
    let height: Int
}

/// A tag attached to a model. Sketchfab returns more fields (slug, uri, …) but
/// only `name` is needed for filtering/display in the demo app.
struct SketchfabTag: Codable, Hashable {
    let name: String
}

/// Paginated search/list response.
struct SketchfabSearchResponse: Codable {
    let results: [SketchfabModel]
    let next: String?
    let previous: String?
}

/// Response of `GET /v3/models/{uid}/download`.
///
/// Sketchfab returns up to three format entries (`gltf`, `glb`, `usdz`); all
/// are optional because availability depends on the model.
struct SketchfabDownloadResponse: Codable {
    let gltf: SketchfabDownloadUrl?
    let glb: SketchfabDownloadUrl?
    let usdz: SketchfabDownloadUrl?

    /// The only format this RealityKit-backed demo can render.
    /// `Entity(contentsOf:)` loads **only** `.usdz`/`.reality` — it cannot
    /// parse GLB or glTF — so on Apple platforms we must request USDZ. (The
    /// Android/Filament demo is the mirror image: it prefers GLB, which
    /// Filament loads natively.) `glb`/`gltf` are decoded for completeness but
    /// are not loadable here; a model without a `usdz` entry can't be shown.
    var appleFormat: SketchfabDownloadUrl? {
        usdz
    }
}

/// A signed download URL with its size and expiration timestamp (epoch seconds).
struct SketchfabDownloadUrl: Codable, Hashable {
    let url: String
    let size: Int
    let expires: Int
}
