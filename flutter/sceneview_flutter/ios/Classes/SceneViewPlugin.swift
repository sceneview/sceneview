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
        registrar.register(
            SceneViewFactory(messenger: registrar.messenger()),
            withId: "io.github.sceneview.flutter/sceneview"
        )
        registrar.register(
            ARSceneViewFactory(messenger: registrar.messenger()),
            withId: "io.github.sceneview.flutter/arsceneview"
        )
    }
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

// MARK: - Model naming

/// The model file's name, used to name a loaded model root.
///
/// Query and fragment are stripped before taking the last path component: a
/// model source may be a URL, and `deletingPathExtension` on a raw URL only
/// removes the extension when it is the last dot in the whole string —
/// `https://cdn/robot.glb?sig=SIG&v=1.2` would otherwise report
/// `robot.glb?sig=SIG&v=1` as the tapped node's name. RealityKit's
/// `ModelNode.load(_ path:)` is bundle-only today, so this is defensive; it
/// keeps the derivation aligned with the Android bridge, which DOES take
/// remote URLs (`ModelLoader.loadModel`).
///
/// Two known divergences, both unreachable for the `.glb` / `.gltf` / `.usdz`
/// sources this bridge loads:
/// - `NSString` path semantics vs Kotlin's `substringAfterLast('/')` /
///   `substringBeforeLast('.')` differ on a trailing slash (`models/` →
///   Kotlin `""`, Swift `models`) and on a dotfile base name (`.hidden` →
///   Kotlin `""`, Swift `.hidden`).
/// - Android's `tapNodeName` falls back to `node_<index>` when the derived
///   base name is empty; this side has no fallback and names the entity `""`,
///   which a tap then reports as `""` — the same value `flutterTappedNodeName`
///   returns for a tap that hit no bridge-loaded model at all.
func flutterModelFileName(_ path: String) -> String {
    let stripped = path.prefix { $0 != "?" && $0 != "#" }
    return (String(stripped) as NSString).lastPathComponent
}

// MARK: - Content root

/// Holds the persistent RealityKit content root for a Flutter platform view.
///
/// `SceneViewSwift.SceneView`'s content closure runs only once during scene
/// setup, so it cannot react to models added later over the method channel.
/// This holder gives the bridge a stable `Entity` that is handed to `SceneView`
/// at construction and then mutated imperatively (`addChild` / `removeFromParent`)
/// as `SceneState.models` changes — the same pattern the SceneViewSwift docs
/// recommend for async-loaded models.
@MainActor
final class FlutterContentRoot {
    /// The entity passed to `SceneView { root in root.addChild(content) }`.
    let entity = Entity()

    /// Loaded model entities, keyed by the `FlutterModelData.id` that produced
    /// them, so a `clearScene` / model removal can detach exactly the right
    /// entity without disturbing models that are still present.
    private var loaded: [UUID: Entity] = [:]

