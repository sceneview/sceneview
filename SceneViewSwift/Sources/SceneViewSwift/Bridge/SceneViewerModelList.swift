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

    /// Name assigned to the loaded entity, or `nil` to leave whatever the loader produced.
    ///
    /// This is what a tap reports back: ``SceneViewerHostView/onTapEntity`` resolves the
    /// tapped entity to the model root — the entity this name is written on — so whatever
    /// a bridge sets here *is* its tap payload. Both shipped bridges set the model file's
    /// name, via ``sceneViewerModelFileName(_:)``, and strip the extension when reporting.
    ///
    /// `nil` is not the same as `""`: left `nil`, the loader's own name survives, which is
    /// what a single-model caller that never reads a tap name wants.
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

// MARK: - Model naming and tap resolution

/// The model file's name (query and fragment stripped), used to name a loaded model root.
///
/// Query and fragment go before the last path component is taken, because a model source
/// may be a URL: `deletingPathExtension` on a raw URL only removes the extension when it
/// is the last dot in the whole string, so `https://cdn/robot.glb?sig=SIG&v=1.2` would
/// otherwise report `robot.glb?sig=SIG&v=1` as the tapped node's name — a CDN signature
/// leaking into a payload apps routinely put in a label or an analytics event. That is not
/// hypothetical on this side either: ``SceneViewerModel/urlString`` already accepts a
/// remote `.usdz`.
///
/// The extension is kept here and stripped at the report, so a bridge that wants the file
/// name for something else still has it.
///
/// The authority goes with them, via ``sceneViewerSourcePath(_:)``: the last path component
/// of a URL that has NO path is the authority itself, and an authority may carry userinfo,
/// so `https://user:pa55w0rd@cdn.example` reported `user:pa55w0rd@cdn` (#3071). Android is
/// the live surface for that one — `ModelLoader` genuinely loads `https://` sources, while
/// an `.asset` here resolves through `Entity(named:)` — so this side hardens a reachable
/// input class rather than closing a live leak, and stays derivation-identical to Kotlin.
///
/// Divergences from the Android bridges' Kotlin derivation
/// (`substringAfterLast('/')` / `substringBeforeLast('.')`) on inputs no loader accepts.
/// The cases below are the measured ones, not a closed set — `NSString` path semantics and
/// Kotlin's `substringBeforeLast` disagree wherever the base name is degenerate, and every
/// such input is unreachable for the `.glb` / `.gltf` / `.usdz` sources these bridges load:
/// - Measured on the full derivation (this function plus the `deletingPathExtension` the
///   bridges apply at the report), as Kotlin → Swift: `models/` → `""` / `models`;
///   `.hidden` → `""` / `.hidden`; `..` → `.` / `..`; and `robot.` → `robot` / `robot.`,
///   because `deletingPathExtension` does not treat a trailing dot as an extension.
/// - `/` is NO LONGER a divergence: `lastPathComponent` returns `"/"` for a root-only path,
///   which is what a path-less URL reduces to. It is not a file name, and publishing it
///   would be indistinguishable from a model genuinely called `/`, so it maps to `""` —
///   the same value Kotlin produces, and the value that makes the Flutter bridge fall back.
/// - Android's Flutter bridge falls back to `node_<index>` when the derived base name is
///   empty; this side has no fallback and names the entity `""`, which a tap then reports
///   as `""` — the same value a tap that hit no bridge-loaded model at all reports.
public func sceneViewerModelFileName(_ path: String) -> String {
    let stripped = String(path.prefix { $0 != "?" && $0 != "#" })
    let base = (sceneViewerSourcePath(stripped) as NSString).lastPathComponent
    return base == "/" ? "" : base
}

/// The path part of `source` when it is a URL, `source` unchanged otherwise.
///
/// Deliberately string surgery rather than `URL(string:)?.path`: this must stay
/// derivation-identical to the Kotlin `urlPathOf` in both Android bridges, and `URL`
/// percent-DECODES its `path`, which would turn an encoded `%3F` into a real `?` *after*
/// the query strip has already run — reintroducing on this side exactly the class of leak
/// the strip exists to close.
///
/// Returns `""` for a path-less URL: there is no file name in `https://cdn.example`.
func sceneViewerSourcePath(_ source: String) -> String {
    guard let schemeEnd = source.range(of: "://") else { return source }
    // A "://" that appears after a slash is not a scheme delimiter — the string is already
    // a path (`models/odd://name.glb`) and cutting at it would drop real path segments.
    if let firstSlash = source.firstIndex(of: "/"), firstSlash < schemeEnd.lowerBound {
        return source
    }
    let afterScheme = source[schemeEnd.upperBound...]
    guard let pathStart = afterScheme.firstIndex(of: "/") else { return "" }
    return String(afterScheme[pathStart...])
}

/// Resolves a tapped entity to the bridge-loaded model root: the direct child of
/// `contentRoot`. Returns nil when the entity is not inside any loaded model.
///
/// `SpatialTapGesture` reports the *deepest* hit entity, and USDZ assets name their
/// meshes — a tap on `black_dragon.usdz` reports `skin0` if the name is read off the hit
/// entity, and a walk to the first *named* ancestor stops at the same place. The one
/// entity whose name a bridge controls is the model root, the direct child of the content
/// root (``SceneViewerModel/nodeName`` is written on it), so the walk climbs to exactly
/// that and nothing else.
///
/// Android cannot reproduce the bug and so cannot be the reference for the walk: the only
/// collider a loaded model owns there is the `ModelNode` root — glTF child renderables get
/// no collision shape — so its hit-test can only ever resolve to the model.
func sceneViewerTappedModelEntity(_ entity: Entity, contentRoot: Entity) -> Entity? {
    var node: Entity? = entity
    while let current = node, current !== contentRoot {
        if current.parent === contentRoot { return current }
        node = current.parent
    }
    return nil
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
