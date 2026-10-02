import Foundation
import CoreVideo

/// What a monocular estimator's raw output means. Android:
/// `MonocularDepthEstimator.OutputKind` (`AffineInverse`, `Metric`).
public enum MonocularDepthOutputKind: String, Sendable, Hashable {
    /// Inverse depth up to an unknown scale and shift (`d ≈ a/z + b`) — Depth
    /// Anything V2, MiDaS. Scaled with `1/z = s·d + t`.
    case affineInverse
    /// Metres already. No fit is applied.
    case metric
}

/// One raw estimate: `width × height` floats, row-major, top-left origin, in
/// the model's own units (see ``MonocularDepthOutputKind``).
public struct MonocularDepthEstimate: Sendable {
    public let width: Int
    public let height: Int
    public let values: [Float]

    public init(width: Int, height: Int, values: [Float]) {
        precondition(values.count == width * height, "values must hold width × height floats")
        self.width = width
        self.height = height
        self.values = values
    }
}

/// A single-image depth model. The `SceneViewDepthML` product ships
/// `DepthAnythingV2Estimator` (Core ML, Neural Engine); any other model can
/// be plugged in by conforming to this protocol.
///
/// The SDK calls ``warmUp()`` and ``estimate(_:)`` on **one serial queue per
/// estimator instance**, never on the main thread. Two calls never overlap,
/// even when a view replaces its ``DepthSource`` while an inference is still
/// running, or when several views share one estimator: the next call waits
/// for the previous one to return. Android: both run on the session's single
/// worker thread.
public protocol MonocularDepthEstimator: AnyObject, Sendable {
    /// Stable identifier, used to cache the first-launch benchmark per model.
    var identifier: String { get }
    /// The size of the BGRA image ``estimate(_:)`` expects, in pixels.
    var inputSize: (width: Int, height: Int) { get }
    /// The `CVPixelBuffer` format of the input (usually `kCVPixelFormatType_32BGRA`).
    var inputPixelFormat: OSType { get }
    var outputKind: MonocularDepthOutputKind { get }
    /// Loads and specialises the model (Neural Engine compilation takes 1–5 s
    /// the first time). Called off the main thread, before any estimate.
    func warmUp() throws
    /// Runs the model on one camera image already scaled to ``inputSize``.
    func estimate(_ pixelBuffer: CVPixelBuffer) throws -> MonocularDepthEstimate
}

/// Where an ``ARSceneView`` takes its environment depth from. Android has no
/// such switch: ARCore's Depth API is `ARSceneView(depthMode = …)`, and the ML
/// path is an `MlDepthSession` fed from `onSessionUpdated`.
///
/// - ``native``: the device's depth sensor only (LiDAR `sceneDepth`; add
///   `.sceneDepth` to the configuration's `frameSemantics`).
/// - ``ml(_:targetHz:)``: always the monocular estimator, even on LiDAR
///   devices — for comparisons and for devices without a sensor. Leave
///   `.sceneDepth` out of `frameSemantics` in this mode: the sensor map is
///   not used and only costs power.
/// - ``auto(ml:targetHz:)``: native when the frame carries sensor depth,
///   otherwise the estimator when one is given and passes the first-launch
///   benchmark, otherwise nothing. Without an estimator this is exactly the
///   behaviour of a view with no depth source.
///
/// `targetHz` is a ceiling: 5 maps a second by default, as Android's
/// `MlDepthSession(targetHz = 5f)`.
public enum DepthSource: Sendable {
    case native
    case ml(any MonocularDepthEstimator, targetHz: Double = 5)
    case auto(ml: (any MonocularDepthEstimator)? = nil, targetHz: Double = 5)

    /// The default: sensor depth when there is one, no ML.
    public static var automatic: DepthSource { .auto(ml: nil) }

    var estimator: (any MonocularDepthEstimator)? {
        switch self {
        case .native: return nil
        case .ml(let e, _): return e
        case .auto(let e, _): return e
        }
    }

    var targetHz: Double {
        switch self {
        case .native: return 0
        case .ml(_, let hz), .auto(_, let hz): return max(0.5, min(hz, 30))
        }
    }

    /// Identity used to tell whether a re-render changed the source.
    var identity: String {
        switch self {
        case .native: return "native"
        case .ml(let e, let hz): return "ml:\(ObjectIdentifier(e).hashValue):\(hz)"
        case .auto(let e, let hz):
            return "auto:\(e.map { String(ObjectIdentifier($0).hashValue) } ?? "-"):\(hz)"
        }
    }
}

