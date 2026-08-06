#if canImport(UIKit) && (os(iOS) || os(visionOS))
import UIKit
import SwiftUI
import RealityKit
import Foundation
import simd

/// A flat, `@objc`-visible description of what to render in a ``SceneViewerHostView``.
///
/// Every member is a primitive on purpose. This type is what a cross-language bridge
/// copies its own model into field by field — Kotlin/Native's `SceneViewerSpec`, a
/// Flutter method-channel argument map, a React Native prop bag — and none of those can
/// carry a Swift enum, a `SIMD3`, or an `Optional<Float>` across. Vectors are three
/// separate scalars for the same reason.
///
/// **Angles are in degrees**, matching the Kotlin `CameraState` this mirrors;
/// ``SceneViewerHostView`` converts to radians on the way in and back to degrees on the
/// way out.
@objc(SVSceneViewerConfiguration)
public final class SceneViewerConfiguration: NSObject {

    // MARK: Model — at most one of the three is set

    /// Bundle resource path, e.g. `"models/helmet.usdz"`.
    ///
    /// **RealityKit loads `.usdz` and `.reality` only.** A glTF / GLB path — which is
    /// what the same code loads on Android — throws, and the failure is logged rather
    /// than rendered: the viewport keeps showing the environment, exactly as it looks
    /// while a load is still in flight. Convert with `tools/convert-usdz.sh`.
    @objc public var modelAssetPath: String?

    /// Absolute `http` / `https` URL of a `.usdz` / `.reality` file.
    @objc public var modelURLString: String?

    /// Model bytes already in memory. Must be a self-contained `.usdz` / `.reality`.
    @objc public var modelBytes: Data?

    /// Extension used for the temporary file ``modelBytes`` is written to.
    ///
    /// RealityKit dispatches on the file extension, so bytes with the wrong one fail to
    /// load however valid they are. Default `"usdz"`.
    @objc public var modelBytesFileExtension: String = "usdz"

    // MARK: Camera — angles in DEGREES

    @objc public var cameraTargetX: Float = 0
    @objc public var cameraTargetY: Float = 0
    @objc public var cameraTargetZ: Float = 0
    @objc public var cameraDistance: Float = 4
    @objc public var cameraAzimuthDegrees: Float = 0
    @objc public var cameraElevationDegrees: Float = 15

    /// Whether drag-to-orbit and pinch-to-zoom respond. Default `true`.
    @objc public var cameraGesturesEnabled: Bool = true

    // MARK: Lighting

    /// Direction the key light *travels*, in world space.
    @objc public var lightDirectionX: Float = 0.3
    @objc public var lightDirectionY: Float = -1
    @objc public var lightDirectionZ: Float = -0.5

    /// Key-light intensity in lux.
    @objc public var lightIntensity: Float = 100_000

    /// Multiplier on the image-based light.
    ///
    /// Applies only when ``environmentKind`` is `"hdr"`: that is the only case with an
    /// authored IBL to scale. With `"default"` or `"color"` the scene is lit by
    /// RealityKit's own default environment, whose level is not exposed.
    @objc public var ambientIntensity: Float = 1

    @objc public var castShadows: Bool = true

    // MARK: Environment

    /// `"default"`, `"color"` or `"hdr"`. Anything else is treated as `"default"`.
    @objc public var environmentKind: String = "default"

    /// Linear components, `0...1`, used when ``environmentKind`` is `"color"`.
    @objc public var environmentRed: Float = 0
    @objc public var environmentGreen: Float = 0
    @objc public var environmentBlue: Float = 0
    @objc public var environmentAlpha: Float = 1

    /// Bundle resource name of the `.hdr` / `.exr`, used when ``environmentKind`` is
    /// `"hdr"`.
    @objc public var environmentHdrPath: String?

    /// Whether an `"hdr"` environment is also drawn as the background.
    @objc public var environmentShowSkybox: Bool = true

    @objc public override init() {
        super.init()
    }
}