    /// Reconciles the attached model entities with the desired model list.
    ///
    /// Loads any model in `models` that is not yet attached and detaches any
    /// previously-loaded model no longer present (covers both `loadModel` and
    /// `clearScene` from the Dart side). `ModelNode.load(_:)` is `async` and
    /// `@MainActor` — there is no synchronous path/`String` initialiser on
    /// `ModelNode` (issue #2065), so each load is awaited in turn.
    ///
    /// RealityKit only loads `.usdz` / `.reality` natively. A `.glb` path
    /// throws; the failure is logged rather than thrown so one bad asset does
    /// not block the rest of the scene.
    func sync(to models: [FlutterModelData]) async {
        let desired = Set(models.map(\.id))

        // Detach models that were removed. Snapshot the keys first — mutating
        // the dictionary while iterating its `keys` view is undefined.
        for id in Array(loaded.keys) where !desired.contains(id) {
            loaded.removeValue(forKey: id)?.removeFromParent()
        }

        // Load and attach models not yet present.
        for data in models where loaded[data.id] == nil {
            do {
                let node = try await ModelNode.load(data.path)
                node.scale(data.scale)
                // The Dart side may have cleared the model between the
                // `await` suspension and resumption — only attach if it is
                // still wanted.
                guard loaded[data.id] == nil else { continue }
                // Name the model root after its file: `flutterTappedNodeName`
                // walks a tapped entity up to this entity (the direct child of
                // `entity`) and reports its base name without extension, which
                // is what Android reports for a tap on that model.
                node.entity.name = flutterModelFileName(data.path)
                loaded[data.id] = node.entity
                entity.addChild(node.entity)
            } catch {
                NSLog(
                    "[flutter_sceneview] Failed to load model '%@': %@",
                    data.path,
                    error.localizedDescription
                )
            }
        }
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

/// Maps the wire name sent from Dart to a `CameraControlMode`.
/// Unknown values fall back to `.orbit`.
func flutterCameraControlMode(_ raw: String?) -> CameraControlMode {
    switch raw {
    case "pan": return .pan
    case "firstPerson": return .firstPerson
    default: return .orbit
    }
}

/// Observable model holding scene state, updated via method channel.
@MainActor
class SceneState: ObservableObject {
    @Published var models: [FlutterModelData] = []
    @Published var environmentPath: String?
    @Published var cameraControlMode: CameraControlMode = .orbit
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

class SceneViewPlatformView: NSObject, FlutterPlatformView {
    private let hostingController: UIHostingController<SceneViewSwiftUIWrapper>
    private let channel: FlutterMethodChannel
    private let sceneState = SceneState()

    init(frame: CGRect, viewId: Int64, args: [String: Any], messenger: FlutterBinaryMessenger) {
        self.channel = FlutterMethodChannel(
            name: "io.github.sceneview.flutter/scene_\(viewId)",
            binaryMessenger: messenger
        )

        // Capture the channel weakly so the tap closure forwarded into the
        // SwiftUI wrapper does not extend the platform view's lifetime.
        let channel = self.channel
        self.hostingController = UIHostingController(
            rootView: SceneViewSwiftUIWrapper(
                state: sceneState,
                onTap: { [weak channel] nodeName in
                    channel?.invokeMethod("onTap", arguments: nodeName)
                }
            )
        )
        self.hostingController.view.frame = frame
        self.hostingController.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]

        super.init()

        // Apply v4.3.0 creation params (camera mode + auto-centre).
        let mode = flutterCameraControlMode(args["cameraControlMode"] as? String)
        let autoCenter = (args["autoCenterContent"] as? NSNumber)?.boolValue ?? true
        Task { @MainActor in
            sceneState.cameraControlMode = mode
            sceneState.autoCenterContent = autoCenter
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
            }
            result(nil)

        case "setEnvironment":
            let hdrPath = (call.arguments as? [String: Any])?["hdrPath"] as? String
            Task { @MainActor in
                sceneState.environmentPath = hdrPath
            }
            result(nil)

        case "setCameraControlMode":
            let raw = (call.arguments as? [String: Any])?["mode"] as? String
            let mode = flutterCameraControlMode(raw)
            Task { @MainActor in
                sceneState.cameraControlMode = mode
            }
            result(nil)

        case "setAutoCenterContent":
            let enabled = ((call.arguments as? [String: Any])?["enabled"] as? NSNumber)?.boolValue ?? true
            Task { @MainActor in
                sceneState.autoCenterContent = enabled
            }
            result(nil)

        default:
            result(FlutterMethodNotImplemented)
        }
    }
}

/// Derives the node name reported to Flutter for a tapped entity, matching the
/// Android bridge convention: the model file's base name without extension.
///
/// Android reports that base name for *every* tap on a model — its `onTouch`
/// closure captures `model.path`, and the only collider a loaded model owns is
/// the `ModelNode` root (glTF child renderables get no collision shape), so a
/// tap can never resolve to asset-internal geometry there.
///
/// `SpatialTapGesture` on the other hand reports the deepest hit entity, and
/// USDZ assets commonly name their meshes: walking to the first *named*
/// ancestor stopped inside the asset and reported `skin0` for a tap on
/// `black_dragon.usdz`. The walk therefore climbs to the model root — the
/// direct child of `contentRoot`, which is the only entity this bridge names
/// (`<file>.usdz`, set in `FlutterContentRoot.sync(to:)`) — so the payload is
/// the model's identity, never a sub-mesh.
///
/// Returns the empty string when the tapped entity is not part of a
/// bridge-loaded model (no ancestor is a child of `contentRoot`).
func flutterTappedNodeName(_ entity: Entity, contentRoot: Entity) -> String {
    var node: Entity? = entity
    while let current = node, current !== contentRoot {
        if current.parent === contentRoot {
            return (current.name as NSString).deletingPathExtension
        }
        node = current.parent
    }
    return ""
}

/// SwiftUI wrapper for `SceneViewSwift.SceneView`, driven by observable state.
///
/// `SceneView`'s `@NodeBuilder` initialiser composes a *static* `[Entity]` list
/// at construction (it has no SwiftUI `ForEach` support — issue #2065), and its
/// imperative `SceneView { (Entity) -> Void }` content closure runs only once.
/// To support models streamed in over the Flutter method channel, the bridge
/// hands `SceneView` a persistent content-root entity and then attaches loaded
/// model entities to it imperatively as `state.models` changes.
struct SceneViewSwiftUIWrapper: View {
    @ObservedObject var state: SceneState

    /// Persistent content root attached to the scene once and mutated as
    /// models load. `@StateObject` so the same `Entity` survives every
    /// SwiftUI re-render — re-creating it would orphan already-loaded models.
    @StateObject private var contentBox = FlutterContentRootBox()

    /// Forwards a model tap to the Flutter method channel as `onTap`.
    let onTap: (String) -> Void

    var body: some View {
        SceneView { root in
            // Runs once during scene setup. Attach the persistent content
            // root so later async model loads (added as its children) appear
            // without re-running this closure.
            root.addChild(contentBox.root.entity)
        }
        .cameraControls(state.cameraControlMode)
        .autoCenterContent(state.autoCenterContent)
        .onEntityTapped { entity in
            // Wire SceneViewSwift's entity hit-test to the Flutter channel so
            // the Dart `onTap` callback fires on iOS, matching Android (#2051).
            // The hit entity is resolved against the content root so the name
            // reported is the model's, not an asset-internal mesh's.
            onTap(flutterTappedNodeName(entity, contentRoot: contentBox.root.entity))
        }
        // Re-sync the loaded model entities whenever the Dart side mutates
        // `state.models` (loadModel / clearScene). `ModelNode.load` is async,
        // so the diff runs in a task keyed on the model-id list.
        .task(id: state.models.map(\.id)) {
            await contentBox.root.sync(to: state.models)
        }
    }
}

/// `ObservableObject` box so a `FlutterContentRoot` (which is `@MainActor`)
/// can be held by `@StateObject` with a stable identity across re-renders.
@MainActor
final class FlutterContentRootBox: ObservableObject {
    let root = FlutterContentRoot()
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
            do {
                let node = try await ModelNode.load(data.path)
                node.scale(data.scale)
                node.entity.name = flutterModelFileName(data.path)
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
