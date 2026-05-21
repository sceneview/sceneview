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

/// SwiftUI content view rendering SceneViewSwift.SceneView.
struct RNSceneViewContent: View {
    @ObservedObject var state: RNSceneState

    var body: some View {
        SceneView {
            ForEach(state.models) { model in
                ModelNode(model.path)
                    .position(model.position)
                    .scale(model.scale)
            }
        }
        .cameraControls(state.cameraControlMode)
        .autoCenterContent(state.autoCenterContent)
        // Forward entity taps to React Native's `onTap` prop (issue #2053).
        .onEntityTapped { entity in
            state.onTap?(entity)
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

/// SwiftUI content view rendering SceneViewSwift.ARSceneView.
struct RNARSceneViewContent: View {
    @ObservedObject var state: RNARSceneState

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
        ) { anchor in
            ForEach(state.models) { model in
                ModelNode(model.path)
                    .scale(model.scale)
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

    override static func requiresMainQueueSetup() -> Bool {
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
