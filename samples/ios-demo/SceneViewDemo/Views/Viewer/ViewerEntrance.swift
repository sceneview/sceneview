import SwiftUI
import RealityKit
import SceneViewSwift

/// The model viewer's entrance, as numbers: Android's `CAMERA_ENTRANCE_MILLIS`
/// and `flyToOne` (`ModelViewerDemo.kt`) and `EntranceCameraManipulator`
/// (`DemoHelpers.kt`), value for value.
///
/// **What moves.** The camera, not the model, so lights and reflections land
/// where the resting frame shows them. At progress 0 the eye sits 1.55x
/// further from the target, swung 24° round world +Y and lifted by 22 % of the
/// orbit radius; it flies to the resting pose over 700 ms (`duration-long`) on
/// `ease-expressive`, cubic-bezier(0.2, 0, 0, 1). The model only settles: it
/// starts 6 % of the camera distance below its resting place and rises on the
/// one spring (damping ratio 0.85, stiffness 450), started with the flight.
///
/// **Recenter** flies from the eye on screen back to rest in a straight line
/// (Android #3622) instead of replaying the swing, and the model settles again.
///
/// **Time.** A step advances by at most 1/20 s, as on Android: the frames a
/// fresh model's upload drops pause the flight instead of eating it.
///
/// Pure values, so `ViewerEntranceTests` checks them without a scene.
struct ViewerEntranceFlight: Equatable {
    static let duration: Double = SceneViewTokens.Motion.long
    static let maxStep: Double = 1.0 / 20
    static let startDistanceScale: Float = 1.55
    static let startYawDegrees: Float = 24
    static let startLiftFraction: Float = 0.22
    static let settleDropFraction: Float = 0.06
    /// `ease-expressive`.
    static let curve = UnitCurve.bezier(
        startControlPoint: UnitPoint(x: 0.2, y: 0),
        endControlPoint: UnitPoint(x: 0, y: 1)
    )
    /// `DESIGN.md`'s one spring in Android's terms: damping ratio 0.85,
    /// stiffness 450, unit mass (so damping = 2 × 0.85 × √450).
    static let settleSpring = Spring(mass: 1, stiffness: 450, damping: 2 * 0.85 * 450.0.squareRoot())

    /// Where the flight starts: the synthetic swing for a model that just
    /// arrived, or the eye the user left the camera at, for Recenter.
    enum Start: Equatable {
        case arrival
        case eye(SIMD3<Float>)
    }

    let rest: SceneCameraPose
    let start: Start
    private(set) var elapsed: Double = 0

    init(rest: SceneCameraPose, start: Start) {
        self.rest = rest
        self.start = start
    }

    /// The camera has landed and the model has settled.
    var isFinished: Bool { elapsed >= Self.duration && settleDrop == 0 }

    /// Advances by one frame of `dt` seconds, capped at ``maxStep``.
    mutating func advance(by dt: Double) {
        elapsed += min(max(dt, 0), Self.maxStep)
    }

    /// Eased flight progress, 0 → 1.
    var progress: Float {
        Float(Self.curve.value(at: min(elapsed / Self.duration, 1)))
    }

    /// The camera pose for the current progress: ``rest`` once landed.
    var pose: SceneCameraPose {
        Self.pose(rest: rest, start: start, progress: progress)
    }

    /// How far below its resting place the model sits, in scene units. Zero
    /// once the spring is within 0.1 % of rest.
    var settleDrop: Float {
        let remaining = 1 - Self.settleSpring.value(target: 1.0, time: elapsed)
        guard abs(remaining) > 0.001 else { return 0 }
        return rest.distance * Self.settleDropFraction * Float(remaining)
    }

