#if os(iOS)
import SwiftUI
import RealityKit
import ARKit
import CoreImage
import Metal

/// A SwiftUI view for augmented reality using ARKit + RealityKit.
///
/// Mirrors SceneView Android's `ARSceneView { }` composable — place content
/// relative to real-world surfaces, images, and anchors.
///
/// Uses `ARView` (UIKit) wrapped in `UIViewRepresentable` for full ARKit
/// support on iPhone. Provides plane detection, tap-to-place hit testing,
/// and coaching overlay.
///
/// ```swift
/// ARSceneView(
///     planeDetection: .horizontal,
///     onTapOnPlane: { position in
///         // Place a 10 cm cube at the tapped surface
///         let cube = GeometryNode.cube(size: 0.1, color: .blue)
///         let anchor = AnchorNode.world(position: position)
///         anchor.add(cube.entity)
///         // arView.scene.addAnchor(anchor.entity) — done automatically
///     }
/// )
/// ```
/// Errors surfaced by ``ARSceneView`` through ``ARSceneView/onSessionError(_:)``.
public enum ARSceneViewError: Error, Sendable, Equatable, LocalizedError {
    /// `faceTracking: true` was requested on a device without a TrueDepth
    /// camera. The view does NOT silently fall back to the rear world-tracking
    /// camera: nothing is started, and the host is expected to render an
    /// unsupported-device state.
    case faceTrackingUnsupported

    /// The ``ARSessionConfiguration`` asked for something this device lacks
    /// (world tracking on the Simulator, LiDAR, a frame semantic). As with
    /// ``faceTrackingUnsupported``, nothing is started: the host renders an
    /// unsupported-device state naming the requirement.
    case unsupported(ARSessionConfiguration.Requirement)

    public var errorDescription: String? {
        switch self {
        case .faceTrackingUnsupported:
            return "Face tracking is not supported on this device (no TrueDepth camera)."
        case .unsupported(.worldTracking):
            return "World tracking is not supported on this device."
        case .unsupported(.faceTracking):
            return "Face tracking is not supported on this device (no TrueDepth camera)."
        case .unsupported(.lidar):
            return "Scene reconstruction requires a LiDAR scanner."
        case .unsupported(.frameSemantics):
            return "The requested frame semantics are not supported on this device."
        }
    }
}

/// Environment slot for the one ``ARSceneSessionObserver`` every ``ARSceneView``
/// in a subtree reports to — see ``SwiftUI/View/arSessionObserver(_:)``.
private struct ARSessionObserverKey: EnvironmentKey {
    static let defaultValue: ARSceneSessionObserver? = nil
}

extension EnvironmentValues {
    /// The ``ARSceneSessionObserver`` installed above this view, if any.
    public var arSessionObserver: ARSceneSessionObserver? {
        get { self[ARSessionObserverKey.self] }
        set { self[ARSessionObserverKey.self] = newValue }
    }
}

extension View {
    /// Routes every ``ARSessionEvent`` of every ``ARSceneView`` in this
    /// subtree to `observer`, in addition to the closures the camera view
    /// itself declares. Lets a container own the permission / starting /
    /// error presentation for an AR screen it does not build:
    ///
    /// ```swift
    /// ARExperienceContainer {          // observes first frame, failures
    ///     ARPlacementDemo()             // keeps its own onTapOnPlane etc.
    /// }
    /// ```
    ///
    /// The SDK holds the observer only through the environment; keep it
    /// alive for as long as the subtree is on screen.
    public func arSessionObserver(_ observer: ARSceneSessionObserver?) -> some View {
        environment(\.arSessionObserver, observer)
    }
}

public struct ARSceneView: UIViewRepresentable {
    /// Everything the session runs. Built by the classic initializer from its
    /// individual parameters, or passed whole through
    /// ``init(configuration:showPlaneOverlay:showCoachingOverlay:showPlacementReticle:groundingShadows:cameraExposure:onTapOnPlane:onImageDetected:onFrame:)``.
    private var configuration: ARSessionConfiguration
    private var showPlaneOverlay: Bool
    private var showCoachingOverlay: Bool
    private var showPlacementReticle: Bool
    private var groundingShadows: Bool
    private var cameraExposure: Float?
    private var onTapOnPlane: ((SIMD3<Float>, ARView) -> Void)?
    private var onSessionStarted: ((ARView) -> Void)?
    private var onSessionError: ((Error, ARView) -> Void)?
    private var onSessionEvent: ((ARSessionEvent, ARView) -> Void)?
    private var onSessionStateChange: ((ARSessionState, ARView) -> Void)?
    private var onTrackingStateChange: ((ARTrackingStatus, ARView) -> Void)?
    private var onImageDetected: ((String, AnchorNode, ARView) -> Void)?
    private var onFrame: ((ARFrame, ARView) -> Void)?

    private var planeDetection: PlaneDetectionMode { configuration.planeDetection }
    private var faceTracking: Bool { configuration.mode == .faceTracking }

    // Light slot overrides — read once during scene setup and re-applied via
    // `updateUIView` when the caller mutates the modifier value. Defaults to
    // `.systemDefault` which provisions Android-parity 10 000-lux main + 3 000-lux
    // fill (matches PR #1136 / #1063 — `ARSceneView` Android exposes
    // `mainLightNode` + `fillLightNode` parameters with the same defaults).
    //
    // ARCore's `ENVIRONMENTAL_HDR` light estimation only drives `mainLightNode`
    // on Android (it returns SH coefficients and main-light direction/intensity).
    // ARKit's `.automatic` environment texturing is the closest equivalent — it
    // populates a runtime cubemap on `ARView` for PBR reflections — but does NOT
    // mutate any explicit directional light. The fill light therefore keeps its
    // baseline intensity each frame on both platforms.
    var mainLightSlot: LightSlot = .systemDefault
    var fillLightSlot: LightSlot = .systemDefault

    /// Plane detection modes matching Android's ARCore config.
    public enum PlaneDetectionMode: Sendable {
        case none
        case horizontal
        case vertical
        case both

        var arPlaneDetection: ARWorldTrackingConfiguration.PlaneDetection {
            switch self {
            case .none: return []
            case .horizontal: return .horizontal
            case .vertical: return .vertical
            case .both: return [.horizontal, .vertical]
            }
        }
    }

    /// Creates an AR scene with plane detection, image tracking, and tap-to-place.
    ///
    /// - Parameters:
    ///   - planeDetection: Which plane orientations to detect. Default horizontal.
    ///   - showPlaneOverlay: Whether to visualize detected planes. Default true.
    ///   - showCoachingOverlay: Whether to show coaching when tracking limited. Default true.
    ///   - showPlacementReticle: Whether to show a placement reticle — a small disc,
    ///     snapped to the real surface at the screen centre, that previews exactly where
    ///     a tap-to-place tap will land. Runs the same
    ///     `raycast(from:allowing:.estimatedPlane, alignment:.any)` query as `onTapOnPlane`,
    ///     once per AR frame; the reticle hides whenever the ray misses every surface.
    ///     Orientation is smoothed with a per-frame slerp factor of `0.75` (ARCore Depth
    ///     Lab's `OrientedReticle` value — Android `PlacementReticle` parity, #2241/#894)
    ///     so the disc doesn't jitter while ARKit refines the surface estimate. Default false.
    ///   - groundingShadows: Whether entities placed via `onTapOnPlane` automatically get
    ///     RealityKit's `GroundingShadowComponent(castsShadow: true)`, so placed models
    ///     project a contact shadow onto the detected surface and read as grounded instead
    ///     of floating — the RealityKit analogue of Android's `ShadowReceiverPlane`
    ///     (#2241/#894). Applied to every model entity of anchors added to
    ///     `arView.scene` *synchronously inside* the `onTapOnPlane` callback (the
    ///     documented placement flow); content anchored later (e.g. after an async model
    ///     load) must set the component itself. Default true.
    ///   - imageTrackingDatabase: Set of reference images to detect. Use
    ///     `AugmentedImageNode.createImageDatabase()` or
    ///     `AugmentedImageNode.referenceImages(inGroupNamed:)` to create.
    ///   - cameraExposure: Optional brightness compensation for the rendered AR frame,
    ///     in EV (exposure value) stops. When non-nil, a post-processing brightness
    ///     adjustment is applied to the rendered frame via
    ///     `ARView.renderCallbacks.postProcess`. This is **rendered-frame brightness,
    ///     not capture exposure**: it acts on the composited camera feed *and* virtual
    ///     content after ARKit metered the capture, so it cannot recover blown
    ///     highlights or crushed shadows.
    ///     Positive values brighten the scene; negative values darken it. A value of `0.0`
    ///     leaves the camera feed unchanged. Pass `nil` (the default) to skip any
    ///     exposure override and rely on ARKit's built-in auto-exposure.
    ///
    ///     Mirrors Android's `ARSceneView(cameraExposure: Float?)` parameter, which
    ///     overrides Filament's camera aperture/shutter/ISO when ARCore's auto-exposure
    ///     does not match the Camera2 output on a given device.
    ///
    ///     Requires iOS 15.0+. On earlier OS versions the value is stored but has no effect.
    ///   - onTapOnPlane: Called with (worldPosition, arView) when user taps on a plane.
    ///   - onImageDetected: Called with (imageName, anchorNode, arView) when a reference
    ///     image is detected. Add content to the anchor and call
    ///     `arView.scene.addAnchor(anchor.entity)`.
    ///   - faceTracking: When `true`, switches to `ARFaceTrackingConfiguration` (uses the
    ///     front TrueDepth camera). Requires a device with a TrueDepth camera (iPhone X+).
    ///     Plane detection and tap-to-place are not available in face-tracking mode.
    public init(
        planeDetection: PlaneDetectionMode = .horizontal,
        showPlaneOverlay: Bool = true,
        showCoachingOverlay: Bool = true,
        showPlacementReticle: Bool = false,
        groundingShadows: Bool = true,
        cameraExposure: Float? = nil,
        imageTrackingDatabase: Set<ARReferenceImage>? = nil,
        faceTracking: Bool = false,
        onTapOnPlane: ((SIMD3<Float>, ARView) -> Void)? = nil,
        onImageDetected: ((String, AnchorNode, ARView) -> Void)? = nil,
        onFrame: ((ARFrame, ARView) -> Void)? = nil
    ) {
        self.init(
            configuration: ARSessionConfiguration(
                mode: faceTracking ? .faceTracking : .worldTracking,
                planeDetection: planeDetection,
                imageTrackingDatabase: imageTrackingDatabase
            ),
            showPlaneOverlay: showPlaneOverlay,
            showCoachingOverlay: showCoachingOverlay,
            showPlacementReticle: showPlacementReticle,
            groundingShadows: groundingShadows,
            cameraExposure: cameraExposure,
            onTapOnPlane: onTapOnPlane,
            onImageDetected: onImageDetected,
            onFrame: onFrame
        )
    }

