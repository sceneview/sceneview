#if os(iOS) || os(macOS) || os(visionOS)
import Foundation
import simd

// The pure, platform-independent half of ``SceneViewerHostView``.
//
// The host view itself is UIKit-only, so on macOS — where `swift test` runs — none of it
// compiles and none of it can be tested. The parts most likely to be silently wrong are
// exactly the parts that need no UIKit: which way the degree/radian conversion goes, what
// makes two model requests "the same model", and when an incoming camera pose is the
// host's own read-back echoing back. They live here so tests can pin them.

// MARK: - Angles

/// Degree ↔ radian conversion for the bridge boundary.
///
/// `SceneViewerSpec` (Kotlin) speaks **degrees**, matching its `CameraState`;
/// ``CameraControls`` and ``SceneCameraPose`` speak **radians**. Every crossing goes
/// through here rather than through an inline `* .pi / 180` whose direction is a coin
/// flip at the call site — a swapped conversion is invisible to the compiler and, at
/// small angles, nearly invisible on screen: 15° read as 15 rad is 859°, i.e. 139°, which
/// still shows a lit model from a plausible angle.
enum SceneViewerAngle {

    static func radians(fromDegrees degrees: Float) -> Float {
        degrees * .pi / 180
    }

    static func degrees(fromRadians radians: Float) -> Float {
        radians * 180 / .pi
    }
}

// MARK: - Model identity

/// What the host has been asked to display.
///
/// Deliberately not `Equatable`: the `.bytes` case would then compare megabytes of model
/// data on every SwiftUI update. ``key`` is the cheap identity used instead.
enum SceneViewerModelRequest {
    case none
    case asset(String)
    case url(URL)
    case bytes(Data, fileExtension: String)

    /// Stable identity for this request, or `nil` when there is no model.
    ///
    /// Drives the `.task(id:)` that loads the model, so it decides when a reload
    /// happens: same key, no reload; new key, reload. Two properties matter, and they
    /// pull in opposite directions — it must change when the model changes (or a swap is
    /// silently ignored) and must NOT change when it does not (or the model reloads on
    /// every recomposition, which on iOS means a visible flash and a dropped camera).
    ///
    /// For `.bytes` that rules out both obvious options: a hash of the whole buffer is
    /// O(n) on every update, and object identity is unavailable because the bytes arrive
    /// as a fresh `Data` each time they cross the language boundary. The length plus the
    /// first and last 16 bytes is O(1) and separates any two models that are not
    /// byte-identical at both ends *and* the same size — a collision needs a deliberately
    /// constructed pair.
    var key: String? {
        switch self {
        case .none:
            return nil
        case .asset(let path):
            return "asset:\(path)"
        case .url(let url):
            return "url:\(url.absoluteString)"
        case .bytes(let data, let fileExtension):
            return "bytes:\(fileExtension):\(data.count):\(Self.edgeSignature(of: data))"
        }
    }

    /// Hex of the first and last 16 bytes — the O(1) half of the `.bytes` identity.
    private static func edgeSignature(of data: Data) -> String {
        let edge = 16
        guard data.count > edge * 2 else {
            return data.map { String(format: "%02x", $0) }.joined()
        }
        let head = data.prefix(edge)
        let tail = data.suffix(edge)
        return (head + tail).map { String(format: "%02x", $0) }.joined()
    }
}

// MARK: - Lighting

/// The single key light + ambient level the viewer façade exposes.
///
/// `Equatable` so the host can rebuild the `LightNode` only when the values actually
/// change: ``LightSlot`` compares `.custom` cases by entity *reference*, so handing
/// `SceneView` a freshly-built node on every update would read as a slot change and swap
/// the light entity out of the scene 60 times a second.
struct SceneViewerLighting: Equatable {
    var direction: SIMD3<Float>
    var intensity: Float
    var ambientIntensity: Float
    var castShadows: Bool

    /// Direction to aim the light along, guaranteed usable.
    ///
    /// `Entity.look(at:from:)` needs a target distinct from the eye; a zero or
    /// non-finite direction would produce a NaN orientation and a light that renders
    /// nothing at all. Falls back to straight down, RealityKit's own main-light default.
    var normalizedDirection: SIMD3<Float> {
        let length = simd_length(direction)
        guard length.isFinite, length > 1e-6 else { return SIMD3<Float>(0, -1, 0) }
        return direction / length
    }
}
#endif
