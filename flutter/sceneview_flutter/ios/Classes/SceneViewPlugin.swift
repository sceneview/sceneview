import Flutter
import UIKit
import SwiftUI
import RealityKit
import ARKit
import SceneViewSwift

/// Flutter plugin entry point for SceneView on iOS.
///
/// Registers two platform view types:
/// - `io.github.sceneview.flutter/sceneview`   -- 3D scene (wraps SceneViewSwift.SceneView)
/// - `io.github.sceneview.flutter/arsceneview` -- AR scene (wraps SceneViewSwift.ARSceneView)
public class SceneViewPlugin: NSObject, FlutterPlugin {

    public static func register(with registrar: FlutterPluginRegistrar) {
        // `Eager`, not the default `WaitUntilTouchesEnded` — root cause of #3045.
        //
        // Flutter's default policy holds every `UIGestureRecognizer` on an embedded
        // platform view in a blocked state until the *whole* touch sequence has ended,
        // then — per Flutter's own header doc — "results in the platform view's
        // UIGestureRecognizers seeing the entire touch sequence, but never recognizing
        // the gesture (and never invoking actions)". `SpatialTapGesture` (targeted and
        // untargeted alike) is exactly that: a discrete, state-based recognizer that
        // needs to transition into `.recognized` to fire its handler, so it was
        // categorically silent — measured directly by instrumenting both
        // `.targetedToAnyEntity()` and a bare, untargeted `SpatialTapGesture()` control
        // inside this bridge and observing neither ever invoke `onEnded`, while a
        // `DragGesture` (continuous, driven by raw touch deltas rather than a
        // recognizer-state transition) visibly orbited the camera through the same
        // platform view — the same asymmetry a tap-vs-drag split like this always
        // points to. `Eager` unblocks a platform view's own recognizers as soon as
        // Flutter decides they should run, which is what SceneView needs to resolve a
        // tap at all; it does not affect the `SceneView`'s already-working drag/pinch
        // path or Flutter's own widgets, which never went through this gate.
        registrar.register(
            SceneViewFactory(messenger: registrar.messenger()),
            withId: "io.github.sceneview.flutter/sceneview",
            gestureRecognizersBlockingPolicy: FlutterPlatformViewGestureRecognizersBlockingPolicyEager
        )
        // AR shares nothing with the 3D path's host (`ARSceneView` is anchor-driven,
        // no `SceneView` gesture stack) but suffers the identical Flutter-side gate for
        // its own `onTapOnPlane` — the same policy switch closes that gap too, at zero
        // cost to the AR session or plane-detection gestures.
        registrar.register(
            ARSceneViewFactory(messenger: registrar.messenger()),
            withId: "io.github.sceneview.flutter/arsceneview",
            gestureRecognizersBlockingPolicy: FlutterPlatformViewGestureRecognizersBlockingPolicyEager
        )
    }
}

// MARK: - AR model loading

// The 3D path no longer loads anything itself — `SceneViewerHostView` resolves an
// asset / URL / bytes request and reports failures. The AR path still does, because
// `ARSceneView` is anchor-driven and shares no host with the viewer, so the two
// helpers below survive for `ARPlacementController` alone. Deleting them with the
// rest of the pre-host loading code would have left AR loading `.glb` paths and
// remote URLs by falling into `ModelNode.load(_:)`, which handles neither.

/// Formats RealityKit can actually parse. `Entity(named:)` and
/// `Entity(contentsOf:)` read USD variants and `.reality` — nothing else. A
/// `.glb` fails with a generic error that reads like a missing file, so the
/// bridge names the real reason instead of relaying it.
let flutterSupportedModelExtensions: Set<String> = [
    "usdz", "usda", "usdc", "usd", "reality"
]

/// Returns an actionable reason why `path` cannot be loaded on Apple platforms,
/// or nil when the format is loadable.
///
/// An extension-less path is accepted: `Entity(named:)` resolves bundle
/// resources by name alone.
func flutterUnsupportedModelReason(_ path: String) -> String? {
    // Query strings and fragments are not part of the file's extension. Both
    // separators are stripped: splitting on "?" alone left `fox.usdz#frag` with
    // an extension of `usdz#frag`, which failed the allowlist and told the
    // caller RealityKit cannot parse a format that is in fact supported.
    let withoutQueryOrFragment = path
        .split(whereSeparator: { $0 == "?" || $0 == "#" })
        .first
        .map(String.init) ?? path
    let ext = (withoutQueryOrFragment as NSString).pathExtension.lowercased()
    guard !ext.isEmpty else { return nil }
    guard !flutterSupportedModelExtensions.contains(ext) else { return nil }
    return "RealityKit cannot parse '.\(ext)' — it reads "
        + flutterSupportedModelExtensions.sorted().map { ".\($0)" }.joined(separator: ", ")
        + " only. Convert the model (tools/convert-usdz.sh) and bundle the .usdz "
        + "as an app resource, or point modelPath at a remote .usdz."
}

