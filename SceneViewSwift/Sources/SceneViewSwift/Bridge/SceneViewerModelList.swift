#if os(iOS) || os(macOS) || os(visionOS)
import Foundation
import simd

// The multi-model half of ``SceneViewerHostView``.
//
// The host started single-model, which is all `sceneview-compose` needs. The Flutter and
// React Native bridges are model *lists* — Flutter appends one model at a time over a
// method channel, React Native replaces the whole list on every prop update — and neither
// can migrate onto a host that renders one. This file adds the list without disturbing the
// single-model surface: a configuration with no ``SceneViewerConfiguration/models`` is
// resolved into a one-element list built from the single-model fields, so there is one
// reconciliation path rather than two that drift.
//
// It lives here, outside the UIKit-only host, for the same reason `SceneViewerBridgeSupport`
// does: the parts most likely to be silently wrong — what makes two entries "the same
// model", and which of append / replace semantics a list update expresses — need no UIKit
// and are therefore testable on macOS, where `swift test` runs.

// MARK: - One model in a list

/// One model in a ``SceneViewerConfiguration``'s ``SceneViewerConfiguration/models`` list.
///
/// Primitive-only for the same reason the configuration is: every member crosses a
/// language boundary. Vectors are three scalars, and every transform is optional so that
/// "not specified" stays distinguishable from "specified as the identity" — see
/// ``scaleX``.
@objc(SVSceneViewerModel)
public final class SceneViewerModel: NSObject {

    // MARK: Source — at most one of the three is set

    /// Bundle resource path, e.g. `"models/helmet.usdz"`.
    @objc public var assetPath: String?

    /// Absolute `http` / `https` URL of a `.usdz` / `.reality` file.
    @objc public var urlString: String?

    /// Model bytes already in memory. Must be a self-contained `.usdz` / `.reality`.
    @objc public var bytes: Data?

    /// Extension used for the temporary file ``bytes`` is written to. Default `"usdz"`.
    @objc public var bytesFileExtension: String = "usdz"

    // MARK: Identity

    /// Caller-supplied identity, deciding when this entry is "the same model" as before.
    ///
    /// **Set this to a value unique per list entry** if two entries may share a source.
    /// The source alone cannot express that: Flutter's `loadModel` called twice with one
    /// path means *two copies on screen*, and a source-keyed list would collapse them into
    /// one. Both bridges pass the UUID they already mint per entry, which also gives
    /// React Native its wholesale-replacement semantics for free — a new prop value mints
    /// new UUIDs, so every old entry is detached and every new one loads.
    ///
    /// Left `nil`, the source identifies the entry, which is what a single-model caller
    /// wants: re-applying the same configuration must not reload.
    @objc public var identity: String?

    /// Name assigned to the loaded entity, or `nil` to leave whatever the loader produced.
    ///
    /// This is what a tap reports back. `nil` is not the same as `""`: the Flutter bridge
    /// names its models after the file so its `onTap` can send that name, while the React
    /// Native bridge deliberately does not, and reports the name RealityKit gave the
    /// tapped entity. Naming unconditionally would silently change the React Native
    /// payload.
    @objc public var nodeName: String?

    // MARK: Transform — `NaN` means "not specified"

    /// Scale to apply, or `NaN` to leave the loaded model's own scale untouched.
    ///
    /// Sentinel-valued rather than optional because `Optional<Float>` does not cross into
    /// Objective-C, and the distinction is load-bearing: applying an identity scale is
    /// **not** the same as applying none. `ModelNode.scale(_:)` overwrites the transform
    /// component the asset authored, so a host that "helpfully" wrote 1 on every load
    /// would flatten every model that ships a non-identity root transform. Use
    /// ``setScale(_:_:_:)`` rather than writing `NaN` by hand.
    @objc public var scaleX: Float = .nan
    @objc public var scaleY: Float = .nan
    @objc public var scaleZ: Float = .nan

    /// Position to apply, or `NaN` to leave the loaded model where the asset puts it.
    /// See ``scaleX`` for why this is a sentinel rather than an optional.
    @objc public var positionX: Float = .nan
    @objc public var positionY: Float = .nan
    @objc public var positionZ: Float = .nan

    // MARK: Animation

    /// Animation to play once loaded, or `nil` for none.
    @objc public var animationName: String?

    /// Whether to play every animation when ``animationName`` is `nil` and the model has
    /// any. Default `false` — playing on load is the React Native bridge's behaviour, not
    /// a universal one, and the Flutter bridge does not do it.
    @objc public var autoPlayAllAnimations: Bool = false

    @objc public override init() {
        super.init()
    }

    /// Sets a uniform scale.
    @objc public func setScale(_ uniform: Float) {
        setScale(uniform, uniform, uniform)
    }

    /// Sets a non-uniform scale.
    @objc public func setScale(_ x: Float, _ y: Float, _ z: Float) {
        scaleX = x
        scaleY = y
        scaleZ = z
    }

    /// Sets a position.
    @objc public func setPosition(_ x: Float, _ y: Float, _ z: Float) {
        positionX = x
        positionY = y
        positionZ = z
    }
}

