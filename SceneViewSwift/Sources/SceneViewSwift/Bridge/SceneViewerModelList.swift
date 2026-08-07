#if os(iOS) || os(macOS) || os(visionOS)
import Foundation
import RealityKit
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

    /// Name assigned to the loaded entity, overriding the one derived from the source.
    ///
    /// This is what a tap reports back. Leave it `nil` and the host names the model root
    /// after its file — see ``sceneViewerNodeName(explicit:request:)``, which is the one
    /// derivation every caller of this host shares. Set it only to publish a name the
    /// source cannot produce.
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
            nodeName: sceneViewerNodeName(explicit: nodeName, request: request),
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

// MARK: - Camera pose

/// The value handed to ``SceneView/cameraPose(_:)``: the pose, or `nil` when the caller
/// authors no camera at all.
///
/// Not a cosmetic nil-check. `SceneView` applies a non-nil request the first time it sees
/// one — its `appliedCache.requestedPose` starts `nil`, so the first comparison is
/// non-nil against nil and the pose is written. Handing over a *default* pose therefore
/// frames the scene, and the host's default (elevation 15°) is not `CameraControls`' own
/// (elevation 30°). A bridge that never exposed a camera would have silently changed
/// viewing angle on being moved onto this host; auto-centering re-fits distance and
/// target and hides everything except the angle.
func sceneViewerRequestedPose(
    authored: Bool,
    pose: SceneCameraPose
) -> SceneCameraPose? {
    authored ? pose : nil
}

// MARK: - Tap resolution

/// The model `tapped` belongs to: the direct child of `contentRoot` above it.
///
/// Every model a host loads is attached as a direct child of its content root and named
/// after its source, so climbing to that child turns any hit — however deep inside the
/// asset — into the model that was tapped. `SpatialTapGesture` hands back the *deepest*
/// hit entity, and walking up to the first *named* ancestor instead stops inside the
/// asset, because USD assets name their meshes: that is what reported `skin0` for a tap
/// on `black_dragon.usdz`.
///
/// `nil` when `tapped` is not part of a model that root loaded — including the content
/// root itself, which is not a model, and an entity detached from it entirely.
///
/// A free function rather than a method on the content root so `swift test` on macOS
/// covers it. The host is UIKit-only, so it is only ever built by the iOS-Simulator
/// `xcodebuild test` leg (`.github/workflows/ios.yml`) — reachable, but not from the
/// local run a change to this walk is made under, and this walk is precisely the logic
/// that was wrong.
@MainActor
func sceneViewerModelRoot(for tapped: Entity, contentRoot: Entity) -> Entity? {
    var node: Entity? = tapped
    while let current = node, current !== contentRoot {
        if current.parent === contentRoot { return current }
        node = current.parent
    }
    return nil
}

// MARK: - Node name

/// The name given to a loaded model root — which is what a tap reports back.
///
/// `explicit` wins when a caller set one. Otherwise the name is derived from the source
/// exactly the way the Android bridges derive theirs (`SceneViewPlugin.tapNodeName`,
/// `ModelNodeData.nodeName`): strip any query and fragment, take the last path component,
/// drop the extension. `models/robot.glb` → `robot`.
///
/// **Nothing but the path is looked at, and that is not decoration.** A model source may
/// be a URL whose query carries a credential, and `deletingPathExtension` on a raw URL
/// string only removes the extension when it is the last dot in the whole string —
/// `https://cdn/robot.glb?sig=SIG&v=1.2` would otherwise be published as
/// `robot.glb?sig=SIG&v=1`. A URL is therefore reduced to `URL.path` before anything else,
/// which drops the scheme, the userinfo and the authority outright rather than relying on
/// the last-path-component cut to step over them — `https://user:pass@host` has no path
/// component at all, so that cut would have published the credentials verbatim.
///
/// `nil` for `.bytes` (there is no file name to derive from) and whenever the derivation
/// comes out empty, which leaves the loaded entity with whatever name the asset carries —
/// and leaves the caller's "no model name" case representable rather than reporting `""`.
/// An `explicit` of `""` is treated as unset and derives from the source, for the same
/// reason: an empty name is not a name.
///
/// What a bridge does with `nil` is the bridge's own call and they differ — Flutter Android
/// falls back to `node_<index>` where the iOS side reports the empty string.
///
/// Three known divergences from Kotlin, none reachable for the `.glb` / `.gltf` / `.usdz`
/// sources these bridges load. `NSString` path semantics differ from Kotlin's
/// `substringAfterLast('/')` / `substringBeforeLast('.')` on a trailing slash (`models/` →
/// Kotlin `""`, Swift `models`) and on a dotfile base name (`.hidden` → Kotlin `""`, Swift
/// `.hidden`). And `URL.path` percent-decodes where Kotlin's string surgery does not, so a
/// percent-encoded path component resolves differently (`robot%20arm.glb` → Swift
/// `robot arm`, Kotlin `robot%20arm`). Decoding on the Kotlin side would need a decoder
/// with its own surprises (`+` becomes a space), for a shape no bridge emits.
func sceneViewerNodeName(explicit: String?, request: SceneViewerModelRequest) -> String? {
    if let explicit, !explicit.isEmpty { return explicit }

    let source: String
    switch request {
    case .none, .bytes:
        return nil
    case .asset(let path):
        source = path
    case .url(let url):
        // The path only — see the note above. It also percent-decodes, so an encoded
        // `%3F` is a real `?` by the time the strip below sees it.
        source = url.path
    }

    let stripped = String(source.prefix { $0 != "?" && $0 != "#" })
    let base = (stripped as NSString).lastPathComponent
    let name = (base as NSString).deletingPathExtension
    // `"/"` is what `lastPathComponent` returns for a root-only path, which is what a
    // path-less URL reduces to (`https://host/` → `/`). It is not a file name, and
    // publishing it would be indistinguishable from a model genuinely called `/`.
    return name.isEmpty || name == "/" ? nil : name
}

// MARK: - Bytes file extension

/// The extension the in-memory-bytes path may append to a temp file name.
///
/// `bytesFileExtension` is public `@objc` on both ``SceneViewerModel`` and
/// ``SceneViewerConfiguration``, and it reaches `appendingPathExtension` and then
/// `Data.write(to:)`. No shipped bridge sets it from untrusted input — Flutter and React
/// Native only ever send an asset path — but "no caller does this today" is a property of
/// the callers, not of the API, and this one is the same shape as the URL-scheme
/// allowlist the bridge boundary already enforces. Anything that is not a short
/// alphanumeric run cannot shape a path, so it is refused rather than sanitised into
/// something the caller did not ask for.
///
/// Refusal falls back to `usdz`, the default and the only value either bridge uses.
func sceneViewerBytesFileExtension(_ raw: String) -> String {
    let allowed = raw.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) }
    guard allowed, !raw.isEmpty, raw.count <= 8 else { return "usdz" }
    return raw
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
                bytesFileExtension: sceneViewerBytesFileExtension(
                    configuration.modelBytesFileExtension
                )
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
                    bytesFileExtension: sceneViewerBytesFileExtension(
                        model.bytesFileExtension
                    )
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