/// A `UIView` that renders a ``SceneView`` and is driven entirely by primitives.
///
/// ### What it is for
///
/// SceneView's Apple renderer is SwiftUI-only. Three separate bridges need it as a
/// `UIView` instead — `sceneview-compose` (Kotlin Multiplatform, through
/// `UIKitView` and its `SceneViewerBridge` factory), the Flutter plugin's
/// `FlutterPlatformView`, and the React Native Fabric component. None of them can hold a
/// SwiftUI `View`: KMP cannot see SwiftUI types through cinterop at all, and the other
/// two are handed a `UIView` by their host framework. This is the one wrapper they can
/// share, so that "hosting a SwiftUI scene in UIKit correctly" is solved once.
///
/// Today `sceneview-compose` is the consumer. The Flutter and React Native bridges still
/// carry their own bespoke platform views (`SceneViewPlugin.swift`,
/// `SceneViewModule.swift`), written before this existed and doing more than viewing —
/// method channels, AR, tap-to-place. Migrating them onto this host is worthwhile and
/// deliberately not done here: they are production-tested, and a rewrite of working
/// bridge code does not belong in the change that introduces the thing to rewrite them
/// onto.
///
/// ### Using it
///
/// ```swift
/// let host = SceneViewerHostView()
/// host.onCameraMoved = { distance, azimuthDegrees, elevationDegrees in
///     // mirror into your own camera state — see the note below, this matters
/// }
/// host.onTap = { hit, x, y, z, distance in /* ... */ }
///
/// let config = SceneViewerConfiguration()
/// config.modelAssetPath = "models/helmet.usdz"
/// host.applyConfiguration(config)       // and again on every change; never rebuild
/// ```
///
/// Call ``applyConfiguration(_:)`` for every update rather than creating a new host:
/// rebuilding reloads the model and throws away wherever the user had orbited to. The
/// verbose name is for the Kotlin side — `apply` is one of the most-used extensions in
/// the Kotlin standard library, and a bridge method that shadows it at every call site
/// is a trap the Swift-side brevity does not pay for.
///
/// ### Two limits, reported rather than papered over
///
/// - **There is no per-frame callback.** ``SceneView`` publishes none, and a polled
///   timer would report times that are not the renderer's. A host's `onFrame` equivalent
///   is not called on iOS.
/// - **A tap that misses the model produces no callback.** RealityKit's hit-testing
///   gesture only fires when it hits something, so — unlike Android, where a miss calls
///   back with a null hit — a miss here is silence. ``onTap``'s `hit` flag exists for
///   signature parity across the bridges and is always `true`.
///
/// And one more worth knowing before you debug a wrong-looking number: the tap position
/// is the tapped entity's bounds centre, not the exact surface point. See ``SceneTapHit``.
@objc(SVSceneViewerHostView)
@MainActor
public final class SceneViewerHostView: UIView {

    /// Observable state the hosted SwiftUI scene reads. Also owns the callbacks, so the
    /// scene never captures this view and no retain cycle can form through them.
    private let state = SceneViewerState()

    private let hostingController: UIHostingController<SceneViewerRootView>

    /// Called after a tap lands on the model, with `(hit, x, y, z, distanceFromCamera)`.
    ///
    /// `hit` is always `true` — see the type's documentation. Coordinates are world
    /// space; `distance` is from the camera to that point, both derived through the same
    /// orbit convention the renderer uses.
    @objc public var onTap: ((Bool, Float, Float, Float, Float) -> Void)? {
        get { state.onTap }
        set { state.onTap = newValue }
    }

    /// Called after **every** camera change, with `(distance, azimuthDegrees,
    /// elevationDegrees)`.
    ///
    /// **Wire this.** It is what keeps a hoisted camera state honest: without it a host
    /// can only ever report the pose it last wrote, so the user orbits the model, the
    /// screen moves, and every read still returns the initial pose — with nothing
    /// anywhere to indicate that the two have diverged. It also reports back a written
    /// pose that had to be clamped, which is the only way the caller learns that the
    /// value it set is not the value on screen.
    @objc public var onCameraMoved: ((Float, Float, Float) -> Void)? {
        get { state.onCameraMoved }
        set { state.onCameraMoved = newValue }
    }

    @objc public override init(frame: CGRect) {
        let state = self.state
        self.hostingController = UIHostingController(
            rootView: SceneViewerRootView(state: state)
        )
        super.init(frame: frame)
        installHostedView()
    }

    /// Creates a host already showing `configuration`.
    ///
    /// Equivalent to `init(frame:)` followed by ``applyConfiguration(_:)``, but applies the
    /// configuration before the first layout, so the scene is never briefly built from
    /// the defaults.
    @objc public convenience init(configuration: SceneViewerConfiguration) {
        self.init(frame: .zero)
        applyConfiguration(configuration)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("SceneViewerHostView is created in code, not from a nib")
    }