    static func pose(rest: SceneCameraPose, start: Start, progress: Float) -> SceneCameraPose {
        let p = min(max(progress, 0), 1)
        guard p < 1 else { return rest }
        let remaining = 1 - p
        let target = rest.target
        let restEye = rest.cameraPosition()
        let eye: SIMD3<Float>
        switch start {
        case .eye(let from):
            eye = restEye + (from - restEye) * remaining
        case .arrival:
            let d = restEye - target
            let radius = simd_length(d)
            // Degenerate framing: nothing to fly along, sit at rest.
            guard radius.isFinite, radius > 1e-6 else { return rest }
            // Swing the offset round world +Y, push it out along itself, lift it.
            let yaw = startYawDegrees * remaining * .pi / 180
            let c = cos(yaw), s = sin(yaw)
            let scale = 1 + (startDistanceScale - 1) * remaining
            let lift = radius * startLiftFraction * remaining
            eye = target + SIMD3(d.x * c + d.z * s, d.y + lift, d.z * c - d.x * s) * scale
        }
        return orbitPose(eye: eye, target: target) ?? rest
    }

    /// The orbit pose whose ``SceneCameraPose/cameraPosition()`` is `eye`.
    static func orbitPose(eye: SIMD3<Float>, target: SIMD3<Float>) -> SceneCameraPose? {
        let d = eye - target
        let distance = simd_length(d)
        guard distance.isFinite, distance > 1e-6 else { return nil }
        return SceneCameraPose(
            azimuth: atan2(d.x, d.z),
            elevation: asin(min(max(d.y / distance, -1), 1)),
            distance: distance,
            target: target
        )
    }
}

/// Runs ``ViewerEntranceFlight`` on the viewer's `SceneView`: it writes the
/// camera through `.cameraPose(_:)` and reads it back through
/// `.onCameraChanged(_:)`.
///
/// A model that arrives is hidden until the fit-to-bounds pass has framed it
/// and the flight's first pose is on screen, so the first frame shows the
/// start of the flight, not the resting pose it then jumps away from. A drag
/// or a pinch during the flight hands the camera to the user where it is, as
/// Android's first touch does.
@MainActor
@Observable
final class ViewerEntranceDriver {
    /// The pose `SceneView` is asked for. `nil` until the first flight.
    private(set) var pose: SceneCameraPose?

    @ObservationIgnored private var phase = Phase.idle
    @ObservationIgnored private var flight: ViewerEntranceFlight?
    @ObservationIgnored private weak var entity: Entity?
    @ObservationIgnored private var restY: Float = 0
    @ObservationIgnored private var loop: Task<Void, Never>?
    /// The last poses written, to tell our own writes from the user's drags.
    @ObservationIgnored private var written: [SceneCameraPose] = []
    /// The pose on screen, as `SceneView` last reported it.
    @ObservationIgnored private(set) var lastReported: SceneCameraPose?
    /// Where the last arrival landed: where Recenter flies back to.
    @ObservationIgnored private(set) var rest: SceneCameraPose?
    /// Until when a report counts as the re-fit that follows a Recenter.
    @ObservationIgnored private var refitDeadline: ContinuousClock.Instant?
    @ObservationIgnored private var onLanded: (() -> Void)?

    private enum Phase {
        case idle
        /// Hidden, waiting for the fit pass to report the resting pose.
        case awaitingRest(azimuth: Float, elevation: Float)
        /// The start pose is written; the model is revealed once it is drawn.
        case revealing
        case flying
    }