/// Loads one AR model, choosing between a remote download and a bundle-resource
/// lookup.
///
/// The Android bridge hands `modelPath` straight to Filament's `ModelLoader`,
/// which accepts both an asset path and an `https://` URL. `ModelNode.load(_:)`
/// on Apple is a *bundle resource* lookup only, so a URL used to fail here
/// while the same Dart code worked on Android. Routing HTTP(S) through
/// `ModelNode.load(from:)` — which downloads first — closes that divergence.
///
/// The 3D path gets the same treatment a layer down, by putting the URL in
/// `SceneViewerModel.urlString` instead of `assetPath` (see `applyState`).
@MainActor
func flutterLoadModel(path: String) async throws -> ModelNode {
    if let url = flutterRemoteModelURL(path) {
        return try await ModelNode.load(from: url)
    }
    return try await ModelNode.load(path)
}

/// The `http(s)` URL `path` denotes, or nil when it is a bundle-resource path.
///
/// Only these two schemes are treated as remote. Anything else — including
/// `file://` — stays an asset path, and `SceneViewerModelRequest.make` refuses it
/// again on the host side; a `file://` reaching `URLSession.download` would be an
/// in-sandbox file read handed to RealityKit's USD parser.
func flutterRemoteModelURL(_ path: String) -> URL? {
    guard let url = URL(string: path),
          let scheme = url.scheme?.lowercased(),
          scheme == "http" || scheme == "https" else { return nil }
    return url
}

// MARK: - Model data

struct FlutterModelData: Identifiable, Equatable {
    let id = UUID()
    let path: String
    let scale: Float

    static func == (lhs: FlutterModelData, rhs: FlutterModelData) -> Bool {
        lhs.id == rhs.id
    }
}

// MARK: - 3D SceneView

class SceneViewFactory: NSObject, FlutterPlatformViewFactory {
    private let messenger: FlutterBinaryMessenger

    init(messenger: FlutterBinaryMessenger) {
        self.messenger = messenger
        super.init()
    }

    func create(
        withFrame frame: CGRect,
        viewIdentifier viewId: Int64,
        arguments args: Any?
    ) -> FlutterPlatformView {
        return SceneViewPlatformView(
            frame: frame,
            viewId: viewId,
            args: args as? [String: Any] ?? [:],
            messenger: messenger
        )
    }

    func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
        return FlutterStandardMessageCodec.sharedInstance()
    }
}

/// Observable model holding scene state, updated via method channel.
///
/// Shared by the 3D and AR platform views. Only the AR one still drives SwiftUI
/// from it — the 3D view reads it to build a `SceneViewerConfiguration` — but it
/// stays an `ObservableObject` because the AR wrapper observes it.
@MainActor
class SceneState: ObservableObject {
    @Published var models: [FlutterModelData] = []
    @Published var environmentPath: String?

    /// Wire name as it arrives from Dart: `"orbit"`, `"pan"` or `"firstPerson"`.
    ///
    /// Kept as the raw string rather than mapped to a `CameraControlMode` here:
    /// `SceneViewerConfiguration` takes the same three wire names and normalises
    /// an unrecognised one to `"orbit"`, which is exactly what the mapping this
    /// replaces did. Mapping locally would mean converting back to a string at
    /// the boundary, with two normalisations free to disagree.
    @Published var cameraControlMode: String = "orbit"
    @Published var autoCenterContent: Bool = true

    /// `nonisolated` so the platform-view classes (plain `NSObject`s, not
    /// `@MainActor`) can construct a `SceneState` as a stored-property
    /// initialiser. The initialiser only assigns the inline property defaults
    /// — no main-actor state is touched — so it is safe outside the actor.
    /// Without this, the `@MainActor`-implicit `init()` is unreachable from
    /// the non-isolated `SceneViewPlatformView` / `ARSceneViewPlatformView`
    /// initialisers and the whole Flutter iOS target fails to compile under
    /// Swift 6 actor checking (issue #2065).
    nonisolated init() {}
}

/// The 3D platform view, hosted on the shared `SceneViewerHostView`.
///
/// This class owns the method channel and the Dart-facing state; everything
/// below the `SceneViewerConfiguration` it builds — hosting SwiftUI in UIKit,
/// loading models, reconciling the scene — belongs to `SceneViewerHostView` and
/// is shared with the React Native bridge and `sceneview-compose`. The AR
/// platform view below still has its own SwiftUI wrapper: `ARSceneView` is
/// anchor-driven and shares nothing with the 3D viewer.
class SceneViewPlatformView: NSObject, FlutterPlatformView {
    private let hostView: SceneViewerHostView
    private let channel: FlutterMethodChannel
    private let sceneState = SceneState()