    /// Creates an AR scene from a whole ``ARSessionConfiguration``.
    ///
    /// The configuration is a value: pass a new one on a later render and the
    /// view applies the difference to the live session — no `.id(...)` rekey,
    /// no tracking reset unless the ``ARSessionConfiguration/mode`` changed,
    /// nothing the host placed is dropped. The same value is what the session
    /// resumes with after an interruption.
    ///
    /// ```swift
    /// ARSceneView(
    ///     configuration: ARSessionConfiguration(
    ///         planeDetection: .both,
    ///         sceneReconstruction: .mesh          // Requires LiDAR — reported, never faked
    ///     )
    /// )
    /// .onSessionStateChange { state, _ in cameraIsLive = (state == .running) }
    /// ```
    ///
    /// The remaining parameters are the ones of the classic initializer and
    /// mean the same thing.
    public init(
        configuration: ARSessionConfiguration,
        showPlaneOverlay: Bool = true,
        showCoachingOverlay: Bool = true,
        showPlacementReticle: Bool = false,
        groundingShadows: Bool = true,
        cameraExposure: Float? = nil,
        onTapOnPlane: ((SIMD3<Float>, ARView) -> Void)? = nil,
        onImageDetected: ((String, AnchorNode, ARView) -> Void)? = nil,
        onFrame: ((ARFrame, ARView) -> Void)? = nil
    ) {
        self.configuration = configuration
        self.showPlaneOverlay = showPlaneOverlay
        self.showCoachingOverlay = showCoachingOverlay
        self.showPlacementReticle = showPlacementReticle
        self.groundingShadows = groundingShadows
        self.cameraExposure = cameraExposure
        self.onTapOnPlane = onTapOnPlane
        self.onImageDetected = onImageDetected
        self.onFrame = onFrame
    }

    /// Called once when the AR session starts. Use to add initial content.
    public func onSessionStarted(
        _ handler: @escaping (ARView) -> Void
    ) -> ARSceneView {
        var copy = self
        copy.onSessionStarted = handler
        return copy
    }

    /// Called with every ``ARSessionEvent`` the view observes, in order:
    /// `started`, `firstFrame`, tracking changes, interruption begin / end,
    /// failures. The one callback to take when a host wants the whole
    /// lifecycle; the modifiers below are projections of it.
    ///
    /// Runs on the main actor (ARKit delivers to `ARSceneView`'s delegate on
    /// the main queue).
    public func onSessionEvent(
        _ handler: @escaping (ARSessionEvent, ARView) -> Void
    ) -> ARSceneView {
        var copy = self
        copy.onSessionEvent = handler
        return copy
    }

    /// Called when the coarse ``ARSessionState`` changes: `starting` when the
    /// session is run, `running` on the first camera frame, `interrupted` /
    /// `running` around an interruption, `failed` on an error. Reported once
    /// per change.
    ///
    /// `running` is the signal that the camera is on screen. It is
    /// independent of whatever the host is loading: a screen that shows
    /// "Starting camera…" until this fires and "Loading model…" until its own
    /// load finishes reports both honestly.
    public func onSessionStateChange(
        _ handler: @escaping (ARSessionState, ARView) -> Void
    ) -> ARSceneView {
        var copy = self
        copy.onSessionStateChange = handler
        return copy
    }

    /// Called when `ARCamera.trackingState` changes — once per change, not
    /// per frame. `limited(.excessiveMotion)` / `limited(.insufficientFeatures)`
    /// are the "Tracking paused. Move slowly." states; `limited(.relocalizing)`
    /// is "Finding your placement…"; `normal` clears them.
    public func onTrackingStateChange(
        _ handler: @escaping (ARTrackingStatus, ARView) -> Void
    ) -> ARSceneView {
        var copy = self
        copy.onTrackingStateChange = handler
        return copy
    }

    /// Called whenever the AR session cannot run: ARKit reported a session
    /// failure (camera permission denied, sensor failure…), or the requested
    /// configuration is unsupported on this device — notably
    /// ``ARSceneViewError/faceTrackingUnsupported``, where the view starts no
    /// session at all rather than silently switching to the rear world camera.
    ///
    /// Additive and optional: without a handler the SDK keeps printing the
    /// error to the console as before.
    ///
    /// ```swift
    /// ARSceneView(faceTracking: true)
    ///     .onSessionError { error, _ in unsupported = true }
    /// ```
    public func onSessionError(
        _ handler: @escaping (Error, ARView) -> Void
    ) -> ARSceneView {
        var copy = self
        copy.onSessionError = handler
        return copy
    }

    /// Sets a brightness compensation override for the rendered AR frame.
    ///
    /// **Rendered-frame brightness, not capture exposure.** The adjustment runs on
    /// the composited frame — camera feed and virtual content alike — after ARKit's
    /// auto-exposure metered the capture. Use
    /// `ARConfiguration.configurableCaptureDeviceForPrimaryCamera` for true capture
    /// exposure control.
    ///
    /// Positive values brighten the rendered scene; negative values darken it. One stop
    /// equals a doubling or halving of brightness. Pass `nil` to remove any override and
    /// rely on ARKit's built-in auto-exposure.
    ///
    /// Mirrors Android's `cameraExposure` parameter on `ARSceneView`, which overrides
    /// Filament's camera aperture/shutter/ISO when ARCore's auto-exposure does not match
    /// the Camera2 output on a given device.
    ///
    /// Implemented via `ARView.renderCallbacks.postProcess` (iOS 15.0+) using a
    /// `CIColorControls` brightness filter. On earlier OS versions, the call is a no-op.
    ///
    /// - Parameter ev: Exposure compensation in EV stops. `0.0` = no change, positive
    ///   values brighten, negative values darken.
    /// - Returns: A copy of this view with the exposure override applied.
    public func cameraExposure(_ ev: Float?) -> ARSceneView {
        var copy = self
        copy.cameraExposure = ev
        return copy
    }

    /// Called on every updated AR frame. Use for debug logging (e.g.
    /// streaming to the Rerun viewer via ``RerunBridge``) or custom
    /// per-frame analysis. Mirrors Android's `onSessionUpdated` callback.
    ///
    /// Runs on the ARKit delegate queue — do NOT block here. For I/O,
    /// hand the frame off to a background queue.
    public func onFrame(
        _ handler: @escaping (ARFrame, ARView) -> Void
    ) -> ARSceneView {
        var copy = self
        copy.onFrame = handler
        return copy
    }

    /// Configures the main / key directional light slot for the AR scene.
    ///
    /// Mirrors SceneView Android's `ARSceneView(mainLightNode = ...)`
    /// composable parameter (`ARSceneView.kt:294`). Default (`.systemDefault`):
    /// directional light at `10 000` lux pointing straight down (`-Y`),
    /// shadow casting enabled — same baseline as the 3D ``SceneView``.
    ///
    /// On Android, ARCore's `ENVIRONMENTAL_HDR` light estimation mutates
    /// the main light's color + intensity each frame. ARKit has no
    /// equivalent on the explicit-light side; instead `.automatic`
    /// environment texturing populates the IBL cubemap for PBR reflections.
    /// The directional light therefore keeps its baseline values unless
    /// you override them via `.custom(LightNode...)`.
    ///
    /// ```swift
    /// ARSceneView(planeDetection: .horizontal)
    ///   .mainLight(.custom(LightNode.directional(intensity: 5_000)))  // dimmer key
    ///
    /// ARSceneView(planeDetection: .horizontal)
    ///   .mainLight(.disabled)                                          // IBL-only AR
    /// ```
    public func mainLight(_ slot: LightSlot) -> ARSceneView {
        var copy = self
        copy.mainLightSlot = slot
        return copy
    }

    /// Configures the secondary fill directional light slot for the AR scene.
    ///
    /// Mirrors SceneView Android's `ARSceneView(fillLightNode = ...)`
    /// composable parameter shipped in PR #1136 (closes the iOS half of
    /// `#1063`). Default (`.systemDefault`): ``LightNode/fill(color:intensity:castsShadow:)``
    /// at `3 000` lux, no shadow, oriented along the canonical Android
    /// direction `(0.5, -0.5, 0.5)` (upper-back-left → down-front-right)
    /// so the unlit side of objects gets a soft kick without flattening
    /// the AR shading.
    ///
    /// Pair with ``mainLight(_:)`` to fully override the dual-light setup.
    /// Pass `.disabled` for a single-light AR scene (rare — most demos
    /// look harsh with a single hard directional light + no fill).
    ///
    /// ```swift
    /// ARSceneView(planeDetection: .horizontal)
    ///   .fillLight(.custom(LightNode.fill(intensity: 6_000)))   // brighter fill
    ///
    /// ARSceneView(planeDetection: .horizontal)
    ///   .fillLight(.disabled)                                    // single-light AR
    /// ```
    public func fillLight(_ slot: LightSlot) -> ARSceneView {
        var copy = self
        copy.fillLightSlot = slot
        return copy
    }