    private func installHostedView() {
        let hosted = hostingController.view!
        hosted.frame = bounds
        hosted.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        // Clear, so this view's own `backgroundColor` — which is what a `"color"`
        // environment sets — shows through the RealityView's transparent background.
        hosted.backgroundColor = .clear
        addSubview(hosted)
    }

    // MARK: - Configuration

    /// Applies `configuration` to the live scene, mutating what changed and nothing else.
    ///
    /// Safe to call on every update of your own framework's render loop, however often
    /// that is: every field is compared before it is assigned. That is not an
    /// optimisation but a correctness requirement — a `@Published` property fires its
    /// observers even when assigned an identical value, so assigning unconditionally
    /// would re-evaluate the SwiftUI body, re-diff the light slots, and re-run the
    /// environment loader on every single frame of a drag.
    @objc public func applyConfiguration(_ configuration: SceneViewerConfiguration) {
        applyModel(configuration)
        applyCamera(configuration)
        // Separate from `applyCamera`, which returns early when the incoming pose is an
        // echo of its own read-back. Folding the gesture flag in there would make it
        // reachable only on frames that also carry a camera move — so toggling gestures
        // alone would land on the next drag, or never.
        applyGestures(configuration)
        applyLighting(configuration)
        applyEnvironment(configuration)
    }

    private func applyModel(_ configuration: SceneViewerConfiguration) {
        let request = Self.modelRequest(from: configuration)
        let key = request.key
        guard key != state.modelKey else { return }
        // The request first: the `@Published` key is what wakes the loading task, and it
        // must not observe a key whose payload has not landed yet.
        state.modelRequest = request
        state.modelKey = key
    }

    private func applyCamera(_ configuration: SceneViewerConfiguration) {
        let incoming = SceneCameraPose(
            azimuth: SceneViewerAngle.radians(fromDegrees: configuration.cameraAzimuthDegrees),
            elevation: SceneViewerAngle.radians(fromDegrees: configuration.cameraElevationDegrees),
            distance: configuration.cameraDistance,
            target: SIMD3<Float>(
                configuration.cameraTargetX,
                configuration.cameraTargetY,
                configuration.cameraTargetZ
            )
        )

        // Echo suppression. A host that wires `onCameraMoved` writes what it is told back
        // into its own state, and its next update hands that same pose straight back
        // here. Re-applying it would be harmless if it arrived instantly — but it arrives
        // one or more frames late, so mid-drag it would pin the camera to where the
        // finger was two frames ago and the orbit would judder or stall outright.
        //
        // Comparing against what was last *reported* (not against what was last
        // requested) is what makes this correct in both directions: a genuine write from
        // the app differs from the last report and is applied, including a write that
        // happens to restore a pose the app had requested earlier and the user has since
        // dragged away from.
        if let reported = state.lastReportedPose, incoming.approximatelyMatches(reported) {
            return
        }
        guard !incoming.approximatelyMatches(state.cameraPose) else { return }
        state.cameraPose = incoming
    }

    private func applyLighting(_ configuration: SceneViewerConfiguration) {
        let lighting = SceneViewerLighting(
            direction: SIMD3<Float>(
                configuration.lightDirectionX,
                configuration.lightDirectionY,
                configuration.lightDirectionZ
            ),
            intensity: configuration.lightIntensity,
            ambientIntensity: configuration.ambientIntensity,
            castShadows: configuration.castShadows
        )
        if state.lighting != lighting {
            state.lighting = lighting
        }
    }

    private func applyGestures(_ configuration: SceneViewerConfiguration) {
        if state.cameraGesturesEnabled != configuration.cameraGesturesEnabled {
            state.cameraGesturesEnabled = configuration.cameraGesturesEnabled
        }
    }

    private func applyEnvironment(_ configuration: SceneViewerConfiguration) {
        let kind = configuration.environmentKind

        // A flat colour is this view's own background, not a RealityKit environment:
        // `SceneEnvironment` only models an HDR resource, and RealityKit exposes no
        // solid-colour environment for a SwiftUI `RealityView`. The model therefore
        // stays lit by RealityKit's default IBL, where Android's flat-colour environment
        // means "key light only" — a divergence the module README states rather than one
        // this code pretends away.
        backgroundColor = kind == "color"
            ? UIColor(
                red: CGFloat(configuration.environmentRed),
                green: CGFloat(configuration.environmentGreen),
                blue: CGFloat(configuration.environmentBlue),
                alpha: CGFloat(configuration.environmentAlpha)
            )
            : .clear

        let environment: SceneEnvironment?
        if kind == "hdr", let path = configuration.environmentHdrPath, !path.isEmpty {
            environment = SceneEnvironment(
                name: path,
                hdrResource: path,
                intensity: configuration.ambientIntensity,
                showSkybox: configuration.environmentShowSkybox
            )
        } else {
            environment = nil
        }

        // `SceneEnvironment` is not `Equatable`, and the HDR loader is a multi-hundred-
        // millisecond decode of a ~30 MB texture — re-running it per frame would be
        // ruinous. Compare the fields that identify one instead.
        let key = environment.map { "\($0.name)|\($0.intensity)|\($0.showSkybox)" }
        guard key != state.environmentKey else { return }
        state.environmentKey = key
        state.environment = environment
    }