    init(frame: CGRect, viewId: Int64, args: [String: Any], messenger: FlutterBinaryMessenger) {
        self.channel = FlutterMethodChannel(
            name: "io.github.sceneview.flutter/scene_\(viewId)",
            binaryMessenger: messenger
        )

        // `SceneViewerHostView` is `@MainActor` and this initialiser is not — Flutter
        // declares `FlutterPlatformViewFactory` without isolation. Asserted rather than
        // hopped onto with a `Task`, because Flutter creates platform views on the
        // platform thread, which *is* the main thread: `assumeIsolated` states that fact
        // and traps if it ever stops being true, where a `Task` would silently defer the
        // whole setup past `view()` and hand Flutter a view with no scene in it.
        self.hostView = MainActor.assumeIsolated { SceneViewerHostView(frame: frame) }

        super.init()

        // Apply v4.3.0 creation params (camera mode + auto-centre).
        let mode = args["cameraControlMode"] as? String
        let autoCenter = (args["autoCenterContent"] as? NSNumber)?.boolValue ?? true

        // Capture the channel weakly, never `self`: the host view holds this
        // closure, and this object holds the host view (issue #2069).
        //
        // `onTapEntity` rather than the host's flattened `onTap`, because what
        // Dart receives is a node name, and the flattened callback carries a
        // position and no entity at all. The host resolves the tapped entity to
        // the model root — the entity this bridge named after its file — so the
        // name reported is the model's, never an asset-internal mesh's.
        //
        // A tap that resolved outside every configured model reports `""`, the
        // value this bridge has always used for "hit nothing of mine": the Dart
        // callback is `void Function(String)` and widening it to `String?` on a
        // published pub.dev API would be source-breaking.
        let channel = self.channel
        MainActor.assumeIsolated {
            hostView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            hostView.onTapEntity = { [weak channel] _, modelRoot in
                let name = ((modelRoot?.name ?? "") as NSString).deletingPathExtension
                channel?.invokeMethod("onTap", arguments: name)
            }
            sceneState.cameraControlMode = mode ?? "orbit"
            sceneState.autoCenterContent = autoCenter
            applyState()
        }

        // Install the handler with a `[weak self]` capture so the channel does
        // not strong-hold the platform view. A bare `handleMethodCall` method
        // reference would strong-capture `self`, forming a retain cycle
        // (self -> channel -> handler -> self) that pins the retain count
        // above zero forever, so `deinit` would never run (issue #2069).
        channel.setMethodCallHandler { [weak self] call, result in
            self?.handleMethodCall(call, result: result)
        }
    }

    deinit {
        // `deinit` now fires naturally because the handler no longer pins
        // `self`. Still detach the channel handler as hygiene — the channel
        // may outlive the platform view (issue #2052).
        channel.setMethodCallHandler(nil)
    }

    func view() -> UIView {
        return hostView
    }

    /// Pushes the current `SceneState` into the host view.
    ///
    /// Called after every mutation. `applyConfiguration` compares field by field
    /// and touches only what changed, so re-sending the whole configuration on
    /// each method call costs nothing and removes the question of which subset a
    /// given call has to send.
    @MainActor
    private func applyState() {
        let configuration = SceneViewerConfiguration()

        configuration.models = sceneState.models.map { data in
            let model = SceneViewerModel()
            // An `http(s)` path is a download, not a bundle resource. The host reads
            // exactly one of these two fields, checking `assetPath` first, so sending a
            // URL as `assetPath` would make it a resource lookup for a name starting
            // "https:" — which fails as "model not found" and never reaches the
            // downloader. `modelPath`'s Dart doc promises a URL works, and it does on
            // Android, where Filament's ModelLoader takes either.
            if let url = flutterRemoteModelURL(data.path) {
                model.urlString = url.absoluteString
            } else {
                model.assetPath = data.path
            }
            // The Dart side's own per-entry id, so two `loadModel` calls with the
            // same path stay two models on screen rather than collapsing into one.
            model.identity = data.id.uuidString
            // The file's name — with its extension, which the tap report strips.
            // Query and fragment go first, so a URL source cannot leak a CDN
            // signature into the name. Matches Android's `tapNodeName`.
            model.nodeName = sceneViewerModelFileName(data.path)
            model.setScale(data.scale)
            return model
        }

        configuration.cameraControlMode = sceneState.cameraControlMode
        configuration.autoCenterContent = sceneState.autoCenterContent
        // This bridge exposes no camera on its Dart surface, so it authors no pose:
        // without this, every method call would re-assert the configuration's default
        // pose and snap the camera back out of the auto-centre framing and away from
        // wherever the user had orbited to.
        configuration.cameraPoseAuthored = false

        if let path = sceneState.environmentPath, !path.isEmpty {
            configuration.environmentKind = "hdr"
            configuration.environmentHdrPath = path
        }

        hostView.applyConfiguration(configuration)
    }