    // MARK: - UIViewRepresentable

    public func makeUIView(context: Context) -> ARView {
        let arView = ARView(frame: .zero)
        arView.automaticallyConfigureSession = false
        let coordinator = context.coordinator
        // Every callback is wired BEFORE the session runs, so the first
        // delegate message (a failure, the first frame) is never lost between
        // `session.run` and the assignment that used to follow it.
        coordinator.arView = arView
        syncCallbacks(on: coordinator, environment: context.environment)

        // Configure AR session — face tracking uses the front TrueDepth camera;
        // world tracking uses the rear camera for plane detection / image tracking.
        if let startError = Self.startSession(
            on: arView,
            configuration: configuration,
            capabilities: .current,
            coordinator: coordinator
        ) {
            print("[SceneViewSwift] AR session error: \(startError.localizedDescription)")
            coordinator.refusedConfiguration = configuration
            coordinator.reportFailure(startError, in: arView)
            // NOTHING ran: no coaching overlay, no lights — and above all no
            // `onSessionStarted`, which promises a live session and would have
            // had the host add content to a scene that never renders. The
            // host's error state is the whole content of this view — until a
            // later render asks for a configuration this device can run
            // (`updateUIView` → `applyIfChanged`).
            return arView
        }

        provisionScene(on: arView, context: context)
        return arView
    }

    /// Everything a live session gets once it runs: plane overlay, reticle,
    /// grounding shadows, coaching overlay, tap recognizer, the two light
    /// slots, then the host's `onSessionStarted`. Called from `makeUIView`
    /// when the first configuration runs, and from `updateUIView` when a
    /// later configuration recovers a view whose first one was refused —
    /// so the host never has to rekey the view to get its scene back.
    private func provisionScene(on arView: ARView, context: Context) {
        // Plane visualization.
        //
        // `arView.debugOptions.insert(.showAnchorGeometry)` is a *developer
        // debug* aid — RealityKit renders detected `ARPlaneAnchor`s as a solid,
        // fully-opaque fluorescent-green fill that overpaints the camera feed.
        // It must never ship to end users (#1557). Instead the coordinator
        // tracks `ARPlaneAnchor` add/update/remove events and renders a subtle
        // translucent overlay entity per plane — see `PlaneVisualizer`.
        context.coordinator.showPlaneOverlay = showPlaneOverlay && planeDetection != .none

        // Placement reticle + grounding shadows (#894 — iOS half of #2241 Sprint-1).
        // The reticle is driven per frame from `session(_:didUpdate:)`; grounding
        // shadows are applied in `handleTap` to the anchors the callback places.
        context.coordinator.showPlacementReticle = showPlacementReticle
        context.coordinator.groundingShadows = groundingShadows

        // Coaching overlay. Never on a face-tracking session: its goals are all
        // plane goals ("Move the device to detect a surface"), which is nonsense
        // in front of the TrueDepth camera and covers the face feed.
        context.coordinator.syncCoachingOverlay(
            on: arView,
            enabled: showCoachingOverlay && !faceTracking,
            goal: coachingGoal
        )

        // Tap gesture — installed ONLY when the host actually consumes taps.
        // A recognizer whose callback does nothing still takes part in gesture
        // recognition, so an unconditional one shadowed / delayed any tap
        // recognizer the host added to the same `ARView`.
        context.coordinator.syncTapRecognizer(on: arView, enabled: onTapOnPlane != nil)

        // Provision both light slots BEFORE the host app's session-started callback
        // runs, so user-supplied content sees the dual-light baseline already in
        // place. Mirrors Android's `ARSceneView { content }` ordering where the
        // composable's `mainLightNode` / `fillLightNode` parameters are added to
        // the scene before the trailing `content` lambda. Closes the iOS half
        // of #1063 / #1138.
        provisionARLightSlot(.main, slot: mainLightSlot, in: arView, coordinator: context.coordinator)
        provisionARLightSlot(.fill, slot: fillLightSlot, in: arView, coordinator: context.coordinator)
        context.coordinator.appliedMainSlot = mainLightSlot
        context.coordinator.appliedFillSlot = fillLightSlot

        // Initial content callback
        onSessionStarted?(arView)
    }

    /// Legacy entry point kept for the tests and hosts that call it directly:
    /// builds an ``ARSessionConfiguration`` from the individual parameters
    /// and defers to ``startSession(on:configuration:capabilities:coordinator:)``.
    /// `faceTrackingSupported` is injected so the unsupported path is
    /// testable on a Simulator.
    @MainActor
    static func startSession(
        on arView: ARView,
        faceTracking: Bool,
        faceTrackingSupported: Bool,
        planeDetection: PlaneDetectionMode,
        imageTrackingDatabase: Set<ARReferenceImage>?
    ) -> ARSceneViewError? {
        var capabilities = ARSessionConfiguration.Capabilities.current
        capabilities.faceTracking = faceTrackingSupported
        return startSession(
            on: arView,
            configuration: ARSessionConfiguration(
                mode: faceTracking ? .faceTracking : .worldTracking,
                planeDetection: planeDetection,
                imageTrackingDatabase: imageTrackingDatabase
            ),
            capabilities: capabilities,
            coordinator: nil
        )
    }

    /// Runs the AR session for `configuration`, or returns the reason it could
    /// not be started — in which case **no session was run at all** and the
    /// caller must not pretend one is live.
    ///
    /// `capabilities` is injected rather than read here so every unsupported
    /// path is testable on a Simulator. When a `coordinator` is given it is
    /// installed as the session delegate *before* `run`, records the
    /// configuration it must resume with, and emits ``ARSessionEvent/started(_:)``.
    @MainActor
    static func startSession(
        on arView: ARView,
        configuration: ARSessionConfiguration,
        capabilities: ARSessionConfiguration.Capabilities,
        coordinator: Coordinator?
    ) -> ARSceneViewError? {
        // No silent fallback: a device that lacks what the configuration
        // asks for gets an explicit error state, not a different (and wrong)
        // AR experience — the rear camera instead of the face camera, or a
        // "mesh" demo running with no mesh.
        if let missing = configuration.unmetRequirement(capabilities: capabilities) {
            return missing == .faceTracking ? .faceTrackingUnsupported : .unsupported(missing)
        }
        if let coordinator {
            coordinator.run(configuration, on: arView, resetTracking: true)
        } else {
            arView.session.run(
                configuration.makeARConfiguration(),
                options: [.resetTracking, .removeExistingAnchors]
            )
        }
        return nil
    }

    /// Copies the host's current closures onto the coordinator. Called from
    /// both `makeUIView` (before the session runs) and `updateUIView`, so a
    /// closure the host supplies on a later render is honoured too.
    private func syncCallbacks(on coordinator: Coordinator, environment: EnvironmentValues) {
        coordinator.onTapOnPlane = onTapOnPlane
        coordinator.onImageDetected = onImageDetected
        coordinator.onFrame = onFrame
        coordinator.onSessionError = onSessionError
        coordinator.onSessionEvent = onSessionEvent
        coordinator.onSessionStateChange = onSessionStateChange
        coordinator.onTrackingStateChange = onTrackingStateChange
        coordinator.sessionObserver = environment.arSessionObserver
    }

    public func updateUIView(_ arView: ARView, context: Context) {
        let coordinator = context.coordinator
        syncCallbacks(on: coordinator, environment: context.environment)
        // Reactive too: a host that supplies `onTapOnPlane` only on a later
        // render gets the recognizer then, and one that drops it gets the
        // recognizer removed so its own gestures are unobstructed.
        coordinator.syncTapRecognizer(on: arView, enabled: onTapOnPlane != nil)
        // A changed configuration is applied to the live session — planes,
        // image database, mesh, semantics — without a tracking reset, so what
        // the host placed stays where it is. Compared against what THIS view
        // last asked for, never against `session.configuration`: a host that
        // mutates the running configuration itself (people occlusion, mesh
        // classification) is not overridden on the next unrelated render.
        //
        // Evaluated even when no session ever ran: a host whose first ask was
        // refused (LiDAR mesh on a device without one) and now asks for
        // something this device can run gets its session here, and the scene
        // it would have got from `makeUIView`.
        let hadSession = coordinator.sessionDidStart
        coordinator.applyIfChanged(configuration, on: arView)
        if !hadSession, coordinator.sessionDidStart {
            provisionScene(on: arView, context: context)
        }
        // Nothing below applies to a view whose session never started (an
        // unsupported configuration): no coaching overlay on a dead session, no
        // post-process on a view that renders nothing.
        guard coordinator.sessionDidStart else { return }
        // Reactive like the light slots: toggling any of these flags on a later
        // render takes effect immediately. Before, `showPlaneOverlay` and
        // `showCoachingOverlay` were read once at creation, so hosts rekeyed the
        // whole view (`.id(...)`) to toggle them — which restarted the AR
        // session and dropped everything placed in it.
        context.coordinator.showPlacementReticle = showPlacementReticle
        context.coordinator.groundingShadows = groundingShadows
        context.coordinator.syncPlaneOverlay(
            on: arView,
            enabled: showPlaneOverlay && planeDetection != .none
        )
        context.coordinator.syncCoachingOverlay(
            on: arView,
            enabled: showCoachingOverlay && !faceTracking,
            goal: coachingGoal
        )

        // Apply camera exposure override via post-processing (iOS 15.0+).
        // Converts the EV value to a CIColorControls brightness offset and installs
        // (or removes) a post-process render callback on the ARView.
        coordinator.applyExposure(cameraExposure, on: arView)

        // Diff light slots and swap entities when the caller's modifier value
        // changed since last frame. Mirrors the reactive light path in
        // ``SceneView`` (#1017) and Android's `prevFillLightRef` pattern in
        // `ARSceneView.kt:540`. Closes #1138.
        refreshARLightSlot(.main, slot: mainLightSlot, in: arView, coordinator: context.coordinator)
        refreshARLightSlot(.fill, slot: fillLightSlot, in: arView, coordinator: context.coordinator)
    }