    private static func modelRequest(
        from configuration: SceneViewerConfiguration
    ) -> SceneViewerModelRequest {
        if let path = configuration.modelAssetPath, !path.isEmpty {
            return .asset(path)
        }
        if let string = configuration.modelURLString,
           !string.isEmpty,
           let url = URL(string: string) {
            return .url(url)
        }
        if let bytes = configuration.modelBytes, !bytes.isEmpty {
            return .bytes(bytes, fileExtension: configuration.modelBytesFileExtension)
        }
        return .none
    }
}

// MARK: - Observable state

/// The scene's live inputs, and the callbacks out of it.
///
/// Holding the callbacks here rather than on the view is what keeps the object graph
/// acyclic: the hosted SwiftUI tree needs to invoke them, and if it reached them through
/// the view it would have to capture the view — which owns the hosting controller, which
/// owns the tree. That is exactly the retain cycle `SceneViewPlugin.swift` documents
/// (issue #2069), avoided here by construction rather than by a weak capture.
@MainActor
final class SceneViewerState: ObservableObject {

    /// Identity of the model to display; drives the loading task.
    @Published var modelKey: String?

    /// The payload behind ``modelKey``. Not `@Published` — nothing renders from it
    /// directly, and republishing megabytes of `Data` would invalidate the SwiftUI body.
    var modelRequest: SceneViewerModelRequest = .none

    @Published var cameraPose = SceneCameraPose(
        azimuth: 0,
        elevation: SceneViewerAngle.radians(fromDegrees: 15),
        distance: 4,
        target: .zero
    )
    @Published var cameraGesturesEnabled = true
    @Published var lighting = SceneViewerLighting(
        direction: SIMD3<Float>(0.3, -1, -0.5),
        intensity: 100_000,
        ambientIntensity: 1,
        castShadows: true
    )
    @Published var environment: SceneEnvironment?

    /// Identity of ``environment``, for the change check in `applyEnvironment`.
    var environmentKey: String?

    /// The last pose handed out through ``onCameraMoved``.
    ///
    /// The reference point for echo suppression — see `SceneViewerHostView.applyCamera`.
    /// Not `@Published`: it changes on every frame of a drag and nothing renders from it.
    var lastReportedPose: SceneCameraPose?

    var onTap: ((Bool, Float, Float, Float, Float) -> Void)?
    var onCameraMoved: ((Float, Float, Float) -> Void)?

    func reportCameraMoved(_ pose: SceneCameraPose) {
        lastReportedPose = pose
        onCameraMoved?(
            pose.distance,
            SceneViewerAngle.degrees(fromRadians: pose.azimuth),
            SceneViewerAngle.degrees(fromRadians: pose.elevation)
        )
    }

    func reportTap(_ hit: SceneTapHit) {
        // Distance is measured from where the camera actually is, which is the last
        // reported pose when there is one — the requested pose can be several frames
        // stale mid-drag, and after any gesture it is not where the camera is at all.
        let pose = lastReportedPose ?? cameraPose
        let position = hit.worldPosition
        let distance = simd_length(position - pose.cameraPosition())
        onTap?(true, position.x, position.y, position.z, distance)
    }
}

// MARK: - Hosted SwiftUI scene

/// Persistent per-scene objects that must survive every SwiftUI re-render.
///
/// `@StateObject`-held, so the content root keeps its identity: rebuilding it would
/// orphan the loaded model, and rebuilding the light node would make ``LightSlot`` — which
/// compares `.custom` by entity reference — read every render as a light change and swap
/// the entity out of the scene at frame rate.
@MainActor
final class SceneViewerSceneObjects: ObservableObject {
    let contentRoot = SceneViewerContentRoot()

    private var appliedLighting: SceneViewerLighting?
    private var lightNode: LightNode?