    private func handleMethodCall(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "loadModel":
            guard let args = call.arguments as? [String: Any],
                  let modelPath = args["modelPath"] as? String else {
                result(FlutterError(code: "INVALID_ARGS", message: "modelPath required", details: nil))
                return
            }
            let scale = (args["scale"] as? NSNumber)?.floatValue ?? 1.0
            Task { @MainActor in
                sceneState.models.append(FlutterModelData(path: modelPath, scale: scale))
                applyState()
            }
            result(nil)

        case "addGeometry":
            // Geometry nodes require Compose/SwiftUI DSL context.
            // Acknowledged but no-op for now.
            result(nil)

        case "addLight":
            // Light configuration uses scene defaults on iOS.
            // Acknowledged but no-op for now.
            result(nil)

        case "clearScene":
            Task { @MainActor in
                sceneState.models.removeAll()
                applyState()
            }
            result(nil)

        case "setEnvironment":
            let hdrPath = (call.arguments as? [String: Any])?["hdrPath"] as? String
            Task { @MainActor in
                sceneState.environmentPath = hdrPath
                applyState()
            }
            result(nil)

        case "setCameraControlMode":
            let raw = (call.arguments as? [String: Any])?["mode"] as? String
            Task { @MainActor in
                sceneState.cameraControlMode = raw ?? "orbit"
                applyState()
            }
            result(nil)

        case "setAutoCenterContent":
            let enabled = ((call.arguments as? [String: Any])?["enabled"] as? NSNumber)?.boolValue ?? true
            Task { @MainActor in
                sceneState.autoCenterContent = enabled
                applyState()
            }
            result(nil)

        default:
            result(FlutterMethodNotImplemented)
        }
    }
}

// MARK: - AR SceneView

class ARSceneViewFactory: NSObject, FlutterPlatformViewFactory {
    private let messenger: FlutterBinaryMessenger

    init(messenger: FlutterBinaryMessenger) {
        self.messenger = messenger
        super.init()
    }

    func create(
        withFrame frame: CGRect,
        viewIdentifier viewId: Int64,
        arguments args: Any?
    ) -> FlutterPlatformView {
        return ARSceneViewPlatformView(
            frame: frame,
            viewId: viewId,
            args: args as? [String: Any] ?? [:],
            messenger: messenger
        )
    }

    func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
        return FlutterStandardMessageCodec.sharedInstance()
    }
}

class ARSceneViewPlatformView: NSObject, FlutterPlatformView {
    private let hostingController: UIHostingController<ARSceneViewSwiftUIWrapper>
    private let channel: FlutterMethodChannel
    private let sceneState = SceneState()
    /// Owns model loading, plane taps and placed models (#3780). Shared with
    /// the SwiftUI wrapper so `placeModel` calls and the view act on one state.
    private let placement: ARPlacementController

    /// ReplayKit-backed AR session recorder (v4.3.0, issue #1053).
    /// Lazily created on the main actor on first use.
    private var recorder: ARRecorder?

    @MainActor private func ensureRecorder() -> ARRecorder {
        if let recorder { return recorder }
        let created = ARRecorder()
        recorder = created
        return created
    }

    init(frame: CGRect, viewId: Int64, args: [String: Any], messenger: FlutterBinaryMessenger) {
        let channel = FlutterMethodChannel(
            name: "io.github.sceneview.flutter/scene_\(viewId)",
            binaryMessenger: messenger
        )
        self.channel = channel
        // `weak`: the controller must not keep the channel (and through its
        // handler, this view) alive — same retain-cycle concern as #2069.
        // `assumeIsolated` for the same reason as `SceneViewerHostView` above:
        // Flutter creates platform views on the main thread, and the
        // controller is `@MainActor`.
        let planeTap = (args["planeTap"] as? NSNumber)?.boolValue ?? false
        let placement = MainActor.assumeIsolated {
            ARPlacementController(
                planeTapEnabled: planeTap,
                onPlaneTap: { [weak channel] hit in
                    channel?.invokeMethod("onPlaneTap", arguments: hit)
                }
            )
        }
        self.placement = placement
        self.hostingController = UIHostingController(
            rootView: ARSceneViewSwiftUIWrapper(state: sceneState, placement: placement)
        )
        self.hostingController.view.frame = frame
        self.hostingController.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        super.init()

        // Install the handler with a `[weak self]` capture so the channel does
        // not strong-hold the platform view. A bare `handleMethodCall` method
        // reference would strong-capture `self`, forming a retain cycle
        // (self -> channel -> handler -> self) that pins the retain count
        // above zero forever, so `deinit` would never run (issue #2069).
        channel.setMethodCallHandler { [weak self] call, result in
            self?.handleMethodCall(call, result: result)
        }
    }

    deinit {
        // `deinit` now fires naturally because the handler no longer pins
        // `self`. Still detach the channel handler as hygiene — the channel
        // may outlive the platform view (issue #2052).
        channel.setMethodCallHandler(nil)
    }

    func view() -> UIView {
        return hostingController.view
    }

