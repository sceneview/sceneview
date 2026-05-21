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
/// Walks up the entity tree past anonymous mesh children to the first named
/// ancestor, since `SpatialTapGesture` may report a deep child whose `name`
/// is empty. Falls back to the empty string when no name is available.
func flutterTappedNodeName(_ entity: Entity) -> String {
    var node: Entity? = entity
    while let current = node {
        let stem = (current.name as NSString).deletingPathExtension
        if !stem.isEmpty { return stem }
        node = current.parent
    }
    return ""
}

/// SwiftUI wrapper for SceneViewSwift.SceneView, driven by observable state.
struct SceneViewSwiftUIWrapper: View {
    @ObservedObject var state: SceneState

    /// Forwards a model tap to the Flutter method channel as `onTap`.
    let onTap: (String) -> Void

    var body: some View {
        SceneView {
            ForEach(state.models) { model in
                ModelNode(model.path)
                    .scale(model.scale)
            }
        }
        .cameraControls(state.cameraControlMode)
        .autoCenterContent(state.autoCenterContent)
        .onEntityTapped { entity in
            // Wire SceneViewSwift's entity hit-test to the Flutter channel so
            // the Dart `onTap` callback fires on iOS, matching Android (#2051).
            onTap(flutterTappedNodeName(entity))
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

/// SwiftUI wrapper for SceneViewSwift.ARSceneView, driven by observable state.
struct ARSceneViewSwiftUIWrapper: View {
    @ObservedObject var state: SceneState

    var body: some View {
        ARSceneView { anchor in
            ForEach(state.models) { model in
                ModelNode(model.path)
                    .scale(model.scale)
            }
        }
    }
}
