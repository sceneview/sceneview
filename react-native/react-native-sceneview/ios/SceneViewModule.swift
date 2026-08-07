import Foundation
import React
import RealityKit
import SceneViewSwift
import SwiftUI

// MARK: - Shared model data

struct RNModelData: Identifiable, Equatable {
    let id = UUID()
    let path: String
    let scale: SIMD3<Float>
    let position: SIMD3<Float>
    let animation: String?

    static func == (lhs: RNModelData, rhs: RNModelData) -> Bool {
        lhs.id == rhs.id
    }
}

// MARK: - SceneView (3D)

/// RCTViewManager subclass that bridges React Native's `<RNSceneView>`
/// to SceneViewSwift's `SceneView` (RealityKit-based).
@objc(RNSceneViewManager)
class RNSceneViewManager: RCTViewManager {

    override func view() -> UIView! {
        return RNSceneViewWrapper()
    }

    override static func requiresMainQueueSetup() -> Bool {
        return true
    }
}

/// State model backing the React props.
///
/// Still an `ObservableObject` for shape consistency with `RNARSceneState`, which
/// genuinely drives SwiftUI; nothing observes this one since the 3D scene moved
/// onto `SceneViewerHostView`.
@MainActor
class RNSceneState: ObservableObject {
    @Published var models: [RNModelData] = []
    @Published var environmentPath: String?

    /// Accepted, stored, and deliberately not applied — as before this bridge
    /// moved onto the shared host.
    ///
    /// `cameraControlMode` supersedes it and the two would contradict each other
    /// (which wins for `cameraOrbit: false, cameraControlMode: "orbit"`?). Wiring
    /// it up here would be a behaviour change dressed as a refactor, so it stays
    /// inert; the TypeScript surface deprecates it.
    @Published var cameraOrbit: Bool = true

    /// Wire name as it arrives from JS: `"orbit"`, `"pan"` or `"firstPerson"`.
    /// `SceneViewerConfiguration` normalises an unrecognised value to `"orbit"`,
    /// which is what the local mapping this replaces did.
    @Published var cameraControlMode: String = "orbit"

    @Published var autoCenterContent: Bool = true
}

/// UIView wrapper hosting the shared `SceneViewerHostView`.
///
/// This class owns the React prop bag; the scene itself — hosting SwiftUI in
/// UIKit, loading models, reconciling them — belongs to `SceneViewerHostView`
/// and is shared with the Flutter plugin and `sceneview-compose`. The AR wrapper
/// below keeps its own SwiftUI content: `ARSceneView` is anchor-driven and
/// shares nothing with the 3D viewer.
class RNSceneViewWrapper: UIView {

    private let hostView = SceneViewerHostView()
    private let sceneState = RNSceneState()

