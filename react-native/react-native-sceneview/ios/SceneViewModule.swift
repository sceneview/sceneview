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

/// Maps the wire string sent from JS to a `CameraControlMode` (v4.3.0).
/// Unknown values fall back to `.orbit`.
func rnCameraControlMode(_ raw: String?) -> CameraControlMode {
    switch raw {
    case "pan": return .pan
    case "firstPerson": return .firstPerson
    default: return .orbit
    }
}

/// Observable state model shared between React props and SwiftUI view.
@MainActor
class RNSceneState: ObservableObject {
    @Published var models: [RNModelData] = []
    @Published var environmentPath: String?
    @Published var cameraOrbit: Bool = true
    @Published var cameraControlMode: CameraControlMode = .orbit
    @Published var autoCenterContent: Bool = true

    /// Invoked from the SwiftUI content's `onEntityTapped` modifier so the
    /// wrapper can forward the tap to React Native's `onTap` prop (issue #2053).
    /// Not `@Published` — it is plumbing, not rendered state.
    var onTap: ((Entity) -> Void)?
}

/// UIView wrapper that hosts a SwiftUI `SceneView` via UIHostingController.
class RNSceneViewWrapper: UIView {

    private var hostingController: UIHostingController<RNSceneViewContent>?
    private let sceneState = RNSceneState()

    /// Event callback for tap events.
    @objc var onTap: RCTDirectEventBlock? {
        didSet {
            let block = onTap
            Task { @MainActor in
                sceneState.onTap = { entity in
                    // Mirrors the Android `TapEvent` payload: world-space
                    // coordinates of the tapped entity + its node name.
                    let p = entity.position(relativeTo: nil)
                    block?([
                        "x": p.x,
                        "y": p.y,
                        "z": p.z,
                        "nodeName": entity.name,
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
        let content = RNSceneViewContent(state: sceneState)
        let hosting = UIHostingController(rootView: content)
        hosting.view.frame = bounds
        hosting.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(hosting.view)
        hostingController = hosting
    }

    // MARK: - React props

    @objc var environment: String? {
        didSet {
            Task { @MainActor in
                sceneState.environmentPath = environment
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
            let mode = rnCameraControlMode(cameraControlMode)
            Task { @MainActor in
                sceneState.cameraControlMode = mode
            }
        }
    }

    /// Whether to auto-centre scene content (v4.3.0, issue #1053).
    @objc var autoCenterContent: Bool = true {
        didSet {
            Task { @MainActor in
                sceneState.autoCenterContent = autoCenterContent
            }
        }
    }
}

/// SwiftUI content view rendering `SceneViewSwift.SceneView` (issue #2067).
///
/// `SceneViewSwift` loads models **asynchronously** (`ModelNode.load(_:)` is
/// `async throws`) — there is no synchronous `ModelNode(String)` initialiser,
/// and the `@NodeBuilder` content builder composes static `EntityProvider`
/// values, not a SwiftUI `ForEach`. So the bridge loads each `RNModelData`
/// off the main actor's `Task`, wraps the resulting `ModelEntity`s in a
/// stable holder entity, and feeds that holder to `SceneView`'s **imperative**
/// `init(_ content: (Entity) -> Void)`.
///
/// `SceneView`'s content closure runs once when the underlying `RealityView`
/// is created, so the holder entity is built up front and re-populated in a
/// `.task(id:)` whenever the JS `modelNodes` prop changes — RealityKit picks
/// up the new children on its next render frame without recreating the view.
struct RNSceneViewContent: View {
    @ObservedObject var state: RNSceneState

    /// Stable parent entity handed to `SceneView`. Its children are the
    /// loaded model entities; rebuilt by `loadModels()` on every prop change.
    @State private var modelRoot = Entity()

    var body: some View {
        SceneView { root in
            root.addChild(modelRoot)
        }
        .cameraControls(state.cameraControlMode)
        .autoCenterContent(state.autoCenterContent)
        // Forward entity taps to React Native's `onTap` prop (issue #2053).
        .onEntityTapped { entity in
            state.onTap?(entity)
        }
        // Reload whenever the JS `modelNodes` prop changes. Keyed on the
        // model identities so an unrelated re-render does not re-download.
        .task(id: state.models.map(\.id)) {
            await loadModels()
        }
    }

    /// Loads every model in `state.models` and replaces `modelRoot`'s
    /// children with the freshly loaded entities. `ModelNode.load(_:)` is
    /// `@MainActor`-isolated, so this runs on the main actor as required by
    /// RealityKit. A failed load is skipped (the others still render).
    @MainActor
    private func loadModels() async {
        // Clear previous content before reloading.
        for child in modelRoot.children {
            child.removeFromParent()
        }
        for model in state.models {
            do {
                let node = try await ModelNode.load(model.path)
                // `.task(id:)` cancels this task when the `modelNodes` prop
                // changes mid-load. A cancelled task still resumes past the
                // `await`, so bail out before mutating the scene — otherwise a
                // superseded load leaks a stale model into `modelRoot`.
                guard !Task.isCancelled else { return }
                node.position(model.position)
                node.scale(model.scale)
                if let animation = model.animation {
                    node.playAnimation(named: animation)
                } else if node.animationCount > 0 {
                    node.playAllAnimations()
                }
                modelRoot.addChild(node.entity)
            } catch {
                // A single bad path must not break the whole scene.
                print("[RNSceneView] Failed to load model '\(model.path)': \(error)")
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