    /// Tears down the AR scene when SwiftUI permanently removes this view.
    ///
    /// Without this, the `ARView`'s `ARSession` keeps running after the view
    /// leaves the hierarchy — the rear camera, motion sensors, and per-frame
    /// tracking pipeline stay live, draining battery and pinning every anchor
    /// the coordinator added (the translucent plane overlays from #2407 and the
    /// dual light anchors from #2408) in `arView.scene`. The session is paused,
    /// the delegate detached, and all coordinator-owned anchors removed and
    /// released here so the AR resources deallocate with the view.
    ///
    /// `UIViewRepresentable.dismantleUIView` is invoked on the main actor while
    /// both `uiView` and `coordinator` are still alive — the correct place for
    /// RealityKit/ARKit teardown. The coordinator's `deinit` is a secondary
    /// safety net (#2407); this is the primary, deterministic path. The teardown
    /// is idempotent, so the two paths never double-free.
    public static func dismantleUIView(_ uiView: ARView, coordinator: Coordinator) {
        coordinator.tearDownScene(in: uiView)
    }

    // MARK: - Light slot provisioning (#1138)

    /// Identifies the active light anchor in `arView.scene.anchors` so the
    /// reactive diff can locate it on subsequent renders.
    fileprivate enum LightSlotKind { case main, fill }

    /// Provisions a directional light anchor on `arView.scene` for the given
    /// slot, applying the per-slot defaults when `slot == .systemDefault`.
    /// The anchor reference is cached on the coordinator so the reactive
    /// diff in ``refreshARLightSlot(_:slot:in:coordinator:)`` can replace it
    /// when the caller's `LightSlot` value changes.
    ///
    /// Lights are added as `AnchorEntity(world: .zero)` children of
    /// `arView.scene` — RealityKit AR scenes are rooted in `AnchorEntity`s,
    /// not bare `Entity`s, so this is the only way to inject a fixed-pose
    /// directional light. The light still renders relative to the world
    /// origin (which is where ARKit sets the session origin at session start).
    @MainActor
    private func provisionARLightSlot(
        _ which: LightSlotKind,
        slot: LightSlot,
        in arView: ARView,
        coordinator: Coordinator
    ) {
        let lightEntity: Entity?
        switch slot {
        case .systemDefault:
            switch which {
            case .main:
                let main = LightNode.directional(
                    color: .white,
                    intensity: 10_000,
                    castsShadow: true
                )
                main.entity.look(at: .zero, from: [0, 1, 0], relativeTo: nil)
                lightEntity = main.entity
            case .fill:
                let fill = LightNode.fill(intensity: 3_000)
                fill.entity.look(at: .zero, from: [-0.5, 0.5, -0.5], relativeTo: nil)
                lightEntity = fill.entity
            }
        case .disabled:
            lightEntity = nil
        case .custom(let node):
            lightEntity = node.entity
        }
        guard let lightEntity = lightEntity else { return }
        // Wrap in a fixed world anchor so RealityKit accepts it at the scene
        // root (an AR scene's `anchors` collection only takes AnchorEntities).
        let anchor = AnchorEntity(world: .zero)
        anchor.addChild(lightEntity)
        arView.scene.addAnchor(anchor)
        switch which {
        case .main: coordinator.mainLightAnchor = anchor
        case .fill: coordinator.fillLightAnchor = anchor
        }
    }

    /// Diffs the current slot value against the cached `applied{Main,Fill}Slot`
    /// stored on the coordinator and swaps the anchored light in-place when
    /// they differ. Called from `updateUIView(_:context:)`. Closes #1138.
    @MainActor
    private func refreshARLightSlot(
        _ which: LightSlotKind,
        slot: LightSlot,
        in arView: ARView,
        coordinator: Coordinator
    ) {
        let applied: LightSlot? = (which == .main)
            ? coordinator.appliedMainSlot
            : coordinator.appliedFillSlot
        guard applied != slot else { return }   // no change since last frame
        // Remove the old tagged anchor if present.
        let cachedAnchor: AnchorEntity?
        switch which {
        case .main: cachedAnchor = coordinator.mainLightAnchor
        case .fill: cachedAnchor = coordinator.fillLightAnchor
        }
        if let cached = cachedAnchor {
            arView.scene.removeAnchor(cached)
        }
        switch which {
        case .main: coordinator.mainLightAnchor = nil
        case .fill: coordinator.fillLightAnchor = nil
        }
        // Provision the new one (or none if the new slot is .disabled).
        provisionARLightSlot(which, slot: slot, in: arView, coordinator: coordinator)
        // Update the cache so the next frame's diff is a no-op.
        switch which {
        case .main: coordinator.appliedMainSlot = slot
        case .fill: coordinator.appliedFillSlot = slot
        }
    }

    // MARK: - Exposure helpers

    /// Applies (or removes) a brightness post-process callback on `arView`, the
    /// RealityKit stand-in for Android's `cameraExposure` EV override.
    ///
    /// **This is rendered-frame brightness, not capture exposure.** The filter runs
    /// on the composited frame — camera feed *and* virtual content — after ARKit's
    /// auto-exposure has already metered the capture. It brightens or darkens what
    /// is on screen; it does not change the camera's shutter/ISO, so it cannot
    /// recover blown highlights or crushed shadows. Real capture exposure would need
    /// `ARConfiguration.configurableCaptureDeviceForPrimaryCamera`.
    ///
    /// An EV of `+1` maps to +0.5 `CIColorControls` brightness, `-1` to -0.5, so
    /// ±2 EV covers the filter's full [-1, 1] range. Filament on Android uses
    /// physical aperture/shutter/ISO; RealityKit exposes none of those.
    ///
    /// Every installed post-process callback MUST write the destination texture or
    /// RealityKit displays nothing for that frame — a black flicker, or a frozen
    /// black screen while the condition lasts. Both failure paths below therefore
    /// blit the source into the target instead of returning early, and the
    /// `CIContext` is created once per view rather than once per frame.
    @available(iOS 15.0, *)
    fileprivate static func applyExposurePostProcess(
        _ ev: Float,
        to arView: ARView,
        cache: ExposureContextCache
    ) {
        arView.renderCallbacks.postProcess = { [ev, cache] context in
            guard
                let filter = CIFilter(name: "CIColorControls"),
                // CIImage(mtlTexture:) is failable — texture format must be supported.
                let ciImage = CIImage(mtlTexture: context.sourceColorTexture, options: nil)
            else { return ARSceneView.passThrough(context) }
            let brightness = NSNumber(value: Double(ev) * 0.5)
            filter.setValue(ciImage, forKey: kCIInputImageKey)
            filter.setValue(brightness, forKey: kCIInputBrightnessKey)
            guard let outputImage = filter.outputImage else {
                return ARSceneView.passThrough(context)
            }
            cache.context(for: context.device).render(
                outputImage,
                to: context.targetColorTexture,
                commandBuffer: context.commandBuffer,
                bounds: outputImage.extent,
                colorSpace: CGColorSpaceCreateDeviceRGB()
            )
        }
    }

    /// Copies the unprocessed frame into the destination texture so a failed
    /// filter degrades to "no exposure adjustment" instead of "no frame".
    @available(iOS 15.0, *)
    private static func passThrough(_ context: ARView.PostProcessContext) {
        guard let blit = context.commandBuffer.makeBlitCommandEncoder() else { return }
        blit.copy(from: context.sourceColorTexture, to: context.targetColorTexture)
        blit.endEncoding()
    }

    public func makeCoordinator() -> Coordinator {
        Coordinator(
            configuration: configuration,
            onTapOnPlane: onTapOnPlane,
            onImageDetected: onImageDetected,
            onFrame: onFrame
        )
    }

    private var coachingGoal: ARCoachingOverlayView.Goal {
        switch planeDetection {
        case .horizontal: return .horizontalPlane
        case .vertical: return .verticalPlane
        case .both, .none: return .anyPlane
        }
    }

    // MARK: - Coordinator

    /// Holds the one `CIContext` used by the exposure post-process. Building a
    /// `CIContext` per frame allocated Metal state 60 times a second; it is
    /// created on first use and lives as long as the coordinator.
    final class ExposureContextCache: @unchecked Sendable {
        private var cached: CIContext?

        func context(for device: MTLDevice) -> CIContext {
            if let cached = cached { return cached }
            let context = CIContext(mtlDevice: device)
            cached = context
            return context
        }
    }

    public class Coordinator: NSObject, ARSessionDelegate {
        /// One `CIContext` per view for the exposure post-process (#exposure).
        let exposureContextCache = ExposureContextCache()