    private func handleMethodCall(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "loadModel":
            guard let args = call.arguments as? [String: Any],
                  let modelPath = args["modelPath"] as? String else {
                result(FlutterError(code: "INVALID_ARGS", message: "modelPath required", details: nil))
                return
            }
            let scale = (args["scale"] as? NSNumber)?.floatValue ?? 1.0
            Task { @MainActor in
                sceneState.models.append(FlutterModelData(path: modelPath, scale: scale))
            }
            result(nil)

        case "addGeometry":
            result(nil)

        case "addLight":
            result(nil)

        case "clearScene":
            Task { @MainActor in
                sceneState.models.removeAll()
                placement.removeAllPlaced()
            }
            result(nil)

        case "placeModel":
            guard let request = FlutterPlaceRequest(arguments: call.arguments) else {
                result(FlutterError(
                    code: "INVALID_ARGS",
                    message: "placeModel needs a hit and a model with a modelPath",
                    details: nil
                ))
                return
            }
            Task { @MainActor in
                do {
                    result(try await placement.placeModel(request))
                } catch let error as FlutterPlacementError {
                    result(error.flutterError)
                } catch {
                    result(FlutterError(code: "LOAD_FAILED", message: error.localizedDescription, details: nil))
                }
            }

        case "removePlacedModel":
            let id = (call.arguments as? [String: Any])?["id"] as? String
            Task { @MainActor in
                if let id { placement.removePlacedModel(id: id) }
                result(nil)
            }

        case "setPlaneTapEnabled":
            let enabled = ((call.arguments as? [String: Any])?["enabled"] as? NSNumber)?.boolValue ?? false
            Task { @MainActor in
                placement.planeTapEnabled = enabled
                result(nil)
            }

        case "setEnvironment":
            // AR scenes use camera feed; environment HDR affects lighting only.
            result(nil)

        case "startRecording":
            Task { @MainActor in
                do {
                    try await ensureRecorder().startRecording()
                    result(nil)
                } catch {
                    result(FlutterError(
                        code: "AR_RECORDER_START_FAILED",
                        message: error.localizedDescription,
                        details: nil
                    ))
                }
            }

        case "stopRecording":
            let outputPath = (call.arguments as? [String: Any])?["outputPath"] as? String
            Task { @MainActor in
                do {
                    let outputURL = outputPath.map { URL(fileURLWithPath: $0) }
                    let url = try await ensureRecorder().stopRecording(outputURL: outputURL)
                    result(url.path)
                } catch {
                    result(FlutterError(
                        code: "AR_RECORDER_STOP_FAILED",
                        message: error.localizedDescription,
                        details: nil
                    ))
                }
            }

        case "saveRecordingToPhotoLibrary":
            guard let movPath = (call.arguments as? [String: Any])?["movPath"] as? String else {
                result(FlutterError(code: "INVALID_ARGS", message: "movPath required", details: nil))
                return
            }
            Task { @MainActor in
                do {
                    try await ARRecorder.saveToPhotoLibrary(URL(fileURLWithPath: movPath))
                    result(nil)
                } catch {
                    result(FlutterError(
                        code: "AR_RECORDER_SAVE_FAILED",
                        message: error.localizedDescription,
                        details: nil
                    ))
                }
            }

        default:
            result(FlutterMethodNotImplemented)
        }
    }
}

/// SwiftUI wrapper for `SceneViewSwift.ARSceneView`, driven by observable state.
///
/// `ARSceneView` has no declarative content closure (issue #2065). Content is
/// placed in the real world by anchoring entities.
///
/// A plane tap does one of two things:
/// - When the Dart widget sets `onPlaneTap` or `placeOnTap` (#3780), the
///   placement controller hit-tests the tap itself and sends the hit to Dart,
///   which decides what to place.
/// - Otherwise, the older behaviour runs: `onTapOnPlane` drops a copy of the
///   most recently loaded model at the tapped position.
///
/// Exactly one of the two tap recognizers is installed at a time. Two plain
/// tap recognizers on one view never both fire, so a second recognizer would
/// swallow half the taps.
struct ARSceneViewSwiftUIWrapper: View {
    @ObservedObject var state: SceneState

    /// Drives async model loading and anchoring. The platform view owns it,
    /// so the Dart `placeModel` calls and this view share one instance.
    @ObservedObject var placement: ARPlacementController

    var body: some View {
        let legacyTap: ((SIMD3<Float>, ARView) -> Void)? = placement.planeTapEnabled
            ? nil
            : { position, _ in placement.placeModel(at: position) }
        ARSceneView(
            planeDetection: .horizontal,
            onTapOnPlane: legacyTap
        )
        // Capture the single reusable content anchor once the session starts,
        // so every tap-placed model and `clearScene` operate on the same
        // anchor instead of leaking a fresh one per tap (issue #2078).
        .onSessionStarted { arView in
            placement.attach(to: arView)
        }
        // Pre-load the requested models so a plane tap can anchor them
        // immediately. Keyed on the model-id list so loadModel / clearScene
        // from the Dart side re-run the loader (and clear placed models when
        // the list becomes empty).
        .task(id: state.models.map(\.id)) {
            await placement.sync(to: state.models)
        }
    }
}

