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

    /// Builds the request a configuration describes, applying the URL scheme allowlist.
    ///
    /// Lives here rather than inside the UIKit-only host so it can be tested: the scheme
    /// check is a security invariant, and an invariant nothing exercises is a comment.
    ///
    /// Only `http` and `https` are accepted. `URL(string:)` takes any scheme and
    /// `URLSession.download` honours `file://` — measured, not assumed — so a host
    /// forwarding an attacker-influenced string (a Flutter channel argument, a React
    /// Native prop, a deep link, a remote-config value) would otherwise turn a `file://`
    /// into an in-sandbox file read handed to RealityKit's USD parser. `ModelSource`'s
    /// KDoc already promises callers on every platform that this cannot happen, and the
    /// Android downloader re-checks for the same reason; this is the second public entry
    /// point onto the same loader, and the Kotlin guard does not cover it.
    ///
    /// - Returns: the request, or `nil` when a URL was supplied and refused. `nil` is
    ///   distinct from `.none` (no model set) so the caller can say which happened —
    ///   both render as an empty viewport, and a developer who cannot tell them apart
    ///   will look for the bug anywhere but here.
    static func make(
        assetPath: String?,
        urlString: String?,
        bytes: Data?,
        bytesFileExtension: String
    ) -> SceneViewerModelRequest? {
        if let assetPath, !assetPath.isEmpty {
            return .asset(assetPath)
        }
        if let urlString, !urlString.isEmpty {
            guard let url = URL(string: urlString),
                  let scheme = url.scheme?.lowercased(),
                  scheme == "http" || scheme == "https" else {
                return nil
            }
            return .url(url)
        }
        if let bytes, !bytes.isEmpty {
            return .bytes(bytes, fileExtension: bytesFileExtension)
        }
        return SceneViewerModelRequest.none
    }

    /// Fold of the first and last 16 bytes — the O(1) half of the `.bytes` identity.
    ///
    /// A numeric fold rather than a hex string: this runs on every configuration update,
    /// which during a drag means every frame, and 32 `String(format:)` calls plus the
    /// joins were pure per-frame garbage for a value that only ever gets compared.
    private static func edgeSignature(of data: Data) -> UInt64 {
        let edge = 16
        var hash: UInt64 = 0xcbf29ce484222325
        func fold(_ byte: UInt8) {
            hash = (hash ^ UInt64(byte)) &* 0x100000001b3
        }
        if data.count > edge * 2 {
            for byte in data.prefix(edge) { fold(byte) }
            for byte in data.suffix(edge) { fold(byte) }
        } else {
            for byte in data { fold(byte) }
        }
        return hash
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