        var onTapOnPlane: ((SIMD3<Float>, ARView) -> Void)?
        var onImageDetected: ((String, AnchorNode, ARView) -> Void)?
        var onFrame: ((ARFrame, ARView) -> Void)?
        /// Host handler for session failures — see ``ARSceneView/onSessionError(_:)``.
        var onSessionError: ((Error, ARView) -> Void)?
        var onSessionEvent: ((ARSessionEvent, ARView) -> Void)?
        var onSessionStateChange: ((ARSessionState, ARView) -> Void)?
        var onTrackingStateChange: ((ARTrackingStatus, ARView) -> Void)?
        /// The container-level observer from the environment, if any — see
        /// ``SwiftUI/View/arSessionObserver(_:)``. Weak: the environment value
        /// is owned by whoever installed it.
        weak var sessionObserver: ARSceneSessionObserver?

        /// Whether `makeUIView` actually ran an AR session. `false` when the
        /// requested configuration is unsupported: the view then installs no
        /// overlays and never calls `onSessionStarted`.
        var sessionDidStart = false

        /// The configuration this view last asked ARKit to run. Retained so
        /// an interruption resumes with exactly it (image database, mesh,
        /// semantics included — #928), and so `updateUIView` can diff the
        /// host's next value against it. `nil` until the first run.
        var appliedConfiguration: ARSessionConfiguration?
        /// The last configuration this device refused, so a render that asks
        /// for the same one again is not reported again. Cleared by a run.
        var refusedConfiguration: ARSessionConfiguration?

        /// The configuration the coordinator was created with — what the
        /// first run uses, and the fallback when nothing has been applied.
        var configuration: ARSessionConfiguration

        /// Last ``ARSessionState`` reported to the host; transitions are
        /// emitted only when this changes. `internal` for the routing tests.
        private(set) var sessionState: ARSessionState?

        /// Last tracking status reported; `didUpdate frame` compares against
        /// it so the host hears one change, not sixty a second.
        private(set) var trackingStatus: ARTrackingStatus?

        /// `true` between a run / interruption end and the next frame, so
        /// ``ARSessionEvent/firstFrame`` fires exactly once per resumption.
        var awaitingFirstFrame = false

        /// Exposure value currently installed (`nil` = none). Diffed by
        /// `applyExposure` so the post-process closure is built once per
        /// change, not once per render.
        var appliedExposure: Float?
        /// Whether the post-process slot on the `ARView` holds OUR closure.
        /// Only then does clearing the exposure clear the slot; a callback
        /// another owner installed is never erased by this view.
        var ownsPostProcess = false

        var planeDetection: PlaneDetectionMode { configuration.planeDetection }
        var faceTracking: Bool { configuration.mode == .faceTracking }
        weak var arView: ARView?
        /// Reference-image anchors already handed to `onImageDetected`, keyed by
        /// ARKit's per-anchor `identifier` — NOT by image name. Keying by name
        /// meant a target that left and re-entered the camera was reported once
        /// and never again, and that two prints of the same reference image
        /// could only ever produce one anchor. Entries are cleared in
        /// `session(_:didRemove:)`.
        var trackedImageAnchors: [UUID: String] = [:]

        /// Whether detected planes should be visualized with a translucent
        /// overlay. Set from `makeUIView` once plane detection is known.
        var showPlaneOverlay: Bool = false

        /// The coaching overlay currently installed as a subview, if any.
        /// `internal` so the toggle + teardown contracts are testable.
        var coachingOverlay: ARCoachingOverlayView?

        /// Translucent overlay entities keyed by their `ARPlaneAnchor` id, so
        /// `didUpdate` can resize them and `didRemove` can tear them down.
        /// Cleared by ``tearDownScene(in:)`` on view dismantle (#2407).
        /// `internal` (not `private`) so the teardown leak test can provision
        /// overlays — `ARPlaneAnchor` has no public initializer, so the live
        /// `session(_:didAdd:)` path cannot be exercised headlessly.
        var planeOverlays: [UUID: PlaneVisualizer] = [:]

        /// Whether the centre-screen placement reticle is active. Set from
        /// `makeUIView` / `updateUIView`; consumed once per AR frame in
        /// ``session(_:didUpdate:)`` (#894 — Android `PlacementReticle` parity).
        var showPlacementReticle: Bool = false

        /// Whether anchors placed via `onTapOnPlane` automatically receive
        /// `GroundingShadowComponent(castsShadow: true)` on their model
        /// entities (#894 — Android `ShadowReceiverPlane` analogue).
        var groundingShadows: Bool = true

        /// World anchor hosting the reticle disc. Created lazily on the first
        /// successful raycast, hidden (`isEnabled = false`) while the ray
        /// misses, removed on teardown (#2407/#2408 pattern) or when
        /// `showPlacementReticle` turns false. `internal` so the teardown leak
        /// test can provision it headlessly (a headless raycast never hits).
        var reticleAnchor: AnchorEntity?

        /// Smoothing state for the reticle pose. `nil` after a miss so the next
        /// surface is re-acquired verbatim — the same reset contract as
        /// Android's `ReticleOrientationSmoother` (#2582).
        private var reticleOrientation: simd_quatf?
        private var reticlePosition: SIMD3<Float>?

        /// Per-frame slerp/lerp fraction toward the raycast pose — ARCore Depth
        /// Lab's `OrientedReticle` damping value, shared with Android's
        /// `PlacementReticleNode.DEFAULT_ORIENTATION_SMOOTHING`.
        static let reticleSmoothing: Float = 0.75

        /// Reticle disc footprint (metres) — visual parity with the built-in
        /// disc of Android's `PlacementReticle` (`PlacementScene.RETICLE_RADIUS`
        /// = 0.07 m radius → 14 cm diameter).
        static let reticleDiameter: Float = 0.14

        // Light-slot reactive plumbing — same pattern as ``SceneView`` (#1017)
        // adapted for AR. The anchor refs let the diff in `refreshARLightSlot`
        // tear down the previous light's `AnchorEntity` before adding a new one.
        // `applied{Main,Fill}Slot` is the cached previous value; the diff is a
        // no-op when it equals the current `LightSlot`.
        /// The SDK's own tap recognizer, installed only while `onTapOnPlane`
        /// is non-nil so a host recognizer on the same `ARView` is never
        /// shadowed by a no-op one. `internal` so tests can assert the
        /// install/remove contract.
        var tapRecognizer: UITapGestureRecognizer?

        var mainLightAnchor: AnchorEntity?
        var fillLightAnchor: AnchorEntity?
        var appliedMainSlot: LightSlot?
        var appliedFillSlot: LightSlot?

        init(
            configuration: ARSessionConfiguration,
            onTapOnPlane: ((SIMD3<Float>, ARView) -> Void)? = nil,
            onImageDetected: ((String, AnchorNode, ARView) -> Void)? = nil,
            onFrame: ((ARFrame, ARView) -> Void)? = nil
        ) {
            self.configuration = configuration
            self.onTapOnPlane = onTapOnPlane
            self.onImageDetected = onImageDetected
            self.onFrame = onFrame
        }

        /// Parameter-wise initializer kept for the existing tests; builds the
        /// equivalent ``ARSessionConfiguration``.
        convenience init(
            onTapOnPlane: ((SIMD3<Float>, ARView) -> Void)?,
            planeDetection: PlaneDetectionMode,
            onImageDetected: ((String, AnchorNode, ARView) -> Void)? = nil,
            onFrame: ((ARFrame, ARView) -> Void)? = nil,
            imageTrackingDatabase: Set<ARReferenceImage>? = nil,
            enableMeshReconstruction: Bool = false,
            environmentTexturing: ARWorldTrackingConfiguration.EnvironmentTexturing = .automatic,
            faceTracking: Bool = false
        ) {
            self.init(
                configuration: ARSessionConfiguration(
                    mode: faceTracking ? .faceTracking : .worldTracking,
                    planeDetection: planeDetection,
                    imageTrackingDatabase: imageTrackingDatabase,
                    environmentTexturing: environmentTexturing,
                    sceneReconstruction: enableMeshReconstruction ? .mesh : .none
                ),
                onTapOnPlane: onTapOnPlane,
                onImageDetected: onImageDetected,
                onFrame: onFrame
            )
        }

        // MARK: - Exposure ownership

        /// Owns the post-process slot only while an exposure override is set.
        ///
        /// - `nil` → `nil`: never touches `renderCallbacks.postProcess`. The
        ///   default camera path installs no filter and does not erase a
        ///   callback another owner (the host, a recorder) put there.
        /// - same value: no-op. Before, the closure was rebuilt and
        ///   reinstalled on every SwiftUI render.
        /// - new value: installs the closure once, remembers it owns the slot.
        /// - value → `nil`: removes the callback, but only if it is still ours.
        @MainActor
        func applyExposure(_ ev: Float?, on arView: ARView) {
            guard appliedExposure != ev else { return }
            appliedExposure = ev
            guard #available(iOS 15.0, *) else { return }   // stored, silently ignored
            guard let ev else {
                if ownsPostProcess {
                    arView.renderCallbacks.postProcess = nil
                    ownsPostProcess = false
                }
                return
            }
            ARSceneView.applyExposurePostProcess(ev, to: arView, cache: exposureContextCache)
            ownsPostProcess = true
        }

        // MARK: - Session lifecycle