/// Loads AR models off the method channel and places them on plane taps.
///
/// `ARSceneView` is anchor-driven — there is no "scene content list" to mutate
/// (unlike the 3D `SceneView`). This controller pre-loads each requested model
/// once and, on a plane tap, clones the most recently requested model into a
/// single reusable content `AnchorNode`.
///
/// Mirrors the React Native AR bridge (`RNARSceneViewContent`): all placed
/// content lives under one `contentAnchor` added to the scene at the world
/// origin. Tap-placed clones are positioned at the tapped world coordinate
/// relative to that anchor. `clearScene` (a `sync(to: [])`) calls
/// `removeAll()` on the anchor, so placed models are actually removed and no
/// per-tap `AnchorEntity` accumulates in the scene (issue #2078).
@MainActor
final class ARPlacementController: ObservableObject {
    /// Loaded model entities, keyed by `FlutterModelData.id`, kept as
    /// templates that are cloned for each placement.
    private var templates: [(data: FlutterModelData, entity: ModelEntity)] = []

    /// The single anchor that owns every tap-placed model. Created once when
    /// the AR session starts; its children are torn down by `clearScene`.
    private var contentAnchor: AnchorNode?

    /// Whether plane taps are hit-tested here and sent to Dart as
    /// `onPlaneTap` (#3780), instead of placing the last loaded model.
    /// Published so the wrapper swaps the SDK's own tap recognizer out.
    @Published var planeTapEnabled: Bool {
        didSet { syncPlaneTapRecognizer() }
    }

    /// Receives each plane hit, already encoded for the method channel.
    private let onPlaneTap: ([String: Any]) -> Void

    private weak var arView: ARView?
    private var planeTapRecognizer: UITapGestureRecognizer?
    /// Gesture recognizers hold their targets weakly; this keeps ours alive.
    private lazy var planeTapTarget = FlutterPlaneTapTarget { [weak self] recognizer in
        self?.handlePlaneTap(recognizer)
    }
    private var nextHitId = 0
    private var nextPlacedId = 0

    /// A model placed with `placeModel`, one ARKit anchor each.
    private struct PlacedModel {
        let anchorEntity: AnchorEntity
        let arAnchor: ARAnchor
        let recognizers: [UIGestureRecognizer]
    }
    private var placed: [String: PlacedModel] = [:]

    init(
        planeTapEnabled: Bool = false,
        onPlaneTap: @escaping ([String: Any]) -> Void = { _ in }
    ) {
        self.onPlaneTap = onPlaneTap
        self.planeTapEnabled = planeTapEnabled
    }

    /// Captures the reusable content anchor once the AR session has started.
    ///
    /// Adds one `AnchorNode` at the world origin to the scene — every placed
    /// model becomes its child, so the scene's anchor count stays at one
    /// regardless of how many models the user taps into the world.
    func attach(to arView: ARView) {
        self.arView = arView
        syncPlaneTapRecognizer()
        guard contentAnchor == nil else { return }
        let anchor = AnchorNode.world(position: .zero)
        arView.scene.addAnchor(anchor.entity)
        contentAnchor = anchor
    }

    // MARK: Tap-to-place (#3780)

    /// Installs the plane-tap recognizer only while `planeTapEnabled` is true.
    /// While it is off, the SDK's own `onTapOnPlane` recognizer handles taps.
    private func syncPlaneTapRecognizer() {
        if planeTapEnabled, planeTapRecognizer == nil, let arView {
            let recognizer = UITapGestureRecognizer(
                target: planeTapTarget,
                action: #selector(FlutterPlaneTapTarget.handle(_:))
            )
            arView.addGestureRecognizer(recognizer)
            planeTapRecognizer = recognizer
        } else if !planeTapEnabled, let recognizer = planeTapRecognizer {
            recognizer.view?.removeGestureRecognizer(recognizer)
            planeTapRecognizer = nil
        }
    }

