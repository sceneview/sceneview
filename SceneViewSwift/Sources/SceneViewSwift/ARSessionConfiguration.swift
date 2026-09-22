#if os(iOS)
import ARKit
import RealityKit

/// What an ``ARSceneView`` asks ARKit to run — as one value, so the view can
/// compare it across renders, re-apply it after an interruption, and tell the
/// host up front what the device cannot do.
///
/// Every field has the default the classic
/// ``ARSceneView/init(planeDetection:showPlaneOverlay:showCoachingOverlay:showPlacementReticle:groundingShadows:cameraExposure:imageTrackingDatabase:faceTracking:onTapOnPlane:onImageDetected:onFrame:)``
/// initializer used, with one deliberate exception: ``sceneReconstruction``
/// defaults to ``SceneReconstruction/none``. Before, every world-tracking
/// session silently turned the LiDAR mesh on whenever the device had one —
/// a per-frame CPU/GPU cost that a tap-to-place screen never used. A host
/// that needs the mesh asks for it (``SceneReconstruction/mesh`` or
/// ``SceneReconstruction/meshWithClassification``), and gets an explicit
/// ``Requirement/lidar`` answer from ``unmetRequirement(capabilities:)`` on a
/// device that cannot deliver it, instead of a session that quietly runs
/// without.
///
/// ```swift
/// ARSceneView(
///     configuration: ARSessionConfiguration(planeDetection: .horizontal),
///     onTapOnPlane: { position, arView in … }
/// )
/// ```
///
/// The value is `Equatable`: ``ARSceneView`` re-runs the session only when the
/// configuration it is *asked* for changes between renders — never because of
/// an unrelated `@State` change, and never by comparing against the live
/// `ARSession.configuration`, which a host may legitimately mutate itself
/// (people occlusion, mesh classification).
public struct ARSessionConfiguration: Equatable {

    /// Which ARKit configuration class the session runs.
    public enum Mode: Equatable, Sendable {
        /// `ARWorldTrackingConfiguration` on the rear camera: plane detection,
        /// image tracking, tap-to-place.
        case worldTracking
        /// `ARFaceTrackingConfiguration` on the front TrueDepth camera. Plane
        /// detection, image tracking and tap-to-place are unavailable.
        case faceTracking
    }

    /// LiDAR scene reconstruction request. Applied only when the device
    /// supports it — a request that cannot be met is reported through
    /// ``unmetRequirement(capabilities:)`` so the host shows an unsupported
    /// state rather than a session that pretends.
    public enum SceneReconstruction: Equatable, Sendable {
        case none
        case mesh
        case meshWithClassification

        var arValue: ARWorldTrackingConfiguration.SceneReconstruction {
            switch self {
            case .none: return []
            case .mesh: return .mesh
            case .meshWithClassification: return .meshWithClassification
            }
        }
    }

    /// A device capability the configuration needs and this device lacks.
    /// Each case maps to one line of the demo's "Unsupported" state
    /// (`"Requires LiDAR."` and so on) — the host decides the copy, the SDK
    /// decides the fact.
    public enum Requirement: Equatable, Sendable {
        /// `ARWorldTrackingConfiguration.isSupported` is `false` — the iOS
        /// Simulator, or hardware older than ARKit's floor.
        case worldTracking
        /// `ARFaceTrackingConfiguration.isSupported` is `false` — no TrueDepth
        /// camera.
        case faceTracking
        /// The requested ``SceneReconstruction`` needs a LiDAR scanner.
        case lidar
        /// A requested `ARConfiguration.FrameSemantics` option (people
        /// occlusion, scene depth) is not available on this device.
        case frameSemantics(ARConfiguration.FrameSemantics)
    }

    /// What the running device can do. Injected into
    /// ``unmetRequirement(capabilities:)`` so the routing is testable on a
    /// Simulator, where ARKit reports nothing as supported.
    public struct Capabilities: Equatable, Sendable {
        public var worldTracking: Bool
        public var faceTracking: Bool
        public var sceneReconstruction: Bool
        public var sceneReconstructionWithClassification: Bool
        public var supportedFrameSemantics: ARConfiguration.FrameSemantics

        public init(
            worldTracking: Bool,
            faceTracking: Bool,
            sceneReconstruction: Bool,
            sceneReconstructionWithClassification: Bool,
            supportedFrameSemantics: ARConfiguration.FrameSemantics
        ) {
            self.worldTracking = worldTracking
            self.faceTracking = faceTracking
            self.sceneReconstruction = sceneReconstruction
            self.sceneReconstructionWithClassification = sceneReconstructionWithClassification
            self.supportedFrameSemantics = supportedFrameSemantics
        }

