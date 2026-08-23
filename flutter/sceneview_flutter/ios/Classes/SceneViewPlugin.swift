import Flutter
import UIKit
import SwiftUI
import RealityKit
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
        self.hostingController = UIHostingController(
            rootView: ARSceneViewSwiftUIWrapper(state: sceneState)
        )
        self.hostingController.view.frame = frame
        self.hostingController.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]

        self.channel = FlutterMethodChannel(
            name: "io.github.sceneview.flutter/scene_\(viewId)",
            binaryMessenger: messenger
        )
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
            }
            result(nil)

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
/// `ARSceneView` has no declarative content closure (issue #2065) — content is
/// placed in the real world by anchoring entities. The bridge registers an
/// `onTapOnPlane` handler so a tap on a detected plane drops the most recently
/// requested model at that world position, mirroring the Android Flutter
/// demo's tap-to-place AR behaviour.
struct ARSceneViewSwiftUIWrapper: View {
    @ObservedObject var state: SceneState

    /// Drives async model loading and tap-to-place anchoring. `@StateObject`
    /// so the loaded-model cache survives SwiftUI re-renders.
    @StateObject private var placement = ARPlacementController()

    var body: some View {
        ARSceneView(
            planeDetection: .horizontal,
            onTapOnPlane: { position, _ in
                placement.placeModel(at: position)
            }
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

    /// Captures the reusable content anchor once the AR session has started.
    ///
    /// Adds one `AnchorNode` at the world origin to the scene — every placed
    /// model becomes its child, so the scene's anchor count stays at one
    /// regardless of how many models the user taps into the world.
    func attach(to arView: ARView) {
        guard contentAnchor == nil else { return }
        let anchor = AnchorNode.world(position: .zero)
        arView.scene.addAnchor(anchor.entity)
        contentAnchor = anchor
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