    /// The key `LightNode` for `lighting`, rebuilt only when the values change.
    func light(for lighting: SceneViewerLighting) -> LightNode {
        if let lightNode, appliedLighting == lighting { return lightNode }
        // Directional lights emit along their forward (-Z) axis, so aiming one is
        // `look(at:)` from the origin toward the direction the light travels.
        let node = LightNode
            .directional(intensity: lighting.intensity, castsShadow: lighting.castShadows)
            .position(.zero)
            .lookAt(lighting.normalizedDirection)
        appliedLighting = lighting
        self.lightNode = node
        return node
    }
}

/// Owns the entity the models are attached to, and reconciles it with the request.
@MainActor
final class SceneViewerContentRoot {

    /// Handed to ``SceneView`` once, during scene setup, and mutated afterwards.
    ///
    /// `SceneView`'s content closure runs once (or once per `.contentID(_:)` change), so
    /// a model that finishes loading later cannot appear through it. A stable root that
    /// the loader attaches to is the pattern the SceneViewSwift docs prescribe for
    /// async-loaded content, and the one the Flutter bridge already uses.
    let entity = Entity()

    private var loadedKey: String?
    private var loaded: Entity?

    /// Loads `request`'s model and swaps it in, replacing whatever was there.
    func sync(to request: SceneViewerModelRequest) async {
        let key = request.key
        guard key != loadedKey else { return }

        loaded?.removeFromParent()
        loaded = nil
        loadedKey = key
        guard key != nil else { return }

        do {
            let node = try await Self.load(request)
            // The request may have moved on across the `await` — a fast model swap, or
            // the scene being torn down. Attaching now would put the superseded model on
            // screen, permanently, since nothing else re-runs this.
            guard loadedKey == key else { return }
            loaded = node.entity
            entity.addChild(node.entity)
        } catch {
            // Logged, not thrown: there is no failed state in the viewer façade, and a
            // failed load leaves the environment on screen — indistinguishable from a
            // load still in progress. The log is the only thing that tells them apart,
            // which is precisely why it must exist.
            NSLog(
                "[SceneViewSwift] SceneViewerHostView failed to load model '%@': %@",
                key ?? "(none)",
                error.localizedDescription
            )
        }
    }

    private static func load(_ request: SceneViewerModelRequest) async throws -> ModelNode {
        switch request {
        case .none:
            throw SceneViewerHostError.noModel
        case .asset(let path):
            return try await ModelNode.load(path)
        case .url(let url):
            return try await ModelNode.load(from: url)
        case .bytes(let data, let fileExtension):
            // RealityKit has no data-based loader — it dispatches on the file extension,
            // so bytes have to become a file with the right one before it will look at
            // them.
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension(fileExtension)
            try data.write(to: url)
            defer { try? FileManager.default.removeItem(at: url) }
            return try await ModelNode.load(contentsOf: url)
        }
    }
}

enum SceneViewerHostError: LocalizedError {
    case noModel

    var errorDescription: String? {
        switch self {
        case .noModel: return "No model source was set on the configuration"
        }
    }
}

/// The SwiftUI scene the host view hosts.
struct SceneViewerRootView: View {

    @ObservedObject var state: SceneViewerState

    @StateObject private var scene = SceneViewerSceneObjects()

    var body: some View {
        var view = SceneView { root in
            root.addChild(scene.contentRoot.entity)
        }
        .cameraControls(.orbit)
        // Off deliberately. The fit-to-bounds pass owns the orbit radius, and would
        // overwrite the distance the caller wrote — then, through `onCameraChanged`,
        // overwrite the caller's own state with it. SceneView Android honours the
        // authored distance verbatim; matching that is what makes one camera state mean
        // one thing on both platforms.
        .autoCenterContent(false)
        .cameraGesturesEnabled(state.cameraGesturesEnabled)
        .cameraPose(state.cameraPose)
        .onCameraChanged { pose in
            state.reportCameraMoved(pose)
        }
        .onEntityTapped(hit: { hit in
            state.reportTap(hit)
        })
        .mainLight(.custom(scene.light(for: state.lighting)))
        // The façade exposes one key light. A second, unexposed light would keep lighting
        // the scene with an intensity the caller can neither see nor turn off — the same
        // reason the Android implementation passes `fillLightNode = null`.
        .fillLight(.disabled)

        if let environment = state.environment {
            view = view.environment(environment)
        }

        return view.task(id: state.modelKey) {
            await scene.contentRoot.sync(to: state.modelRequest)
        }
    }
}
#endif
