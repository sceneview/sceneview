import Foundation
import simd

/// A captured space, flattened into what an export needs: the coloured point cloud, the
/// camera's path and photos, the planes (with their photo textures when the capture has
/// them) and the placed models. Every exporter (`.ply`, `.glb`, `.usdz`, `.rrd`) reads this
/// one value, so the bundled showcase and a capture recorded on the phone export the same
/// way, and the same way Android exports them.
///
/// Coordinates are the session's world space: metres, Y up, right-handed — ARCore's and
/// ARKit's world frame, and glTF's and USD's default (`upAxis = "Y"`, `metersPerUnit = 1`).
/// Entity names follow the Rerun bridge's wire format (`world/camera`, `world/points`,
/// `world/planes/<id>`, `world/anchors/<id>`), so an export and a live stream describe the
/// same entities.
struct RerunExportScene: Sendable {
    /// A pinhole lens, in pixels of the recorded frames (portrait: `width < height`).
    struct Lens: Sendable, Equatable {
        var width: Int
        var height: Int
        var fx: Float
        var fy: Float
        var cx: Float
        var cy: Float
    }

    /// One camera pose on the timeline. Cameras look down their local -Z, local +Y is the
    /// photo's up (the wire format's convention on both platforms).
    struct CameraSample: Sendable, Equatable {
        /// Seconds since the first event of the session.
        var time: Double
        var position: SIMD3<Float>
        var orientation: simd_quatf
    }

    /// One recorded photo on the timeline: `imagePath` keys ``images``.
    struct Keyframe: Sendable, Equatable {
        var time: Double
        var imagePath: String
        /// The camera pose the photo was taken from.
        var pose: CameraSample
    }

    /// Map indices seen by the camera at one instant, in session seconds.
    struct PointObservation: Sendable, Equatable {
        var time: Double
        var points: [Int]
    }

    /// A plane's photo, laid on it: `origin` is the world position of texel (0, 0), `u` and
    /// `v` the texture's two full edges in world space. The texture coordinate of a world
    /// point `p` is `(dot(p - origin, u) / dot(u, u), dot(p - origin, v) / dot(v, v))`,
    /// with `v = 0` the image's top row.
    struct PlaneTexture: Sendable, Equatable {
        /// An encoded image (WebP, JPEG or PNG) — anything ImageIO decodes.
        var imageData: Data
        var origin: SIMD3<Float>
        var u: SIMD3<Float>
        var v: SIMD3<Float>
    }

    struct Plane: Sendable, Equatable {
        var id: Int
        /// The wire format's kind: `horizontal_upward`, `horizontal_downward`, `vertical`
        /// or `unknown`.
        var kind: String
        /// The boundary, world space, convex, in order (a fan from its centroid fills it).
        var polygon: [SIMD3<Float>]
        var texture: PlaneTexture?
    }

    struct Anchor: Sendable, Equatable {
        var id: Int
        var position: SIMD3<Float>
        var orientation: simd_quatf
        /// The model the demo placed on it, as a bundled resource name without extension
        /// (`"shiba"`), `nil` for a bare anchor.
        var modelName: String?
    }

    /// Human-readable name of the capture, used for file names and scene titles.
    var title: String
    var lens: Lens?
    /// Every map point, world space.
    var points: [SIMD3<Float>]
    /// One sRGB colour per point, same count as ``points``.
    var pointColors: [SIMD3<UInt8>]
    /// The camera's path, oldest first.
    var cameraPath: [CameraSample]
    var keyframes: [Keyframe]
    /// Encoded photos by path (`frames/012.webp`): every recorded image.
    var images: [String: Data]
    var planes: [Plane]
    var anchors: [Anchor]
    /// Every recorded photo; older callers can fall back to `keyframes`.
    var photos: [Keyframe] = []
    var pointObservations: [PointObservation] = []
}
