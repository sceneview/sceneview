import Foundation
import simd

// MARK: - AR tap-to-place (#3780): pure logic
//
// Kept free of ARKit, RealityKit and Flutter so the math can be checked off-device.
// The Android bridge holds the same contract in `ARTapToPlace.kt`, and its JVM test
// (`ARTapToPlaceTest`) pins the wire format that both sides share with Dart.

/// How far a pinch can resize a placed model, relative to its placed size. The
/// same range as Android's `PLACED_SCALE_RANGE`, and the one the Dart
/// `placeModel` doc quotes.
let flutterPlacedScaleRange: ClosedRange<Float> = 0.25...4

/// Orientation of a plane hit, in the ARCore `HitResult.hitPose` convention
/// that the Dart `ARHitResult` documents for both platforms:
/// - +Y is the plane normal;
/// - +Z lies in the plane and points toward the camera;
/// - +X is `Y × Z`.
///
/// ARKit's raycast result has no such convention (its orientation follows the
/// plane anchor), so the iOS bridge derives it here to keep the Dart API
/// identical across platforms. A model placed with this rotation faces the user.
///
/// When the camera is straight above the hit, the direction to the camera has
/// no in-plane component. Any heading is then valid, so a fixed world axis
/// projected onto the plane is used instead of a NaN.
func flutterPlaneHitRotation(
    normal: SIMD3<Float>,
    hitPosition: SIMD3<Float>,
    cameraPosition: SIMD3<Float>
) -> simd_quatf {
    let normalLength = simd_length(normal)
    let y = normalLength > 1e-6 ? normal / normalLength : SIMD3<Float>(0, 1, 0)

    func inPlane(_ v: SIMD3<Float>) -> SIMD3<Float> { v - simd_dot(v, y) * y }

    var z = inPlane(cameraPosition - hitPosition)
    if simd_length(z) < 1e-4 {
        let reference: SIMD3<Float> = abs(y.z) < 0.9 ? SIMD3(0, 0, 1) : SIMD3(1, 0, 0)
        z = inPlane(reference)
    }
    z = simd_normalize(z)
    let x = simd_cross(y, z)
    return simd_normalize(simd_quatf(simd_float3x3(columns: (x, y, z))))
}

/// World transform of a hit pose: `rotation`, then a translation to `position`.
func flutterHitTransform(position: SIMD3<Float>, rotation: simd_quatf) -> simd_float4x4 {
    var transform = simd_float4x4(rotation)
    transform.columns.3 = SIMD4<Float>(position, 1)
    return transform
}

/// Encodes a plane hit as the `onPlaneTap` payload that the Dart
/// `ARHitResult.fromMap` decodes. Uses the same keys as Android's `planeHitMap`.
func flutterPlaneHitMap(
    id: String,
    position: SIMD3<Float>,
    rotation: simd_quatf,
    planeType: String,
    distance: Float
) -> [String: Any] {
    [
        "id": id,
        "x": Double(position.x),
        "y": Double(position.y),
        "z": Double(position.z),
        "qx": Double(rotation.imag.x),
        "qy": Double(rotation.imag.y),
        "qz": Double(rotation.imag.z),
        "qw": Double(rotation.real),
        "planeType": planeType,
        "distance": Double(distance),
    ]
}

/// A decoded `placeModel` call. It mirrors Android's `PlaceModelRequest`.
struct FlutterPlaceRequest {
    let hitId: String?
    let position: SIMD3<Float>
    /// Unit quaternion. A zero or non-finite input decodes to identity.
    let rotation: simd_quatf
    let modelPath: String
    /// Size of the model's largest dimension, in metres (`ModelNode.scale`).
    let size: Float
    let editable: Bool
    let draggable: Bool
    let rotatable: Bool
    let scalable: Bool

    var canDrag: Bool { editable && draggable }
    var canRotate: Bool { editable && rotatable }
    var canScale: Bool { editable && scalable }

    /// Decodes `{hit: ARHitResult.toMap(), model: ModelNode.toMap(), editable,
    /// draggable, rotatable, scalable}`. Returns nil when the hit or the model
    /// path is missing.
    init?(arguments: Any?) {
        guard let args = arguments as? [String: Any],
              let hit = args["hit"] as? [String: Any],
              let model = args["model"] as? [String: Any],
              let path = model["modelPath"] as? String,
              !path.trimmingCharacters(in: .whitespaces).isEmpty
        else { return nil }

        func float(_ map: [String: Any], _ key: String, _ fallback: Float) -> Float {
            guard let value = (map[key] as? NSNumber)?.floatValue, value.isFinite else {
                return fallback
            }
            return value
        }
        func flag(_ key: String) -> Bool { (args[key] as? NSNumber)?.boolValue ?? true }

        hitId = hit["id"] as? String
        position = SIMD3(float(hit, "x", 0), float(hit, "y", 0), float(hit, "z", 0))
        let raw = simd_quatf(
            ix: float(hit, "qx", 0),
            iy: float(hit, "qy", 0),
            iz: float(hit, "qz", 0),
            r: float(hit, "qw", 1)
        )
        rotation = simd_length(raw) > 1e-6 ? simd_normalize(raw) : simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)
        modelPath = path
        let requestedSize = float(model, "scale", 1)
        size = requestedSize > 0 ? requestedSize : 1
        editable = flag("editable")
        draggable = flag("draggable")
        rotatable = flag("rotatable")
        scalable = flag("scalable")
    }
}

/// Clamps a pinched uniform scale to `flutterPlacedScaleRange`.
func flutterClampPlacedScale(_ scale: Float) -> Float {
    guard scale.isFinite else { return 1 }
    return min(max(scale, flutterPlacedScaleRange.lowerBound), flutterPlacedScaleRange.upperBound)
}