    /// Event callback for tap events.
    @objc var onTap: RCTDirectEventBlock? {
        didSet {
            let block = onTap
            Task { @MainActor in
                // `onTapModel` rather than the host's flattened `onTap`: this
                // prop reports the tapped model's own origin and its name,
                // where the flattened callback carries the hit's bounds centre
                // and no name at all.
                hostView.onTapModel = { hit in
                    // Mirrors the Android `TapEvent` payload: world-space
                    // coordinates of the tapped model + its node name. Android
                    // reports the `ModelNode` root — the only collider a loaded
                    // model owns — and `null` for a tap that hit no node, so
                    // `nil` here maps to the same `nodeName: null`.
                    //
                    // An empty name maps to `null` too: the host leaves a model
                    // it could derive no name for (a `bytes` source has no file
                    // name) with whatever RealityKit called it, which is `""`
                    // for most assets. `""` would read in JS as a model named
                    // the empty string rather than as "no name".
                    let p = hit?.entity.position(relativeTo: nil) ?? SIMD3<Float>.zero
                    let name = hit?.entity.name ?? ""
                    block?([
                        "x": p.x,
                        "y": p.y,
                        "z": p.z,
                        "nodeName": name.isEmpty ? NSNull() : name,
                    ])
                }
            }
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupView()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupView()
    }

    private func setupView() {
        hostView.frame = bounds
        hostView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(hostView)
    }

    /// Pushes the current props into the host view.
    ///
    /// Called after every prop change. `applyConfiguration` compares field by
    /// field and touches only what changed, so re-sending everything on each
    /// prop update costs nothing.
    @MainActor
    private func applyState() {
        let configuration = SceneViewerConfiguration()

        configuration.models = sceneState.models.map { data in
            let model = SceneViewerModel()
            model.assetPath = data.path
            // The per-entry id minted when the prop was decoded. A new
            // `modelNodes` value mints new ids, which is what gives this prop
            // its replace-the-whole-list semantics.
            model.identity = data.id.uuidString
            model.setScale(data.scale.x, data.scale.y, data.scale.z)
            model.setPosition(data.position.x, data.position.y, data.position.z)
            model.animationName = data.animation
            // No named animation means play them all, which is this bridge's
            // behaviour and not the Flutter one's.
            model.autoPlayAllAnimations = true
            // No `nodeName`: the host derives it from the source, which is what
            // makes the `onTap` payload the model file's base name on both
            // platforms rather than whatever RealityKit named the hit entity.
            return model
        }

        configuration.cameraControlMode = sceneState.cameraControlMode
        configuration.autoCenterContent = sceneState.autoCenterContent
        // This bridge exposes no camera prop, so it authors no pose: without
        // this, every prop change would re-assert the configuration's default
        // pose and snap the camera back out of the auto-centre framing and away
        // from wherever the user had orbited to.
        configuration.cameraPoseAuthored = false

        if let path = sceneState.environmentPath, !path.isEmpty {
            configuration.environmentKind = "hdr"
            configuration.environmentHdrPath = path
        }

        hostView.applyConfiguration(configuration)
    }

    // MARK: - React props

    @objc var environment: String? {
        didSet {
            Task { @MainActor in
                sceneState.environmentPath = environment
                applyState()
            }
        }
    }

    @objc var modelNodes: [[String: Any]]? {
        didSet {
            Task { @MainActor in
                sceneState.models = modelNodes?.compactMap { dict -> RNModelData? in
                    guard let src = dict["src"] as? String else { return nil }
                    let scale: SIMD3<Float>
                    if let arr = dict["scale"] as? [NSNumber], arr.count >= 3 {
                        scale = SIMD3(arr[0].floatValue, arr[1].floatValue, arr[2].floatValue)
                    } else if let s = (dict["scale"] as? NSNumber)?.floatValue {
                        scale = SIMD3(repeating: s)
                    } else {
                        scale = SIMD3(repeating: 1.0)
                    }
                    let position: SIMD3<Float>
                    if let arr = dict["position"] as? [NSNumber], arr.count >= 3 {
                        position = SIMD3(arr[0].floatValue, arr[1].floatValue, arr[2].floatValue)
                    } else {
                        position = .zero
                    }
                    let animation = dict["animation"] as? String
                    return RNModelData(path: src, scale: scale, position: position, animation: animation)
                } ?? []
                applyState()
            }
        }
    }

    /// **Deprecated.** Superseded by `cameraControlMode`; see `RNSceneState.cameraOrbit`.
    @objc var cameraOrbit: Bool = true {
        didSet {
            Task { @MainActor in
                sceneState.cameraOrbit = cameraOrbit
            }
        }
    }

    /// Camera interaction mode (v4.3.0, issue #1053).
    @objc var cameraControlMode: String? {
        didSet {
            let raw = cameraControlMode
            Task { @MainActor in
                sceneState.cameraControlMode = raw ?? "orbit"
                applyState()
            }
        }
    }

    /// Whether to auto-centre scene content (v4.3.0, issue #1053).
    @objc var autoCenterContent: Bool = true {
        didSet {
            Task { @MainActor in
                sceneState.autoCenterContent = autoCenterContent
                applyState()
            }
        }
    }
}

// MARK: - ARSceneView

/// RCTViewManager subclass that bridges React Native's `<RNARSceneView>`
/// to SceneViewSwift's `ARSceneView` (ARKit + RealityKit).
@objc(RNARSceneViewManager)
class RNARSceneViewManager: RCTViewManager {

    override func view() -> UIView! {
        return RNARSceneViewWrapper()
    }

    override static func requiresMainQueueSetup() -> Bool {
        return true
    }
}

/// Observable state for AR scene configuration.
@MainActor
class RNARSceneState: ObservableObject {
    @Published var models: [RNModelData] = []
    @Published var planeDetection: Bool = true
    @Published var depthOcclusion: Bool = false
    @Published var instantPlacement: Bool = false

    /// Invoked from `ARSceneView`'s `onTapOnPlane` so the wrapper can forward
    /// the tap to React Native's `onTap` prop (issue #2053). Not `@Published` —
    /// it is plumbing, not rendered state.
    var onTap: ((SIMD3<Float>) -> Void)?
}

/// UIView wrapper that hosts a SwiftUI `ARSceneView` via UIHostingController.
class RNARSceneViewWrapper: UIView {