        /// The capabilities ARKit reports for the device this code runs on.
        public static var current: Capabilities {
            var semantics: ARConfiguration.FrameSemantics = []
            for option in [
                ARConfiguration.FrameSemantics.personSegmentation,
                .personSegmentationWithDepth,
                .sceneDepth,
                .smoothedSceneDepth,
                .bodyDetection,
            ] where ARWorldTrackingConfiguration.supportsFrameSemantics(option) {
                semantics.insert(option)
            }
            return Capabilities(
                worldTracking: ARWorldTrackingConfiguration.isSupported,
                faceTracking: ARFaceTrackingConfiguration.isSupported,
                sceneReconstruction: ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh),
                sceneReconstructionWithClassification:
                    ARWorldTrackingConfiguration.supportsSceneReconstruction(.meshWithClassification),
                supportedFrameSemantics: semantics
            )
        }
    }

    public var mode: Mode
    /// Plane orientations to detect. Ignored in ``Mode/faceTracking``.
    public var planeDetection: ARSceneView.PlaneDetectionMode
    /// Reference images to detect. Ignored in ``Mode/faceTracking``. Retained
    /// across an interruption: the database the host passed is the one the
    /// session resumes with.
    public var imageTrackingDatabase: Set<ARReferenceImage>?
    /// Environment texturing for PBR reflections — `.automatic` mirrors
    /// ARCore's `ENVIRONMENTAL_HDR`, the Android default since v4.3.0.
    public var environmentTexturing: ARWorldTrackingConfiguration.EnvironmentTexturing
    /// LiDAR mesh request. Off by default — see the type overview.
    public var sceneReconstruction: SceneReconstruction
    /// Extra per-frame semantics (people occlusion, scene depth). Empty by
    /// default.
    public var frameSemantics: ARConfiguration.FrameSemantics

    public init(
        mode: Mode = .worldTracking,
        planeDetection: ARSceneView.PlaneDetectionMode = .horizontal,
        imageTrackingDatabase: Set<ARReferenceImage>? = nil,
        environmentTexturing: ARWorldTrackingConfiguration.EnvironmentTexturing = .automatic,
        sceneReconstruction: SceneReconstruction = .none,
        frameSemantics: ARConfiguration.FrameSemantics = []
    ) {
        self.mode = mode
        self.planeDetection = planeDetection
        self.imageTrackingDatabase = imageTrackingDatabase
        self.environmentTexturing = environmentTexturing
        self.sceneReconstruction = sceneReconstruction
        self.frameSemantics = frameSemantics
    }

    /// The first requirement this device does not meet, or `nil` when the
    /// configuration can run as asked. Checked by ``ARSceneView`` before the
    /// session starts; a non-`nil` result is surfaced as
    /// ``ARSceneViewError/unsupported(_:)`` (or the legacy
    /// ``ARSceneViewError/faceTrackingUnsupported`` for face tracking) and
    /// **no session is run**.
    public func unmetRequirement(
        capabilities: Capabilities = .current
    ) -> Requirement? {
        switch mode {
        case .faceTracking:
            return capabilities.faceTracking ? nil : .faceTracking
        case .worldTracking:
            guard capabilities.worldTracking else { return .worldTracking }
            switch sceneReconstruction {
            case .none:
                break
            case .mesh:
                guard capabilities.sceneReconstruction else { return .lidar }
            case .meshWithClassification:
                guard capabilities.sceneReconstructionWithClassification else { return .lidar }
            }
            let missing = frameSemantics.subtracting(capabilities.supportedFrameSemantics)
            if !missing.isEmpty { return .frameSemantics(missing) }
            return nil
        }
    }

    /// Builds the ARKit configuration object this value describes. Pure: a
    /// fresh object every call, nothing cached, so re-running after an
    /// interruption and re-running after a host change go through the same
    /// code.
    public func makeARConfiguration() -> ARConfiguration {
        switch mode {
        case .faceTracking:
            return ARFaceTrackingConfiguration()
        case .worldTracking:
            let config = ARWorldTrackingConfiguration()
            config.planeDetection = planeDetection.arPlaneDetection
            config.environmentTexturing = environmentTexturing
            if let images = imageTrackingDatabase, !images.isEmpty {
                config.detectionImages = images
                config.maximumNumberOfTrackedImages = images.count
            }
            config.sceneReconstruction = sceneReconstruction.arValue
            config.frameSemantics = frameSemantics
            return config
        }
    }

    /// Whether moving from `previous` to `self` needs ARKit to start tracking
    /// from scratch. Only a change of ``Mode`` does — the two configuration
    /// classes use different cameras and cannot share a world map. Every
    /// other field (planes, images, mesh, semantics) is applied to the live
    /// session with no `.resetTracking`, so anchors the host placed stay put.
    func requiresTrackingReset(from previous: ARSessionConfiguration?) -> Bool {
        guard let previous else { return true }
        return previous.mode != mode
    }
}