        /// Runs `configuration` on the view's session with this coordinator
        /// as delegate — installed BEFORE `run`, so the first delegate message
        /// cannot be missed — records it as the configuration to resume with,
        /// and reports `started` / `starting`.
        ///
        /// `resetTracking` is `true` for the first run and for a change of
        /// ``ARSessionConfiguration/Mode``; every other re-run keeps the
        /// world map and the host's anchors.
        @MainActor
        func run(
            _ configuration: ARSessionConfiguration,
            on arView: ARView,
            resetTracking: Bool
        ) {
            arView.session.delegate = self
            self.arView = arView
            if resetTracking {
                // Tracking starts from scratch: the detected image anchors are
                // gone, forget them or the same target is never reported again.
                trackedImageAnchors.removeAll()
                for visualizer in planeOverlays.values {
                    arView.scene.removeAnchor(visualizer.anchor)
                }
                planeOverlays.removeAll()
            }
            arView.session.run(
                configuration.makeARConfiguration(),
                options: resetTracking ? [.resetTracking, .removeExistingAnchors] : []
            )
            appliedConfiguration = configuration
            refusedConfiguration = nil
            self.configuration = configuration
            sessionDidStart = true
            awaitingFirstFrame = true
            trackingStatus = nil
            emit(.started(configuration), in: arView)
            transition(to: .starting, in: arView)
        }

        /// Applies `configuration` to the live session when it differs from
        /// the one last applied — or runs it, when no configuration ever ran.
        /// A requirement the device cannot meet is reported once, as a
        /// ``ARSessionEvent/failed(_:)`` event to the closure and the
        /// environment observer and through the legacy `onSessionError`, and
        /// the previous configuration stays live — the session is never torn
        /// down over a toggle, and its state stays what it was.
        @MainActor
        func applyIfChanged(
            _ configuration: ARSessionConfiguration,
            on arView: ARView,
            capabilities: ARSessionConfiguration.Capabilities = .current
        ) {
            guard configuration != appliedConfiguration,
                  configuration != refusedConfiguration else { return }
            if let missing = configuration.unmetRequirement(capabilities: capabilities) {
                let error: ARSceneViewError =
                    missing == .faceTracking ? .faceTrackingUnsupported : .unsupported(missing)
                print("[SceneViewSwift] AR configuration not applied: \(error.localizedDescription)")
                refusedConfiguration = configuration
                emit(.failed(error), in: arView)
                onSessionError?(error, arView)
                return
            }
            run(
                configuration,
                on: arView,
                resetTracking: configuration.requiresTrackingReset(from: appliedConfiguration)
            )
        }

        /// Reports a failure that prevented the session from starting (or
        /// stopped it): `failed` state, `failed` event, the legacy
        /// `onSessionError` closure.
        @MainActor
        func reportFailure(_ error: Error, in arView: ARView) {
            transition(to: .failed, in: arView)
            emit(.failed(error), in: arView)
            onSessionError?(error, arView)
        }

        /// Records and reports a new ``ARSessionState``, once per change.
        @MainActor
        func transition(to state: ARSessionState, in arView: ARView) {
            guard sessionState != state else { return }
            sessionState = state
            onSessionStateChange?(state, arView)
        }

        /// Sends `event` to the view's own closure and to the environment
        /// observer, in that order.
        @MainActor
        func emit(_ event: ARSessionEvent, in arView: ARView) {
            onSessionEvent?(event, arView)
            sessionObserver?.arSession(didEmit: event, in: arView)
        }

        /// Per-frame lifecycle bookkeeping, split from the delegate method so
        /// the routing is testable without an `ARFrame` (which has no public
        /// initializer): the first frame after a run / resumption flips the
        /// state to `running`; a tracking change is reported once.
        @MainActor
        func noteFrame(trackingState: ARCamera.TrackingState, in arView: ARView) {
            if awaitingFirstFrame {
                awaitingFirstFrame = false
                emit(.firstFrame, in: arView)
                transition(to: .running, in: arView)
            }
            let status = ARTrackingStatus(trackingState)
            if status != trackingStatus {
                trackingStatus = status
                emit(.trackingStateChanged(status), in: arView)
                onTrackingStateChange?(status, arView)
            }
        }

        /// Interruption bookkeeping, split from the delegate for the same
        /// reason as ``noteFrame(trackingState:in:)``.
        @MainActor
        func noteInterruption(in arView: ARView) {
            emit(.interrupted, in: arView)
            transition(to: .interrupted, in: arView)
        }

        /// Resumes after an interruption with the configuration the session
        /// was running. Never resets tracking: with
        /// `sessionShouldAttemptRelocalization` ARKit relocalizes into the
        /// existing map and the host's anchors stay put. The next frame
        /// reports `firstFrame` / `running` again.
        @MainActor
        func resumeAfterInterruption(_ session: ARSession, in arView: ARView?) {
            // The configuration the session still holds is the authoritative
            // one: it carries whatever the host mutated after start —
            // `frameSemantics` (people occlusion), `sceneReconstruction`
            // (`.meshWithClassification`), a swapped image database.
            // Rebuilding a stock configuration here threw all of that away
            // (#928 follow-up).
            if let activeConfiguration = session.configuration {
                session.run(activeConfiguration)
            } else {
                // No active configuration means there is nothing to
                // relocalize into: rebuild from the retained value — the
                // one the host asked for, image database and all.
                print("[SceneViewSwift] AR session interruption ended — no active configuration, restarting")
                trackedImageAnchors.removeAll()
                session.run(
                    (appliedConfiguration ?? configuration).makeARConfiguration(),
                    options: [.resetTracking]
                )
            }
            awaitingFirstFrame = true
            trackingStatus = nil
            guard let arView else { return }
            emit(.interruptionEnded, in: arView)
        }

        /// Tears down every RealityKit/ARKit resource this coordinator owns:
        /// pauses the AR session, detaches the session delegate, removes the
        /// translucent plane overlays (#2407) and the dual light anchors
        /// (#2408) from `arView.scene`, and clears all strong references so the
        /// entities deallocate with the view instead of leaking.
        ///
        /// Called from ``ARSceneView/dismantleUIView(_:coordinator:)`` on the
        /// main actor while the view is alive. Idempotent — a second call (e.g.
        /// from `deinit`) is a no-op because the collections are already empty
        /// and the anchor references already `nil`, so there is no double-free.
        @MainActor
        func tearDownScene(in arView: ARView) {
            // Plane overlays (#2407) — remove each translucent fill anchor from
            // the scene, then drop the strong dictionary references.
            for visualizer in planeOverlays.values {
                arView.scene.removeAnchor(visualizer.anchor)
            }
            planeOverlays.removeAll()

            // Light anchors (#2408) — mirrors the `removeAnchor` in
            // `refreshARLightSlot`; the cached refs (#2278 pattern) let us
            // detach them directly without walking `scene.anchors`.
            if let main = mainLightAnchor { arView.scene.removeAnchor(main) }
            if let fill = fillLightAnchor { arView.scene.removeAnchor(fill) }
            mainLightAnchor = nil
            fillLightAnchor = nil

            // Placement reticle (#894) — same coordinator-owned-anchor rule as
            // the overlays and lights above.
            if let reticle = reticleAnchor { arView.scene.removeAnchor(reticle) }
            reticleAnchor = nil

            // Coaching overlay — detach from the session being paused and drop
            // the subview, otherwise it outlives the AR session it observes.
            if let overlay = coachingOverlay {
                overlay.setActive(false, animated: false)
                overlay.session = nil
                overlay.removeFromSuperview()
                coachingOverlay = nil
            }

            // Tap recognizer — detach so the torn-down coordinator is no longer
            // a gesture target on a view the host may keep alive.
            if let recognizer = tapRecognizer {
                arView.removeGestureRecognizer(recognizer)
                tapRecognizer = nil
            }

            // Exposure post-process — release the slot only if it holds our
            // closure; another owner's callback is left alone.
            if ownsPostProcess {
                if #available(iOS 15.0, *) { arView.renderCallbacks.postProcess = nil }
                ownsPostProcess = false
            }
            appliedExposure = nil

            // Stop the AR session so the camera + sensor pipeline goes idle and
            // ARKit drops its hold; detach the delegate so no late frame
            // callback fires into a torn-down coordinator.
            arView.session.pause()
            arView.session.delegate = nil
            sessionDidStart = false
            awaitingFirstFrame = false
        }

        deinit {
            // Safety net for the teardown path (#2407). `dismantleUIView` is the
            // primary, deterministic teardown (SwiftUI calls it before releasing
            // the coordinator), but if the coordinator is ever released without
            // it, break the strong-reference graph here so the plane-overlay and
            // light entities still deallocate.
            //
            // Mirrors `SceneEntities.deinit` (#2038/#2068): a non-`@MainActor`
            // class deinits on whatever thread drops the last reference, and
            // touching RealityKit off-main traps the process. Always release the
            // strong refs (thread-agnostic); detach from the live scene only
            // when we can confirm main-actor isolation and the view still
            // exists. No escaping task, no heap escape — safe in `deinit`.
            let overlayAnchors = planeOverlays.values.map(\.anchor)
            let main = mainLightAnchor
            let fill = fillLightAnchor
            let reticle = reticleAnchor
            planeOverlays.removeAll()
            mainLightAnchor = nil
            fillLightAnchor = nil
            reticleAnchor = nil
            guard let arView = arView,
                  !overlayAnchors.isEmpty || main != nil || fill != nil
                    || reticle != nil else { return }
            let detach = {
                MainActor.assumeIsolated {
                    for anchor in overlayAnchors { arView.scene.removeAnchor(anchor) }
                    if let main = main { arView.scene.removeAnchor(main) }
                    if let fill = fill { arView.scene.removeAnchor(fill) }
                    if let reticle = reticle { arView.scene.removeAnchor(reticle) }
                }
            }
            if Thread.isMainThread {
                detach()
            } else {
                DispatchQueue.main.sync(execute: detach)
            }
        }

