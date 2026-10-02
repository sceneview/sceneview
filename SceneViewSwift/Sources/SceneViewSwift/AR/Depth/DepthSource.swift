import Foundation
import CoreVideo

/// What a monocular estimator's raw output means. Android:
/// `MonocularDepthEstimator.OutputKind`.
public enum MonocularDepthOutputKind: String, Sendable, Hashable {
    /// Inverse depth up to an unknown scale and shift (`d ≈ a/z + b`) — Depth
    /// Anything V2, MiDaS. Scaled with `1/z = s·d + t`.
    case affineInverse
    /// Depth up to an unknown scale and shift (`d ≈ a·z + b`).
    case affineDepth
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
/// The SDK calls ``estimate(_:)`` on its own serial queue, one frame at a
/// time, never on the main thread. Conformers must therefore be safe to call
/// from a background queue (they are only ever called from one at a time).
public protocol MonocularDepthEstimator: AnyObject, Sendable {
    /// Stable identifier, used to cache the first-launch benchmark per model.
    var identifier: String { get }
    /// The size of the BGRA image ``estimate(_:)`` expects, in pixels.
    var inputSize: (width: Int, height: Int) { get }
    /// The `CVPixelBuffer` format of the input (usually `kCVPixelFormatType_32BGRA`).
    var inputPixelFormat: OSType { get }
    var outputKind: MonocularDepthOutputKind { get }
    /// Loads and specialises the model (Neural Engine compilation takes 1–5 s
    /// the first time). Called once, off the main thread, before any estimate.
    func warmUp() throws
    /// Runs the model on one camera image already scaled to ``inputSize``.
    func estimate(_ pixelBuffer: CVPixelBuffer) throws -> MonocularDepthEstimate
}

/// Where an ``ARSceneView`` takes its environment depth from. Android:
/// `DepthSource.Native | Ml | Auto`.
///
/// - ``native``: the device's depth sensor only (LiDAR `sceneDepth`; add
///   `.sceneDepth` to the configuration's `frameSemantics`).
/// - ``ml(_:targetHz:)``: always the monocular estimator, even on LiDAR
///   devices — for comparisons and for devices without a sensor.
/// - ``auto(ml:targetHz:)``: native when the frame carries sensor depth,
///   otherwise the estimator when one is given and passes the first-launch
///   benchmark, otherwise nothing. Without an estimator this is exactly the
///   behaviour of a view with no depth source.
public enum DepthSource: Sendable {
    case native
    case ml(any MonocularDepthEstimator, targetHz: Double = 10)
    case auto(ml: (any MonocularDepthEstimator)? = nil, targetHz: Double = 10)

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

/// What the depth layer is doing right now. Android: `DepthSourceState`.
public enum DepthSourceState: Sendable, Hashable {
    /// Sensor depth (LiDAR) is flowing.
    case native
    /// The estimator is loading, specialising for the Neural Engine, or
    /// running its first-launch benchmark.
    case preparing
    /// ML depth is flowing at about `hz` frames per second.
    case ml(hz: Double)
    /// ML is paused or slowed down on purpose.
    case throttled(ThrottleReason)
    /// No depth will come; consumers fall back to their no-depth behaviour.
    case unavailable(UnavailableReason)

    public enum ThrottleReason: Sendable, Hashable {
        /// `ProcessInfo.thermalState` is `.critical`: inference is paused.
        /// At `.serious` the rate is halved and the state stays ``ml(hz:)``
        /// (Android: `MlDepthState.Throttled` at SEVERE and above only).
        case thermal
        /// Tracking is not `.normal`: there is nothing to scale the depth against.
        case tracking
        /// The model runs but the depth cannot be put in metres yet: too few
        /// feature points or plane hits (`anchors` of them in the last frame),
        /// or they span too little depth. Android: `MlDepthState.WaitingForAnchors(anchors)`.
        case waitingForAnchors(anchors: Int)
    }

    public enum UnavailableReason: String, Sendable, Hashable {
        /// No depth source requested, or `.native` on a device/configuration
        /// without sensor depth and no estimator to fall back to.
        case noSource
        /// The estimator failed to load, or kept throwing while running
        /// (three inferences in a row). Nothing more is published.
        /// Android: `MlDepthState.Failed`.
        case modelFailed
        /// The first-launch benchmark was slower than the floor (p50 > 400 ms).
        case tooSlow
        /// Face tracking: the front camera has no world anchors to scale with.
        case unsupportedMode
    }
}