/// Live figures of the ML path, for a debug overlay. Android: `MlDepthStats`,
/// field for field.
public struct MLDepthStats: Sendable, Hashable {
    /// Duration of the last model run, in milliseconds.
    public let lastInferenceMs: Double
    /// Median model run over the last 30 runs, in milliseconds.
    public let medianInferenceMs: Double
    /// Depth maps published per second, over the last 30 maps.
    public let publishedHz: Double
    /// Anchors projected into the last image (plane samples + feature points).
    public let anchors: Int
    /// Anchors the last scale fit kept.
    public let inliers: Int
    /// RMS relative error of the scale fit over its inliers (0.05 = 5 %).
    public let rmsRelativeError: Float
    /// `true` while a refused fit is covered by the previous scale.
    public let holding: Bool

    public init(
        lastInferenceMs: Double,
        medianInferenceMs: Double,
        publishedHz: Double,
        anchors: Int,
        inliers: Int,
        rmsRelativeError: Float,
        holding: Bool
    ) {
        self.lastInferenceMs = lastInferenceMs
        self.medianInferenceMs = medianInferenceMs
        self.publishedHz = publishedHz
        self.anchors = anchors
        self.inliers = inliers
        self.rmsRelativeError = rmsRelativeError
        self.holding = holding
    }
}

/// What the depth layer is doing right now.
///
/// The ML cases are Android's `MlDepthState`, one for one: `Preparing` →
/// ``preparing``, `WaitingForAnchors(anchors)` → ``waitingForAnchors(anchors:)``,
/// `Running(stats)` → ``running(_:)``, `Throttled` → ``throttled(_:)``,
/// `Failed(error)` → ``failed(_:)``. ``native`` and ``unavailable(_:)`` are
/// iOS additions: the sensor path, and the reasons no depth comes at all.
public enum DepthSourceState: Sendable, Equatable {
    /// Sensor depth (LiDAR) is flowing.
    case native
    /// The estimator is loading, specialising for the Neural Engine, or
    /// running its first-launch benchmark.
    case preparing
    /// The model runs but the depth cannot be put in metres yet: too few
    /// feature points or plane hits (`anchors` of them in the last image), or
    /// they span too little depth. Nothing is published in this state.
    case waitingForAnchors(anchors: Int)
    /// Metric ML depth maps are being published.
    case running(MLDepthStats)
    /// ML is paused on purpose.
    case throttled(ThrottleReason)
    /// The estimator could not be loaded, or threw three inferences in a row
    /// (a single failure only skips that frame). Nothing more is published.
    case failed(any Error)
    /// No depth will come; consumers fall back to their no-depth behaviour.
    case unavailable(UnavailableReason)

    public enum ThrottleReason: Sendable, Hashable {
        /// `ProcessInfo.thermalState` is `.critical`: inference is paused.
        /// At `.serious` the rate is halved and the state stays
        /// ``DepthSourceState/running(_:)`` (Android: `Throttled` at SEVERE
        /// and above only).
        case thermal
        /// Tracking is not `.normal`: there is nothing to scale the depth
        /// against.
        case tracking
    }

    public enum UnavailableReason: String, Sendable, Hashable {
        /// No depth source requested, or `.native` on a device/configuration
        /// without sensor depth and no estimator to fall back to.
        case noSource
        /// `.auto`: the first-launch benchmark was slower than the floor
        /// (p50 > 400 ms), so the estimator is not used.
        case tooSlow
        /// Face tracking: the front camera has no world anchors to scale with.
        case unsupportedMode
    }

    /// Failures compare by error type and description, so the same cause
    /// reported twice is one state.
    public static func == (lhs: DepthSourceState, rhs: DepthSourceState) -> Bool {
        switch (lhs, rhs) {
        case (.native, .native), (.preparing, .preparing):
            return true
        case let (.waitingForAnchors(a), .waitingForAnchors(b)):
            return a == b
        case let (.running(a), .running(b)):
            return a == b
        case let (.throttled(a), .throttled(b)):
            return a == b
        case let (.failed(a), .failed(b)):
            return String(reflecting: a) == String(reflecting: b)
        case let (.unavailable(a), .unavailable(b)):
            return a == b
        default:
            return false
        }
    }
}