        /// Adds, retargets or removes the coaching overlay so exactly one exists
        /// while `enabled`, and none otherwise. Idempotent; safe to call on
        /// every `updateUIView`.
        @MainActor
        func syncCoachingOverlay(
            on arView: ARView,
            enabled: Bool,
            goal: ARCoachingOverlayView.Goal
        ) {
            guard enabled else {
                if let overlay = coachingOverlay {
                    overlay.setActive(false, animated: false)
                    overlay.session = nil
                    overlay.removeFromSuperview()
                    coachingOverlay = nil
                }
                return
            }
            let overlay: ARCoachingOverlayView
            if let existing = coachingOverlay {
                overlay = existing
            } else {
                overlay = ARCoachingOverlayView()
                overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
                overlay.frame = arView.bounds
                overlay.session = arView.session
                overlay.activatesAutomatically = true
                arView.addSubview(overlay)
                coachingOverlay = overlay
            }
            if overlay.goal != goal { overlay.goal = goal }
        }

        /// Turns the translucent detected-plane overlays on or off after view
        /// creation. Turning them on adopts the planes ARKit has already
        /// detected instead of waiting for the next `didAdd`; turning them off
        /// removes every overlay anchor currently in the scene.
        @MainActor
        func syncPlaneOverlay(on arView: ARView, enabled: Bool) {
            guard enabled != showPlaneOverlay else { return }
            showPlaneOverlay = enabled
            guard enabled else {
                for visualizer in planeOverlays.values {
                    arView.scene.removeAnchor(visualizer.anchor)
                }
                planeOverlays.removeAll()
                return
            }
            for anchor in arView.session.currentFrame?.anchors ?? [] {
                guard let planeAnchor = anchor as? ARPlaneAnchor,
                      planeOverlays[planeAnchor.identifier] == nil else { continue }
                let visualizer = PlaneVisualizer(planeAnchor: planeAnchor)
                planeOverlays[planeAnchor.identifier] = visualizer
                arView.scene.addAnchor(visualizer.anchor)
            }
        }

        /// Adds or removes the SDK tap recognizer so exactly one exists while
        /// `enabled`, and none otherwise. Idempotent.
        @MainActor
        func syncTapRecognizer(on arView: ARView, enabled: Bool) {
            if enabled {
                guard tapRecognizer == nil else { return }
                let recognizer = UITapGestureRecognizer(
                    target: self,
                    action: #selector(Coordinator.handleTap(_:))
                )
                arView.addGestureRecognizer(recognizer)
                tapRecognizer = recognizer
            } else if let recognizer = tapRecognizer {
                arView.removeGestureRecognizer(recognizer)
                tapRecognizer = nil
            }
        }

        @objc func handleTap(_ recognizer: UITapGestureRecognizer) {
            guard let arView = recognizer.view as? ARView else { return }
            let location = recognizer.location(in: arView)

            // Raycast against detected planes
            let results = arView.raycast(
                from: location,
                allowing: .estimatedPlane,
                alignment: .any
            )
            guard let firstResult = results.first,
                  let onTapOnPlane = onTapOnPlane else { return }
            let column = firstResult.worldTransform.columns.3
            let position = SIMD3<Float>(column.x, column.y, column.z)

            // Grounding shadows (#894): snapshot the scene's anchors, let the
            // callback place its content, then give every model entity the
            // callback anchored a RealityKit contact shadow. Covers the
            // documented synchronous placement flow; asynchronously anchored
            // content must set `GroundingShadowComponent` itself.
            let anchorsBefore = groundingShadows
                ? Set(arView.scene.anchors.map(\.id)) : []
            onTapOnPlane(position, arView)
            if groundingShadows {
                applyGroundingShadows(in: arView, addedSince: anchorsBefore)
            }
        }

        /// Applies `GroundingShadowComponent(castsShadow: true)` to every model
        /// entity under anchors that joined `arView.scene` after the snapshot —
        /// the RealityKit analogue of Android's invisible `ShadowReceiverPlane`
        /// (#2241/#894): RealityKit itself renders the contact shadow onto the
        /// detected real-world surface, no shadow-catcher geometry needed.
        func applyGroundingShadows(in arView: ARView, addedSince existing: Set<Entity.ID>) {
            for anchor in arView.scene.anchors where !existing.contains(anchor.id) {
                Self.applyGroundingShadow(to: anchor)
            }
        }

        /// Recursively sets the grounding-shadow component on `entity` and its
        /// descendants that render a model. Idempotent — re-setting the
        /// component on an entity that already has one is a no-op overwrite.
        static func applyGroundingShadow(to entity: Entity) {
            if entity.components.has(ModelComponent.self) {
                entity.components.set(GroundingShadowComponent(castsShadow: true))
            }
            for child in entity.children {
                applyGroundingShadow(to: child)
            }
        }

        // MARK: - Placement reticle (#894)

        /// Per-frame reticle update, driven from ``session(_:didUpdate:)`` —
        /// ARKit delivers session callbacks on the main thread for
        /// `ARSceneView` (no `delegateQueue` override), so touching RealityKit
        /// and `arView.bounds` here is safe. `internal` so tests can drive the
        /// disabled/teardown paths headlessly.
        func updatePlacementReticle(in arView: ARView) {
            guard showPlacementReticle else {
                // Flag turned off (or never on): tear the reticle down.
                if let anchor = reticleAnchor {
                    arView.scene.removeAnchor(anchor)
                    reticleAnchor = nil
                }
                reticleOrientation = nil
                reticlePosition = nil
                return
            }

            // The same query the tap-to-place path uses, cast from the screen
            // centre — what the reticle shows is exactly where a tap lands.
            let center = CGPoint(x: arView.bounds.midX, y: arView.bounds.midY)
            guard let result = arView.raycast(
                from: center,
                allowing: .estimatedPlane,
                alignment: .any
            ).first else {
                // Ray misses every surface: hide, and forget the damping state
                // so the next surface is re-acquired verbatim (Android
                // `ReticleOrientationSmoother.reset()` contract).
                reticleAnchor?.isEnabled = false
                reticleOrientation = nil
                reticlePosition = nil
                return
            }

            let anchor = reticleAnchor ?? makeReticleAnchor(in: arView)
            anchor.isEnabled = true

            let target = Transform(matrix: result.worldTransform)
            // Exponential per-frame damping toward the hit pose (factor 0.75,
            // Depth Lab / Android PlacementReticle parity) — kills the jitter
            // of ARKit's frame-to-frame surface refinement. The first sample
            // after a miss applies verbatim, so the reticle never "rolls in".
            let rotation = reticleOrientation.map {
                simd_slerp($0, target.rotation, Self.reticleSmoothing)
            } ?? target.rotation
            let position = reticlePosition.map {
                simd_mix($0, target.translation, SIMD3<Float>(repeating: Self.reticleSmoothing))
            } ?? target.translation
            reticleOrientation = rotation
            reticlePosition = position
            anchor.transform = Transform(
                scale: .one, rotation: rotation, translation: position
            )
        }

        /// Builds the reticle — a thin translucent disc in the design-system
        /// cyan, matching Android `PlacementReticle`'s built-in disc
        /// (`PlacementScene.DEFAULT_RETICLE_COLOR` = #44E7FF at ~60 % alpha),
        /// hosted on a world anchor whose transform tracks the smoothed
        /// raycast pose.
        private func makeReticleAnchor(in arView: ARView) -> AnchorEntity {
            let anchor = AnchorEntity(world: .zero)
            let mesh = MeshResource.generatePlane(
                width: Self.reticleDiameter,
                depth: Self.reticleDiameter,
                cornerRadius: Self.reticleDiameter / 2
            )
            // Alpha rides the blending opacity, not the tint: RealityKit is
            // known to drop the base-color alpha on some material paths, which
            // would render the disc fully opaque.
            var material = UnlitMaterial(
                color: .init(red: 0x44 / 255.0, green: 0xE7 / 255.0, blue: 1.0, alpha: 1.0)
            )
            material.blending = .transparent(opacity: .init(floatLiteral: 0.6))
            let disc = ModelEntity(mesh: mesh, materials: [material])
            // Nudge off the surface along the plane normal so the disc never
            // z-fights the detected-plane overlay (a flat plane).
            disc.position.y = 0.002
            anchor.addChild(disc)
            arView.scene.addAnchor(anchor)
            reticleAnchor = anchor
            return anchor
        }

        // MARK: - ARSessionDelegate

        public func session(_ session: ARSession, didUpdate frame: ARFrame) {
            guard let arView = arView else { return }
            // ARKit delivers to `ARSceneView`'s delegate on the main queue (no
            // `delegateQueue` override), which is what makes the RealityKit
            // work below safe — and what `assumeIsolated` asserts.
            MainActor.assumeIsolated {
                noteFrame(trackingState: frame.camera.trackingState, in: arView)
            }
            updatePlacementReticle(in: arView)
            onFrame?(frame, arView)
        }

        public func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
            guard let arView = arView else { return }

            for anchor in anchors {
                // Detected-plane visualization — a subtle translucent overlay,
                // never the opaque `.showAnchorGeometry` debug fill (#1557).
                if showPlaneOverlay, let planeAnchor = anchor as? ARPlaneAnchor {
                    let visualizer = PlaneVisualizer(planeAnchor: planeAnchor)
                    planeOverlays[planeAnchor.identifier] = visualizer
                    arView.scene.addAnchor(visualizer.anchor)
                    continue
                }

                guard let onImageDetected = onImageDetected,
                      let imageAnchor = anchor as? ARImageAnchor,
                      let imageName = imageAnchor.referenceImage.name,
                      trackedImageAnchors[imageAnchor.identifier] == nil else { continue }

                trackedImageAnchors[imageAnchor.identifier] = imageName
                let anchorEntity = AnchorEntity(anchor: imageAnchor)
                let anchorNode = AnchorNode(entity: anchorEntity)
                onImageDetected(imageName, anchorNode, arView)
            }
        }