// MARK: - Resolved entry

/// A ``SceneViewerModel`` resolved into what the loader actually needs.
///
/// The optionals here are the point: they carry "not specified" through to the loader so
/// it can skip the corresponding mutation entirely, rather than writing a default over
/// something the asset authored.
struct SceneViewerModelEntry {

    /// Identity used to reconcile this entry against what is already attached.
    ///
    /// Combines the caller's identity with the source key, so an entry whose identity is
    /// reused for a different model still reloads. Neither bridge does that today; the
    /// combination costs nothing and removes the question.
    let key: String

    let request: SceneViewerModelRequest
    let nodeName: String?
    let scale: SIMD3<Float>?
    let position: SIMD3<Float>?
    let animationName: String?
    let autoPlayAllAnimations: Bool

    /// Builds an entry, or `nil` when the source is absent or a URL was refused.
    static func make(
        identity: String?,
        request: SceneViewerModelRequest?,
        nodeName: String?,
        scale: SIMD3<Float>?,
        position: SIMD3<Float>?,
        animationName: String?,
        autoPlayAllAnimations: Bool
    ) -> SceneViewerModelEntry? {
        guard let request, let requestKey = request.key else { return nil }
        return SceneViewerModelEntry(
            key: "\(identity ?? "")|\(requestKey)",
            request: request,
            nodeName: nodeName,
            scale: scale,
            position: position,
            animationName: animationName,
            autoPlayAllAnimations: autoPlayAllAnimations
        )
    }
}

/// A vector, or `nil` when any component is the "not specified" sentinel.
///
/// All three components are checked, not just the first: a partially-written vector is a
/// caller bug, and silently applying `(2, NaN, NaN)` as a scale would send the model to
/// nowhere with no diagnostic. Treating it as unspecified keeps a mistake visible as
/// "my scale did nothing" rather than as a vanished model.
func sceneViewerVector(_ x: Float, _ y: Float, _ z: Float) -> SIMD3<Float>? {
    guard x.isFinite, y.isFinite, z.isFinite else { return nil }
    return SIMD3<Float>(x, y, z)
}

/// Joint identity of a whole list, for the `.task(id:)` that loads it.
///
/// A newline join rather than a `[String]`: SwiftUI compares the task id by value on every
/// body evaluation, and a single string is one comparison instead of one per entry. The
/// separator cannot appear in a key — both halves of a key are a caller identity and a
/// source key, and neither is multi-line.
func sceneViewerListKey(_ entries: [SceneViewerModelEntry]) -> String {
    entries.map(\.key).joined(separator: "\n")
}

// MARK: - Camera control mode

/// Maps the wire name a bridge sends to a ``CameraControlMode``.
///
/// One mapper rather than the two identical private ones the Flutter and React Native
/// bridges each carried (`flutterCameraControlMode`, `rnCameraControlMode`). Unknown and
/// absent values both fall back to `.orbit`, which is what both of those did — a bridge
/// receives this string from user-authored Dart or JavaScript, so an unrecognised value is
/// a typo to absorb, not a reason to render nothing.
func sceneViewerCameraControlMode(_ raw: String?) -> CameraControlMode {
    switch raw {
    case "pan": return .pan
    case "firstPerson": return .firstPerson
    default: return .orbit
    }
}

// MARK: - Resolving a configuration

#if canImport(UIKit) && (os(iOS) || os(visionOS))
extension SceneViewerModelEntry {

    /// Resolves `configuration` into the list to render.
    ///
    /// ``SceneViewerConfiguration/models`` wins when it is non-empty; otherwise the
    /// single-model fields are resolved into a one-element list. That fallback is what
    /// lets `sceneview-compose`, which knows nothing about lists, keep working unchanged
    /// through the same reconciliation path — one path that both callers exercise, rather
    /// than a second one that only the older caller runs and that therefore rots.
    static func entries(from configuration: SceneViewerConfiguration) -> [SceneViewerModelEntry] {
        guard !configuration.models.isEmpty else {
            let request = SceneViewerModelRequest.make(
                assetPath: configuration.modelAssetPath,
                urlString: configuration.modelURLString,
                bytes: configuration.modelBytes,
                bytesFileExtension: configuration.modelBytesFileExtension
            )
            return [
                make(
                    identity: nil,
                    request: request,
                    nodeName: nil,
                    scale: nil,
                    position: nil,
                    animationName: nil,
                    autoPlayAllAnimations: false
                )
            ].compactMap { $0 }
        }

        return configuration.models.compactMap { model in
            make(
                identity: model.identity,
                request: SceneViewerModelRequest.make(
                    assetPath: model.assetPath,
                    urlString: model.urlString,
                    bytes: model.bytes,
                    bytesFileExtension: model.bytesFileExtension
                ),
                nodeName: model.nodeName,
                scale: sceneViewerVector(model.scaleX, model.scaleY, model.scaleZ),
                position: sceneViewerVector(model.positionX, model.positionY, model.positionZ),
                animationName: model.animationName,
                autoPlayAllAnimations: model.autoPlayAllAnimations
            )
        }
    }
}
#endif
#endif