// MARK: - Session state and events

/// Coarse lifecycle of the `ARSession` behind an ``ARSceneView``, reported
/// through ``ARSceneView/onSessionStateChange(_:)``. One value at a time; the
/// transitions are the ones a host UI needs to draw the plan's states
/// (Starting → live, Tracking lost, Error) without reading ARKit itself.
public enum ARSessionState: Equatable, Sendable {
    /// `session.run` was issued; no camera frame has arrived yet. The host
    /// shows its "Starting camera…" state here — nothing on screen is live.
    case starting
    /// At least one `ARFrame` was delivered since the last run. The camera
    /// feed is on screen. Independent of any model the host is loading.
    case running
    /// ARKit paused delivery (backgrounded, phone call, camera taken by
    /// another client). The configuration is retained; ``running`` follows
    /// again once ARKit resumes and delivers a frame.
    case interrupted
    /// The session could not start or stopped with an error. Details arrive
    /// through ``ARSceneView/onSessionError(_:)`` / ``ARSessionEvent/failed(_:)``.
    case failed
}

/// `ARCamera.TrackingState` as an `Equatable` value, so hosts can compare
/// successive reports and tests can assert on them.
public enum ARTrackingStatus: Equatable, Sendable {
    /// Why tracking is limited — mirrors `ARCamera.TrackingState.Reason`.
    public enum LimitedReason: Equatable, Sendable {
        case initializing
        case excessiveMotion
        case insufficientFeatures
        case relocalizing
        case other
    }

    case notAvailable
    case limited(LimitedReason)
    case normal

    public init(_ state: ARCamera.TrackingState) {
        switch state {
        case .notAvailable:
            self = .notAvailable
        case .normal:
            self = .normal
        case .limited(let reason):
            switch reason {
            case .initializing: self = .limited(.initializing)
            case .excessiveMotion: self = .limited(.excessiveMotion)
            case .insufficientFeatures: self = .limited(.insufficientFeatures)
            case .relocalizing: self = .limited(.relocalizing)
            @unknown default: self = .limited(.other)
            }
        @unknown default:
            self = .notAvailable
        }
    }
}

/// Everything ``ARSceneView`` observes about its session, in order, through
/// ``ARSceneView/onSessionEvent(_:)``. The narrower modifiers
/// (``ARSceneView/onSessionStateChange(_:)``,
/// ``ARSceneView/onTrackingStateChange(_:)``, ``ARSceneView/onSessionError(_:)``)
/// are projections of this stream; a host that wants one callback takes this
/// one.
public enum ARSessionEvent {
    /// `session.run` was issued with this configuration. Fires on the initial
    /// start and on every re-run the view performs (a configuration change
    /// from the host, a restart after an interruption ARKit could not resume).
    case started(ARSessionConfiguration)
    /// The first `ARFrame` since the last ``started(_:)`` or
    /// ``interruptionEnded`` — the camera is on screen.
    case firstFrame
    /// `ARCamera.trackingState` changed. Reported once per change, not once
    /// per frame.
    case trackingStateChanged(ARTrackingStatus)
    /// `ARSessionDelegate.sessionWasInterrupted`.
    case interrupted
    /// `ARSessionDelegate.sessionInterruptionEnded`. The session was resumed
    /// with the configuration it was running; the next frame reports
    /// ``firstFrame`` again.
    case interruptionEnded
    /// The configuration could not run on this device, or ARKit reported a
    /// failure. No session is live after this event until the host recreates
    /// the view.
    case failed(Error)
}

/// A single object that receives every ``ARSessionEvent`` of every
/// ``ARSceneView`` below it in the view tree — installed with
/// ``SwiftUI/View/arSessionObserver(_:)``.
///
/// This is how a container that owns the permission / starting / error
/// presentation observes a camera view it did not create: the AR screen keeps
/// its own `onSessionStarted` / `onTapOnPlane` closures untouched, and the
/// container above it still learns when the first frame lands or the session
/// fails. Callbacks run on the main actor.
@MainActor
public protocol ARSceneSessionObserver: AnyObject {
    func arSession(didEmit event: ARSessionEvent, in arView: ARView)
}
#endif // os(iOS)