    private var hostingController: UIHostingController<RNARSceneViewContent>?
    private let sceneState = RNARSceneState()

    /// Event callback for tap events.
    @objc var onTap: RCTDirectEventBlock? {
        didSet {
            let block = onTap
            Task { @MainActor in
                sceneState.onTap = { worldPosition in
                    // Mirrors the Android `TapEvent` payload. `ARSceneView`'s
                    // `onTapOnPlane` reports the tapped surface point, not a
                    // node, so `nodeName` is left absent.
                    block?([
                        "x": worldPosition.x,
                        "y": worldPosition.y,
                        "z": worldPosition.z,
                    ])
                }
            }
        }
    }

    /// Event callback for plane detection events.
    ///
    /// **iOS limitation (issue #2053):** SceneViewSwift's `ARSceneView` does
    /// not expose a public per-plane-detected callback — only `onTapOnPlane`.
    /// The block is accepted for API compatibility but is not yet invoked on
    /// iOS; the TypeScript doc comment for `onPlaneDetected` discloses this.
    @objc var onPlaneDetected: RCTDirectEventBlock?

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupView()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupView()
    }

    private func setupView() {
        let content = RNARSceneViewContent(state: sceneState)
        let hosting = UIHostingController(rootView: content)
        hosting.view.frame = bounds
        hosting.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(hosting.view)
        hostingController = hosting
    }

    // MARK: - React props

    @objc var planeDetection: Bool = true {
        didSet {
            Task { @MainActor in
                sceneState.planeDetection = planeDetection
            }
        }
    }

    @objc var depthOcclusion: Bool = false {
        didSet {
            Task { @MainActor in
                sceneState.depthOcclusion = depthOcclusion
            }
        }
    }

    @objc var instantPlacement: Bool = false {
        didSet {
            Task { @MainActor in
                sceneState.instantPlacement = instantPlacement
            }
        }
    }

    @objc var modelNodes: [[String: Any]]? {
        didSet {
            Task { @MainActor in
                sceneState.models = modelNodes?.compactMap { dict -> RNModelData? in
                    guard let src = dict["src"] as? String else { return nil }
                    let scale: SIMD3<Float>
                    if let arr = dict["scale"] as? [NSNumber], arr.count >= 3 {
                        scale = SIMD3(arr[0].floatValue, arr[1].floatValue, arr[2].floatValue)
                    } else if let s = (dict["scale"] as? NSNumber)?.floatValue {
                        scale = SIMD3(repeating: s)
                    } else {
                        scale = SIMD3(repeating: 1.0)
                    }
                    let position: SIMD3<Float>
                    if let arr = dict["position"] as? [NSNumber], arr.count >= 3 {
                        position = SIMD3(arr[0].floatValue, arr[1].floatValue, arr[2].floatValue)
                    } else {
                        position = .zero
                    }
                    let animation = dict["animation"] as? String
                    return RNModelData(path: src, scale: scale, position: position, animation: animation)
                } ?? []
            }
        }
    }

    @objc var environment: String? {
        didSet {
            // AR scenes use camera feed; environment affects lighting only.
        }
    }
}

/// SwiftUI content view rendering `SceneViewSwift.ARSceneView` (issue #2067).
///
/// `SceneViewSwift.ARSceneView` is a `UIViewRepresentable` with **no content
/// builder closure** — content is added imperatively to the underlying
/// `ARView` once the session starts (`onSessionStarted`) or on tap
/// (`onTapOnPlane`). The previous `ARSceneView(...) { anchor in ForEach … }`
/// trailing closure referenced API that does not exist and never compiled.
///
/// This bridge captures the `ARView` in `onSessionStarted`, then loads each
/// `RNModelData` (async, via `ModelNode.load(_:)`) into a single
/// `AnchorNode` anchored at the world origin. The models are (re)placed in a
/// `.task(id:)` keyed on the JS `modelNodes` prop so prop changes are honoured
/// after the session has already started.
struct RNARSceneViewContent: View {
    @ObservedObject var state: RNARSceneState

    /// Captured once the AR session starts so prop-driven model reloads can
    /// add / remove content after `makeUIView`. Held in a reference box so a
    /// SwiftUI body re-evaluation does not lose it.
    @State private var sessionBox = ARSessionBox()

    /// Reference holder for the captured `ARView` + content anchor. A class
    /// keeps the references stable across `RNARSceneViewContent` value copies.
    @MainActor
    final class ARSessionBox {
        weak var arView: ARView?
        /// Anchor that owns every placed model. Added to the scene the first
        /// time the session starts; its children are rebuilt on prop changes.
        var contentAnchor: AnchorNode?
    }