        public func session(_ session: ARSession, didUpdate anchors: [ARAnchor]) {
            for anchor in anchors {
                guard let planeAnchor = anchor as? ARPlaneAnchor,
                      let visualizer = planeOverlays[planeAnchor.identifier] else { continue }
                visualizer.update(with: planeAnchor)
            }
        }

        public func session(_ session: ARSession, didRemove anchors: [ARAnchor]) {
            for anchor in anchors {
                // Forget the image anchor so the same target is reported again
                // if ARKit re-detects it.
                if let imageAnchor = anchor as? ARImageAnchor {
                    trackedImageAnchors.removeValue(forKey: imageAnchor.identifier)
                    continue
                }
                guard let planeAnchor = anchor as? ARPlaneAnchor,
                      let visualizer = planeOverlays.removeValue(forKey: planeAnchor.identifier) else { continue }
                arView?.scene.removeAnchor(visualizer.anchor)
            }
        }

        public func session(
            _ session: ARSession,
            didFailWithError error: Error
        ) {
            // The print stays as the fallback for hosts that install no handler.
            print("[SceneViewSwift] AR session error: \(error.localizedDescription)")
            guard let arView = arView else { return }
            MainActor.assumeIsolated { reportFailure(error, in: arView) }
        }

        public func sessionWasInterrupted(_ session: ARSession) {
            print("[SceneViewSwift] AR session interrupted")
            guard let arView = arView else { return }
            MainActor.assumeIsolated { noteInterruption(in: arView) }
        }

        /// Lets ARKit try to relocalize into the world map that was built before
        /// the interruption instead of dropping it. Without this the session
        /// silently restarts tracking and every anchor the host placed ends up in
        /// a new coordinate space.
        public func sessionShouldAttemptRelocalization(_ session: ARSession) -> Bool {
            true
        }

        public func sessionInterruptionEnded(_ session: ARSession) {
            print("[SceneViewSwift] AR session interruption ended — resuming")
            MainActor.assumeIsolated { resumeAfterInterruption(session, in: arView) }
        }
    }
}

// MARK: - Detected-plane visualization (#1557)

/// Renders a single detected `ARPlaneAnchor` as a *subtle, translucent*
/// overlay so the user can see where surfaces have been found without the
/// real-world camera feed being obscured.
///
/// This replaces RealityKit's `.showAnchorGeometry` debug option, which
/// renders detected planes as a solid, fully-opaque fluorescent-green fill —
/// a developer debug aid that must never reach end users (#1557, part of
/// #1373). The overlay here uses a low-opacity tinted material (~12 %),
/// matching how a polished ARKit app surfaces plane detection.
///
/// The overlay is purely cosmetic — it carries no `CollisionComponent`, so
/// it never interferes with the tap-to-place raycast in `handleTap`, which
/// raycasts against ARKit's `.estimatedPlane`, not scene geometry.
///
/// Not `@MainActor`-isolated: it is only ever constructed and mutated from
/// `ARSessionDelegate` callbacks, which ARKit delivers on the session's
/// delegate queue (the main thread for `ARSceneView`). This matches the
/// surrounding image-anchor handling in `session(_:didAdd:)`.
final class PlaneVisualizer {
    /// The world-anchored entity that hosts the overlay mesh. Added to /
    /// removed from `arView.scene` by the coordinator.
    let anchor: AnchorEntity

    /// The translucent fill entity, regenerated on each `update` because the
    /// plane's `extent` grows as ARKit refines its estimate.
    private let fill: ModelEntity

    /// Tint of the plane overlay. Opaque here on purpose: RealityKit drops
    /// the base-colour alpha on the unlit material path, so the translucency
    /// is carried by ``overlayOpacity`` through `material.blending` — the
    /// same rule the placement reticle follows. With alpha in the colour and
    /// `.transparent(opacity: 1.0)` in the blending (the previous shipping
    /// combination), the "12 % white" rendered as a fully opaque white slab
    /// over the detected surface.
    private static let overlayColor: SimpleMaterial.Color = .white

    /// Overlay opacity — the "subtle translucent" of the #1557 intent, now
    /// actually applied.
    static let overlayOpacity: Float = 0.12

    /// Hosts the overlay on an anchor bound to the detected plane's pose,
    /// then sizes the translucent fill to the plane's current extent.
    convenience init(planeAnchor: ARPlaneAnchor) {
        self.init(anchor: AnchorEntity(anchor: planeAnchor))
        update(with: planeAnchor)
    }

    /// Designated initializer — wires the translucent fill onto a caller-
    /// provided anchor. Split out from the plane-anchor binding so the teardown
    /// leak test can provision an overlay headlessly (`ARPlaneAnchor` has no
    /// public initializer, so the live detection path is untestable off-device).
    init(anchor: AnchorEntity) {
        self.anchor = anchor
        fill = ModelEntity()
        anchor.addChild(fill)
    }

    /// Resizes and re-centers the overlay to match the latest plane estimate.
    func update(with planeAnchor: ARPlaneAnchor) {
        let extent = planeAnchor.planeExtent
        // A flat plane, not a box: a box has four side faces that catch the
        // light at grazing angles and read as a raised slab. The plane's
        // local frame is X/Z, so width maps to X and depth to Z.
        let mesh = MeshResource.generatePlane(
            width: extent.width,
            depth: extent.height
        )
        var material = UnlitMaterial(color: Self.overlayColor)
        // Transparent blending with the REAL opacity so the camera feed shows
        // through — the core of the #1557 intent.
        material.blending = .transparent(opacity: .init(floatLiteral: Self.overlayOpacity))
        fill.model = ModelComponent(mesh: mesh, materials: [material])
        // `center` is the plane center relative to the anchor's transform.
        fill.position = [
            planeAnchor.center.x,
            planeAnchor.center.y,
            planeAnchor.center.z,
        ]
    }
}

// MARK: - AR Anchor helpers (mirrors Android's ARCore anchor API)

/// A wrapper around ARKit anchors for placing content in the real world.
///
/// Mirrors SceneView Android's `AnchorNode`.
public struct AnchorNode: Sendable {
    /// The underlying RealityKit anchor entity.
    public let entity: AnchorEntity

    /// Creates an anchor at a world position.
    public static func world(position: SIMD3<Float>) -> AnchorNode {
        let anchor = AnchorEntity(world: position)
        return AnchorNode(entity: anchor)
    }

    /// Creates an anchor on a detected plane.
    ///
    /// - Parameters:
    ///   - alignment: Horizontal or vertical plane.
    ///   - minimumBounds: Minimum plane size to anchor to.
    public static func plane(
        alignment: PlaneAlignment = .horizontal,
        minimumBounds: SIMD2<Float> = .init(0.1, 0.1)
    ) -> AnchorNode {
        let anchor: AnchorEntity
        switch alignment {
        case .horizontal:
            anchor = AnchorEntity(plane: .horizontal, minimumBounds: minimumBounds)
        case .vertical:
            anchor = AnchorEntity(plane: .vertical, minimumBounds: minimumBounds)
        }
        return AnchorNode(entity: anchor)
    }

    /// Creates an anchor that tracks a detected reference image.
    ///
    /// Mirrors SceneView Android's `AugmentedImageNode`. The image must already be
    /// registered via `ARSceneView(imageTrackingDatabase:)` so ARKit knows what to
    /// look for. Once the image is detected, content added to this anchor renders
    /// at the image's pose in the real world.
    ///
    /// - Parameters:
    ///   - group: AR Resource group name in the asset catalog (e.g. `"AR Resources"`).
    ///   - name: Reference image name within the group.
    public static func image(group: String, name: String) -> AnchorNode {
        let anchor = AnchorEntity(.image(group: group, name: name))
        return AnchorNode(entity: anchor)
    }

    /// Creates an anchor that tracks a detected face (front-camera).
    ///
    /// Mirrors SceneView Android's `AugmentedFaceNode` (closes part of #894).
    /// Requires `ARFaceTrackingConfiguration` to be active — set via
    /// `ARSceneView(faceTracking: true)` (when wired). Content added to this
    /// anchor renders attached to the user's face at runtime.
    ///
    /// **Limitation**: RealityKit's `AnchorEntity(.face)` provides face-pose
    /// tracking but no `ARSCNFaceGeometry`-equivalent mesh. For the morphing
    /// face-mesh overlay seen in Android's AugmentedFaceDemo, drop down to a
    /// raw `ARFaceAnchor` + custom mesh entity.
    public static func face() -> AnchorNode {
        let anchor = AnchorEntity(.face)
        return AnchorNode(entity: anchor)
    }

    /// Creates an anchor that tracks a detected body (rear-camera, iOS 13+).
    ///
    /// Requires `ARBodyTrackingConfiguration` to be active. Content added to
    /// this anchor renders at the detected human body's root joint.
    public static func body() -> AnchorNode {
        let anchor = AnchorEntity(.body)
        return AnchorNode(entity: anchor)
    }

    /// Adds a child entity to this anchor.
    public func add(_ child: Entity) {
        entity.addChild(child)
    }

    /// Removes a child entity from this anchor.
    public func remove(_ child: Entity) {
        entity.removeChild(child)
    }

    /// Removes all child entities from this anchor.
    public func removeAll() {
        // Snapshot the children into an `Array` first: `entity.children` is a
        // live view over the entity's child list, so calling `removeChild`
        // while iterating it directly re-indexes the collection mid-loop and
        // skips every other child (2 children → 1 left behind). See #2878.
        for child in Array(entity.children) {
            entity.removeChild(child)
        }
    }

    /// Plane alignment type matching Android's Plane.Type.
    public enum PlaneAlignment: Sendable {
        case horizontal
        case vertical
    }
}
#endif // os(iOS)