    /// Hit-tests a tap against detected plane geometry and reports the hit.
    ///
    /// Only detected geometry counts, not an estimated plane. This matches
    /// Android, which only reports taps inside a tracked plane's polygon.
    private func handlePlaneTap(_ recognizer: UITapGestureRecognizer) {
        guard planeTapEnabled, recognizer.state == .ended, let arView else { return }
        let point = recognizer.location(in: arView)
        // A tap on a placed model belongs to that model (it is where a drag,
        // twist or pinch starts), not to the plane behind it.
        if let entity = arView.entity(at: point), isPlaced(entity) { return }
        guard let result = arView.raycast(
            from: point,
            allowing: .existingPlaneGeometry,
            alignment: .any
        ).first else { return }

        let hit = result.worldTransform
        let position = SIMD3<Float>(hit.columns.3.x, hit.columns.3.y, hit.columns.3.z)
        let planeAnchor = result.anchor as? ARPlaneAnchor
        let normalColumn = (planeAnchor?.transform ?? hit).columns.1
        let camera = arView.cameraTransform.translation
        let rotation = flutterPlaneHitRotation(
            normal: SIMD3(normalColumn.x, normalColumn.y, normalColumn.z),
            hitPosition: position,
            cameraPosition: camera
        )
        let id = "hit-\(nextHitId)"
        nextHitId += 1
        onPlaneTap(flutterPlaneHitMap(
            id: id,
            position: position,
            rotation: rotation,
            planeType: flutterPlaneTypeName(planeAnchor),
            distance: simd_distance(camera, position)
        ))
    }

    private func isPlaced(_ entity: Entity) -> Bool {
        var current: Entity? = entity
        while let node = current {
            if let anchor = node as? AnchorEntity,
               placed.values.contains(where: { $0.anchorEntity === anchor }) {
                return true
            }
            current = node.parent
        }
        return false
    }

    /// Loads `request.modelPath`, anchors it at the hit pose and installs the
    /// requested gestures. Returns the placed model's id.
    ///
    /// The entity tree is anchor → pivot → model:
    /// - the anchor is an `ARAnchor` at the hit pose, so ARKit keeps the model
    ///   in place as tracking refines;
    /// - the pivot sits on the contact point and carries the gestures and the
    ///   only collision shape;
    /// - the model is scaled to `size` metres and bottom-centred on the pivot.
    ///
    /// So twist and pinch act around the point where the model touches the
    /// surface, as on Android.
    func placeModel(_ request: FlutterPlaceRequest) async throws -> String {
        if let reason = flutterUnsupportedModelReason(request.modelPath) {
            throw FlutterPlacementError.unsupportedFormat(reason)
        }
        guard arView != nil else { throw FlutterPlacementError.notReady }

        let node: ModelNode
        do {
            node = try await flutterLoadModel(path: request.modelPath)
        } catch {
            throw FlutterPlacementError.loadFailed(error.localizedDescription)
        }
        // The view can go away while the model downloads.
        guard let arView else { throw FlutterPlacementError.notReady }

        node.scaleToUnits(request.size)
        node.centerOrigin(normalized: SIMD3<Float>(0, -1, 0))
        node.entity.name = sceneViewerModelFileName(request.modelPath)

        let pivot = ModelEntity()
        pivot.addChild(node.entity)
        // One collision shape, on the pivot. RealityKit's entity gestures
        // hit-test collision shapes, and a shape on a child would route the
        // touch to the child instead of the entity the gestures are on.
        Self.removeCollision(from: node.entity)
        let bounds = pivot.visualBounds(relativeTo: pivot)
        pivot.collision = CollisionComponent(shapes: [
            ShapeResource
                .generateBox(size: simd_max(bounds.extents, SIMD3<Float>(repeating: 0.01)))
                .offsetBy(translation: bounds.center)
        ])
        Self.applyGroundingShadow(to: pivot)

        let arAnchor = ARAnchor(
            name: "flutter_sceneview.placed",
            transform: flutterHitTransform(position: request.position, rotation: request.rotation)
        )
        arView.session.add(anchor: arAnchor)
        let anchorEntity = AnchorEntity(anchor: arAnchor)
        anchorEntity.addChild(pivot)
        arView.scene.addAnchor(anchorEntity)

        var gestures: ARView.EntityGestures = []
        if request.canDrag { gestures.insert(.translation) }
        if request.canRotate { gestures.insert(.rotation) }
        if request.canScale { gestures.insert(.scale) }
        let recognizers: [UIGestureRecognizer] = gestures.isEmpty
            ? []
            : arView.installGestures(gestures, for: pivot)
        for case let scale as EntityScaleGestureRecognizer in recognizers {
            // RealityKit's pinch has no limit; keep it within the same
            // 0.25x...4x of the placed size as Android.
            scale.addTarget(planeTapTarget, action: #selector(FlutterPlaneTapTarget.clampScale(_:)))
        }

        let id = "placed-\(nextPlacedId)"
        nextPlacedId += 1
        placed[id] = PlacedModel(anchorEntity: anchorEntity, arAnchor: arAnchor, recognizers: recognizers)
        return id
    }

    /// Removes a placed model, its gestures and its ARKit anchor. No-op for
    /// an unknown id.
    func removePlacedModel(id: String) {
        guard let model = placed.removeValue(forKey: id) else { return }
        tearDown(model)
    }

    /// Removes every model placed with `placeModel` (the Dart `clearScene`).
    func removeAllPlaced() {
        let all = placed.values
        placed.removeAll()
        all.forEach(tearDown)
    }

    private func tearDown(_ model: PlacedModel) {
        for recognizer in model.recognizers {
            recognizer.view?.removeGestureRecognizer(recognizer)
        }
        model.anchorEntity.removeFromParent()
        arView?.session.remove(anchor: model.arAnchor)
    }

    private static func removeCollision(from entity: Entity) {
        entity.components.remove(CollisionComponent.self)
        for child in entity.children {
            removeCollision(from: child)
        }
    }

    /// Same contact shadow the SDK gives models placed through `onTapOnPlane`.
    private static func applyGroundingShadow(to entity: Entity) {
        if entity.components.has(ModelComponent.self) {
            entity.components.set(GroundingShadowComponent(castsShadow: true))
        }
        for child in entity.children {
            applyGroundingShadow(to: child)
        }
    }

    /// Reconciles the loaded templates with the requested model list.
    ///
    /// When the model list becomes empty (the Dart `clearScene` path) the
    /// reusable content anchor is emptied so already-placed models are removed
    /// from the world — previously `clearScene` only dropped the load cache
    /// and left placed models on screen (issue #2078).
    func sync(to models: [FlutterModelData]) async {
        let desired = Set(models.map(\.id))
        templates.removeAll { !desired.contains($0.data.id) }

        // `clearScene` empties `state.models`; with no model to place, also
        // tear down everything already placed in the world.
        if models.isEmpty {
            contentAnchor?.removeAll()
        }

        let loadedIds = Set(templates.map(\.data.id))
        for data in models where !loadedIds.contains(data.id) {
            if let reason = flutterUnsupportedModelReason(data.path) {
                NSLog("[flutter_sceneview] Cannot load AR model '%@': %@", data.path, reason)
                continue
            }
            do {
                let node = try await flutterLoadModel(path: data.path)
                node.scale(data.scale)
                node.entity.name = sceneViewerModelFileName(data.path)
                templates.append((data, node.entity))
            } catch {
                NSLog(
                    "[flutter_sceneview] Failed to load AR model '%@': %@",
                    data.path,
                    error.localizedDescription
                )
            }
        }
    }

    /// Places a clone of the most recently requested model at `position`.
    ///
    /// The clone is added to the single reusable content anchor (positioned at
    /// the tapped world coordinate), so repeated taps do not grow the scene's
    /// anchor count. Cloning lets the same model be tapped onto multiple
    /// planes. Does nothing until a model has loaded and the session has
    /// provided the content anchor.
    func placeModel(at position: SIMD3<Float>) {
        guard let template = templates.last,
              let contentAnchor else { return }
        let clone = template.entity.clone(recursive: true)
        clone.position = position
        contentAnchor.add(clone)
    }
}

// MARK: - Tap-to-place support types (#3780)

/// Objective-C target for the plane-tap and pinch-clamp recognizers.
/// `UIGestureRecognizer` needs an `NSObject` target, which the
/// `ObservableObject` controller is not.
@MainActor
final class FlutterPlaneTapTarget: NSObject {
    private let onTap: (UITapGestureRecognizer) -> Void