    var body: some View {
        // Forward surface taps to React Native's `onTap` prop (issue #2053).
        // `depthOcclusion` / `instantPlacement` are accepted as props but have
        // no `ARSceneView` configuration knob in SceneViewSwift yet — the
        // TypeScript doc comments disclose that iOS gap (issue #2055).
        ARSceneView(
            planeDetection: state.planeDetection ? .both : .none,
            onTapOnPlane: { worldPosition, _ in
                state.onTap?(worldPosition)
            }
        )
        .onSessionStarted { arView in
            sessionBox.arView = arView
            let anchor = AnchorNode.world(position: .zero)
            arView.scene.addAnchor(anchor.entity)
            sessionBox.contentAnchor = anchor
        }
        // (Re)load models whenever the JS `modelNodes` prop changes — runs
        // after `onSessionStarted` too, so the initial set is placed once the
        // session is up.
        .task(id: state.models.map(\.id)) {
            await placeModels()
        }
    }

    /// Loads every model in `state.models` and replaces the content anchor's
    /// children with the freshly loaded entities. Waits until the AR session
    /// has provided a content anchor before placing anything.
    @MainActor
    private func placeModels() async {
        // The session may not have started yet on the first invocation —
        // poll briefly so the initial model set still lands.
        var anchor = sessionBox.contentAnchor
        var waited = 0
        while anchor == nil && waited < 50 {           // up to ~5 s
            try? await Task.sleep(nanoseconds: 100_000_000)
            waited += 1
            anchor = sessionBox.contentAnchor
        }
        guard let anchor else { return }
        // The poll above `await`s; if the `modelNodes` prop changed while we
        // waited, this task was superseded — do not clear/repopulate the anchor.
        guard !Task.isCancelled else { return }
        anchor.removeAll()
        for model in state.models {
            do {
                let node = try await ModelNode.load(model.path)
                // `.task(id:)` cancels this task when the `modelNodes` prop
                // changes mid-load. A cancelled task still resumes past the
                // `await`, so bail out before mutating the scene — otherwise a
                // superseded load leaks a stale model into the content anchor.
                guard !Task.isCancelled else { return }
                node.position(model.position)
                node.scale(model.scale)
                if let animation = model.animation {
                    node.playAnimation(named: animation)
                } else if node.animationCount > 0 {
                    node.playAllAnimations()
                }
                anchor.add(node.entity)
            } catch {
                print("[RNARSceneView] Failed to load model '\(model.path)': \(error)")
            }
        }
    }
}

// MARK: - AR Recorder native module (v4.3.0, issue #1053)

/// React Native bridge for SceneViewSwift's `ARRecorder` — record-only
/// AR session capture via ReplayKit. Exposed to JS as `NativeModules.RNARRecorder`.
///
/// iOS-only; the JS `ARRecorder` class guards non-iOS platforms before
/// calling into this module.
@objc(RNARRecorder)
class RNARRecorder: NSObject {

    /// A single recorder instance shared by the JS `ARRecorder` API —
    /// ReplayKit's `RPScreenRecorder` is itself a process-wide singleton,
    /// so multiple JS instances still drive one underlying recorder.
    @MainActor private lazy var recorder = ARRecorder()

    /// `RNARRecorder` extends `NSObject` directly (it is registered as an
    /// `RCT_EXTERN_MODULE`, not a view manager), so this is **not** an
    /// `override` — `NSObject` has no `requiresMainQueueSetup`. React Native
    /// reads the static method via the bridge-module protocol. (#2067)
    @objc static func requiresMainQueueSetup() -> Bool {
        return true
    }

    @objc func start(
        _ resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        Task { @MainActor in
            do {
                try await recorder.startRecording()
                resolve(nil)
            } catch {
                reject("AR_RECORDER_START_FAILED", error.localizedDescription, error)
            }
        }
    }

    @objc func stop(
        _ outputPath: String?,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        Task { @MainActor in
            do {
                let outputURL = outputPath.map { URL(fileURLWithPath: $0) }
                let url = try await recorder.stopRecording(outputURL: outputURL)
                resolve(url.path)
            } catch {
                reject("AR_RECORDER_STOP_FAILED", error.localizedDescription, error)
            }
        }
    }

    @objc func saveToPhotoLibrary(
        _ movPath: String,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        Task { @MainActor in
            do {
                try await ARRecorder.saveToPhotoLibrary(URL(fileURLWithPath: movPath))
                resolve(nil)
            } catch {
                reject("AR_RECORDER_SAVE_FAILED", error.localizedDescription, error)
            }
        }
    }
}
