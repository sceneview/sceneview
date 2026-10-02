import Foundation
import simd

/// One environment depth map in metres-as-millimetres, with everything a
/// consumer needs to place it in the world: the camera pose and intrinsics
/// **at the moment the depth was captured**, never the current ones.
///
/// The type is the Swift side of the shared depth contract; Android's
/// `ArDepthFrame` carries the same fields. Two producers fill it:
///
/// - ``Source-swift.enum/native`` — ARKit `sceneDepth` (LiDAR), 256×192.
/// - ``Source-swift.enum/ml`` — a monocular estimator (Depth Anything V2
///   Small through the `SceneViewDepthML` product), scaled to metres against
///   ARKit's feature points and planes. Lower resolution, up to 5 Hz, and 100 to
///   300 ms behind the camera: always use ``cameraTransform`` to place it.
///   The fit figures (anchors, inliers, error) live in
///   ``DepthSourceState/running(_:)``, as on Android.
///
/// The map is stored in the camera image's own orientation (landscape, the
/// sensor's), row-major, top-left origin. A value of `0` means "no depth here".
///
/// ```swift
/// ARSceneView(configuration: ARSessionConfiguration(planeDetection: .both))
///     .depthSource(.auto(ml: estimator))
///     .onDepthFrame { frame in
///         guard let frame else { return }               // nil = no depth right now
///         let centre = frame.depth(atX: frame.width / 2, y: frame.height / 2)
///         print(frame.source, centre ?? .nan)           // .ml 1.42
///     }
/// ```
public struct ARDepthFrame: Sendable {
    /// Where the depth comes from. Same cases on Android:
    /// `ArDepthFrame.Source.Native` / `.Ml`.
    public enum Source: String, Sendable, Hashable {
        /// The device's own depth sensor (ARKit `sceneDepth`, LiDAR). On
        /// Android, ARCore's Depth API.
        case native
        /// A monocular ML estimate scaled to metres.
        case ml
    }

    /// Capture time, on the `ARFrame.timestamp` clock (seconds).
    public let timestamp: TimeInterval
    public let width: Int
    public let height: Int
    /// `width × height` depths in millimetres, row-major; `0` = invalid.
    public let millimetres: [UInt16]
    /// Optional per-pixel confidence, `0` (none) to `255` (high), same layout.
    public let confidence: [UInt8]?
    /// Pinhole intrinsics **for this map's resolution** (pixels, top-left
    /// origin). Never assume the camera image's: they differ by the scale.
    public let intrinsics: simd_float3x3
    /// Camera-to-world transform at ``timestamp`` (ARKit camera convention:
    /// +x right, +y up, −z forward in the sensor's landscape orientation).
    public let cameraTransform: simd_float4x4
    public let source: Source

    public init(
        timestamp: TimeInterval,
        width: Int,
        height: Int,
        millimetres: [UInt16],
        confidence: [UInt8]? = nil,
        intrinsics: simd_float3x3,
        cameraTransform: simd_float4x4,
        source: Source
    ) {
        precondition(millimetres.count == width * height, "millimetres must hold width × height values")
        precondition(confidence == nil || confidence!.count == width * height,
                     "confidence must hold width × height values")
        self.timestamp = timestamp
        self.width = width
        self.height = height
        self.millimetres = millimetres
        self.confidence = confidence
        self.intrinsics = intrinsics
        self.cameraTransform = cameraTransform
        self.source = source
    }

    /// Depth along the optical axis in metres at pixel (`x`, `y`), or `nil`
    /// when the pixel is out of bounds or carries no depth.
    public func depth(atX x: Int, y: Int) -> Float? {
        guard x >= 0, y >= 0, x < width, y < height else { return nil }
        let mm = millimetres[y * width + x]
        return mm == 0 ? nil : Float(mm) / 1000
    }

    /// The world-space point seen at pixel (`x`, `y`), unprojected with this
    /// frame's own intrinsics and capture pose. `nil` where there is no depth.
    public func worldPoint(atX x: Int, y: Int) -> SIMD3<Float>? {
        guard let z = depth(atX: x, y: y) else { return nil }
        let fx = intrinsics[0][0], fy = intrinsics[1][1]
        let cx = intrinsics[2][0], cy = intrinsics[2][1]
        // Pixel rows grow downwards; camera +y points up.
        let camera = SIMD4<Float>(
            (Float(x) + 0.5 - cx) / fx * z,
            -(Float(y) + 0.5 - cy) / fy * z,
            -z,
            1
        )
        let world = cameraTransform * camera
        return SIMD3(world.x, world.y, world.z)
    }
}