    init(onTap: @escaping (UITapGestureRecognizer) -> Void) {
        self.onTap = onTap
    }

    @objc func handle(_ recognizer: UITapGestureRecognizer) {
        onTap(recognizer)
    }

    /// Runs after RealityKit's own pinch handler and clamps the uniform
    /// scale to `flutterPlacedScaleRange`. The pivot starts at scale 1, so
    /// the range is relative to the placed size.
    @objc func clampScale(_ recognizer: EntityScaleGestureRecognizer) {
        guard let entity = recognizer.entity else { return }
        let current = entity.scale.x
        let clamped = flutterClampPlacedScale(current)
        if clamped != current {
            entity.scale = SIMD3<Float>(repeating: clamped)
        }
    }
}

/// Why `placeModel` failed, mapped to the codes the Dart doc lists.
enum FlutterPlacementError: Error {
    case unsupportedFormat(String)
    case loadFailed(String)
    case notReady

    var flutterError: FlutterError {
        switch self {
        case .unsupportedFormat(let reason):
            return FlutterError(code: "UNSUPPORTED_FORMAT", message: reason, details: nil)
        case .loadFailed(let reason):
            return FlutterError(code: "LOAD_FAILED", message: reason, details: nil)
        case .notReady:
            return FlutterError(
                code: "NOT_READY",
                message: "The AR session has not started yet. Place models from onPlaneTap.",
                details: nil
            )
        }
    }
}

/// Maps an ARKit plane anchor to the plane type name Dart receives. The names
/// are the same as Android's `planeTypeName`.
func flutterPlaneTypeName(_ anchor: ARPlaneAnchor?) -> String {
    guard let anchor else { return "unknown" }
    if anchor.alignment == .vertical { return "vertical" }
    if ARPlaneAnchor.isClassificationSupported, anchor.classification == .ceiling {
        return "horizontal_downward"
    }
    return anchor.alignment == .horizontal ? "horizontal_upward" : "unknown"
}