    /// Arms a flight for `entity`, which has just been handed to the scene.
    /// The resting pose is Android's: the fitted distance and target, seen
    /// from `azimuth` / `elevation`.
    func arrive(_ entity: Entity, azimuth: Float, elevation: Float) {
        stop(settle: true)
        self.entity = entity
        restY = entity.position.y
        entity.components.set(OpacityComponent(opacity: 0))
        phase = .awaitingRest(azimuth: azimuth, elevation: elevation)
        // Never leave a model hidden: if no fit lands, or the start pose is
        // never reported, show it at rest.
        loop = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1.5))
            guard let self, !Task.isCancelled else { return }
            switch self.phase {
            case .awaitingRest, .revealing:
                if let rest = self.rest, self.flight != nil { self.write(rest) }
                self.stop(settle: true)
            case .idle, .flying:
                return
            }
        }
    }

    /// Flies from the eye on screen back to the last arrival's resting pose,
    /// then calls `landed`, which re-fits through
    /// `SceneView.recenterCamera(_:)`: a no-op when the viewport has not
    /// changed since the arrival, the right framing when it has. Returns
    /// `false` when there is nothing to fly to, so the caller re-fits at once.
    func recenter(landed: @escaping () -> Void) -> Bool {
        guard let rest, let from = lastReported, let entity, entity.parent != nil else { return false }
        stop(settle: true)
        self.entity = entity
        restY = entity.position.y
        onLanded = landed
        start(ViewerEntranceFlight(rest: rest, start: .eye(from.cameraPosition())))
        phase = .flying
        runLoop()
        return true
    }

    /// Ends any flight at once: the camera stays where it is and the model
    /// goes back to its resting place, visible.
    func stop(settle: Bool) {
        loop?.cancel()
        loop = nil
        if settle, let entity {
            entity.components.remove(OpacityComponent.self)
            entity.position.y = restY
        }
        phase = .idle
        flight = nil
        onLanded = nil
        written.removeAll()
    }

    /// Feed of `SceneView.onCameraChanged(_:)`. Runs inside RealityKit's
    /// update pass: it touches entities and plain properties only, and hops
    /// before writing the observed ``pose``.
    func cameraChanged(_ reported: SceneCameraPose) {
        lastReported = reported
        switch phase {
        case .idle:
            // The re-fit after a Recenter landed somewhere else: the viewport
            // changed since the arrival, and this is the new resting pose.
            if let deadline = refitDeadline, ContinuousClock.now < deadline {
                rest = reported
            }
        case let .awaitingRest(azimuth, elevation):
            // The first report once the new model is in the scene is the fit.
            // Reports before that frame the previous model.
            guard let entity, entity.parent != nil else { return }
            let rest = SceneCameraPose(azimuth: azimuth, elevation: elevation,
                                       distance: reported.distance, target: reported.target)
            self.rest = rest
            phase = .revealing
            Task { [weak self] in
                guard let self, case .revealing = self.phase else { return }
                self.start(ViewerEntranceFlight(rest: rest, start: .arrival))
            }
        case .revealing:
            guard let flight, written.contains(where: { $0.approximatelyMatches(reported, tolerance: 1e-3) }) else { return }
            // The start pose is drawn this frame: show the model, low, and fly.
            entity?.components.remove(OpacityComponent.self)
            entity?.position.y = restY - flight.settleDrop
            phase = .flying
            Task { [weak self] in self?.runLoop() }
        case .flying:
            // Anything we did not write is the user's hand on the camera.
            if !written.contains(where: { $0.approximatelyMatches(reported, tolerance: 1e-3) }) {
                stop(settle: true)
            }
        }
    }

    private func start(_ flight: ViewerEntranceFlight) {
        self.flight = flight
        write(flight.pose)
    }

    private func write(_ pose: SceneCameraPose) {
        written.append(pose)
        if written.count > 6 { written.removeFirst(written.count - 6) }
        self.pose = pose
    }

    private func runLoop() {
        loop?.cancel()
        loop = Task { [weak self] in
            var last = ContinuousClock.now
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(8))
                guard let self, !Task.isCancelled, var flight = self.flight else { return }
                let now = ContinuousClock.now
                let dt = last.duration(to: now)
                last = now
                flight.advance(by: Double(dt.components.seconds) + Double(dt.components.attoseconds) * 1e-18)
                self.flight = flight
                self.entity?.position.y = self.restY - flight.settleDrop
                if flight.pose != self.pose { self.write(flight.pose) }
                if flight.isFinished {
                    let landed = self.onLanded
                    self.phase = .idle
                    self.flight = nil
                    self.onLanded = nil
                    if let landed {
                        self.refitDeadline = ContinuousClock.now.advanced(by: .milliseconds(500))
                        landed()
                    }
                    return
                }
            }
        }
    }
}
