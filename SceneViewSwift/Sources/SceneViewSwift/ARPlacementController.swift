#if os(iOS)
import ARKit
import Combine
import RealityKit
import UIKit

/// Automatic placement phases shared with Android. Permission and asset errors belong to the host.
public enum ARPlacementPhase: String, Sendable {
    case initializing, scanning, noSurface, placed, adjusting
    case trackingLost, recovering, recoveryFailed, cameraError
}

public enum ARPlacementAlignment: Equatable, Sendable { case horizontal, vertical }

/// Keep this ticket with an asynchronous load; obsolete results cannot mutate the scene.
public struct ARPlacementAssetTicket: Equatable, Sendable {
    public let session: Int
    public let selection: Int
}

/// A real detected surface and its placement anchor, including the full contact orientation.
///
/// With ``ARPlacementController/instantPlacement`` on, ``ARPlacementController/result`` stays
/// `nil` while ``ARPlacementController/trackingMethod`` is
/// ``ARPlacementTrackingMethod/approximate``: there is no detected surface to name yet. It is
/// set when the model lands on one.
@MainActor
public struct ARPlacementResult {
    public let surfaceIdentifier: UUID
    public let anchor: AnchorEntity
    /// Identity of the owned RealityKit anchor; distinct from the detected ARKit surface.
    public var anchorIdentifier: Entity.ID { anchor.id }
    public let worldTransform: simd_float4x4
}

// Pure decision state, separated from ARKit so races can be exercised on the Simulator.
struct ARPlacementLifecycle {
    var phase: ARPlacementPhase = .initializing
    var ticket = ARPlacementAssetTicket(session: 0, selection: 0)
    var requested = false
    var hasPlacement = false
    var dismissed = false
    var searchSince: TimeInterval?
    var recoverySince: TimeInterval?
    /// Anchors committed since the last dismissal; `.arHapticFeedback` plays `.placed` on each.
    var placementsCreated = 0
    var acceptsFrames: Bool { !dismissed && phase != .cameraError }

    mutating func select() -> ARPlacementAssetTicket {
        dismissed = false
        ticket = .init(session: ticket.session, selection: ticket.selection + 1)
        if !hasPlacement { requested = false; searchSince = nil }
        return ticket
    }
    func accepts(_ value: ARPlacementAssetTicket) -> Bool { !dismissed && ticket == value }
    mutating func dismiss() {
        self = ARPlacementLifecycle(ticket: .init(session: ticket.session + 1, selection: ticket.selection), dismissed: true)
    }
    mutating func reset() {
        guard !dismissed else { return }
        hasPlacement = false
        requested = true
        searchSince = nil
        recoverySince = nil
        if phase != .initializing && phase != .trackingLost { phase = .scanning }
    }
    /// The camera session itself restarts (coaching "Start Over", a relocalization reset): the
    /// placement is reset and the flow starts over from `.initializing`, so the untracked frames
    /// of the restarted session are start-up, not a loss.
    mutating func restartSession() {
        guard !dismissed else { return }
        reset()
        phase = .initializing
    }
    mutating func frame(now: TimeInterval, tracking: Bool, anchorTracking: Bool, assetReady: Bool) -> Bool {
        guard acceptsFrames else { return false }
        guard tracking else {
            // Untracked frames before the first tracked one are the normal start-up: nothing
            // was lost, so no `.trackingLost`, no warning haptic, no "paused" copy.
            if phase != .initializing { phase = .trackingLost }
            searchSince = nil
            recoverySince = nil
            return false
        }
        if hasPlacement {
            if anchorTracking {
                recoverySince = nil
                if phase != .adjusting { phase = .placed }
            } else {
                recoverySince = recoverySince ?? now
                phase = now - recoverySince! >= 10 ? .recoveryFailed : .recovering
            }
            return false
        }
        phase = phase == .noSurface ? .noSurface : .scanning
        guard requested && assetReady else { searchSince = nil; return false }
        searchSince = searchSince ?? now
        if now - searchSince! >= 10 { phase = .noSurface }
        return true
    }
    /// How long an anchor whose plane is still usable may stay unresolved before it counts as a
    /// failed attempt. RealityKit resolves `AnchorEntity(anchor:)` asynchronously and a slow
    /// device can need several frames.
    static let pendingAnchorTimeout: TimeInterval = 3

    /// What to do with an anchor that was created but that RealityKit has not resolved yet.
    enum PendingAnchorDecision: Equatable { case commit, wait, reject }

    /// Pure policy for the pending anchor, so the slow-device race can be exercised off-device.
    /// Rejecting an unresolved anchor on the very next frame would create and discard one anchor
    /// per frame and never reach ``commit()``, so waiting is the default while the surface holds.
    static func pendingAnchorDecision(
        anchored: Bool,
        surfaceUsable: Bool,
        now: TimeInterval,
        since: TimeInterval?
    ) -> PendingAnchorDecision {
        guard surfaceUsable else { return .reject }
        if anchored { return .commit }
        guard let since, now - since >= pendingAnchorTimeout else { return .wait }
        return .reject
    }

    mutating func commit() {
        guard requested && !hasPlacement && acceptsFrames else { return }
        requested = false
        hasPlacement = true
        placementsCreated += 1
        searchSince = nil
        phase = .placed
    }
}

/// One placement request, one plane-associated anchor. Renderer mutations run on the main actor.
///
/// Supply a loaded entity with ``setModel(_:ticket:previewSize:)``. The default is a 0.3 m
/// longest-dimension preview, never a claim about authored units. Pass `nil` to retain units.
/// The controller owns only its own anchors/recognizers; resetting never resets the camera.
@MainActor
public final class ARPlacementController: NSObject, ObservableObject, UIGestureRecognizerDelegate {
    public let alignment: ARPlacementAlignment
    @Published public private(set) var phase: ARPlacementPhase = .initializing
    @Published public private(set) var result: ARPlacementResult?
    @Published public private(set) var scale: Float = 1
    @Published public private(set) var selection = false
    @Published public private(set) var invalidMovement = false
    /// Apple's coaching overlay is on screen (``AutoPlacementScene`` `coaching: true`, the
    /// default). Hide non-essential app UI — status pills, hints, secondary controls — while
    /// this is true and bring it back when it turns false (Human Interface Guidelines,
    /// "Coaching"). Kotlin twin: `ArGuidanceState.isCoaching`.
    @Published public internal(set) var isCoachingActive = false
    public var hasPlacement: Bool { lifecycle.hasPlacement }

    private var lifecycle = ARPlacementLifecycle()
    private weak var arView: ARView?
    private var anchor: AnchorEntity?
    private var surface: ARPlaneAnchor?
    private var pendingPlacementResult: ARPlacementResult?
    /// Frame timestamp at which the still-unresolved anchor was created. See
    /// ``ARPlacementLifecycle/pendingAnchorDecision(anchored:surfaceUsable:now:since:)``.
    private var pendingAnchorSince: TimeInterval?
    private var pendingMove: (anchor: AnchorEntity, plane: ARPlaneAnchor, contact: simd_float4x4, transform: Transform,
                              speed: Float, easeRotation: Bool)?
    private(set) var pivot = Entity()
    private var model: Entity?
    private var selectedAssetReady = false
    private var recognizers: [UIGestureRecognizer] = []
    private var activeGestures: Set<ObjectIdentifier> = []
    private var grabOffset = SIMD3<Float>.zero
    private(set) var twistBaseline = simd_quatf(angle: 0, axis: [0, 1, 0])
    private var lastTimestamp: TimeInterval?
    private var visibility: Float = 0
    private var visibilityTarget: Float = 0
    private var renderSubscription: (any Cancellable)?
    /// The pinch, the 100 % rebound, the double-tap ease and the grow-in. Internal for tests.
    private(set) var scaleDisplay = ARPlacementFeel.ScaleDisplay()
    /// The glide after a thrown drag (ARMV `moveCoast`), in screen pixels. Internal for tests.
    var moveCoast = ARPlacementFeel.moveCoast()
    /// The spin after a flicked twist (ARMV `twistCoast`), in degrees. Internal for tests.
    var twistCoast = ARPlacementFeel.twistCoast()
    /// Where the glide's virtual finger is, in view points; `nil` when no glide runs.
    private var glidePointer: CGPoint?

    /// Gesture-driven haptic moments (100 % snap, scale limits) for `.arHapticFeedback`.
    /// Phase, placement and selection moments are derived from the published state instead.
    let hapticEvents = PassthroughSubject<ARHapticEvent, Never>()
    var placementsCreated: Int { lifecycle.placementsCreated }

    /// Places the model before ARKit has detected a plane, then moves it onto the real surface
    /// when one is found — ARCore Instant Placement parity.
    ///
    /// A detected plane under the screen centre still takes the model at once. Without one, after
    /// tracking has run for 0.6 s, the model stands where the centre ray meets the floor as far
    /// as ARKit knows it — the lowest detected or estimated horizontal surface well below the
    /// phone, else 1.3 m below it — between half its reach and its reach ahead, the reach growing
    /// with the model's size as shown: 1.2 m at the default 0.3 m preview; a 2 m sofa at
    /// real-world size (`previewSize: nil`) stands 2.9 m away. It never floats
    /// along the ray. Walls wait for an ARKit estimated wall under the centre.
    /// ``trackingMethod`` then reads ``ARPlacementTrackingMethod/approximate`` and ``result`` is
    /// `nil`.
    ///
    /// Ten times a second the model's own screen position is cast again: an estimated surface
    /// there eases it closer (a continuous follow, 6/s, which a drag or a move takes over from),
    /// and a detected plane there lands it — ``result`` names the plane, ``trackingMethod`` becomes
    /// ``ARPlacementTrackingMethod/fullTracking`` and ``ARHapticEvent/placed`` plays again.
    /// Landing waits while a gesture is in progress. Each plane anchor gets 3 s to resolve,
    /// counted again from the end of such a gesture; one that does not counts as a failure and
    /// backs off (0.1 s doubling to 0.8 s). After six the recovery is abandoned, as in ARMV: the
    /// model stays where it stands and no timer retries; the next drag, move or ``recenter()``
    /// re-arms it, as does ARKit reporting another plane there or that plane growing. Losing the plane under the
    /// model (a young plane's edge) drops the anchor without counting a failure, unless it
    /// happens three times in a row on that plane. After landing, the glide to the landing
    /// point goes on until a drag or a move actually moves the model. Drag, pinch, twist, ``rotate(by:)``,
    /// ``move(by:)`` and ``scale(to:)`` all work while approximate; drag follows the estimated
    /// floor.
    ///
    /// Off by default, as on Android: `AutoPlacementScene` there runs with
    /// `InstantPlacementMode.DISABLED`, and an existing app keeps waiting for a plane. Changing it
    /// affects the next placement only; a standing approximate placement still refines.
    /// `AutoPlacementScene`'s coaching overlay then coaches tracking only, not a plane search.
    @Published public var instantPlacement: Bool

    /// How precisely the current placement is tracked; `nil` while nothing is placed. See
    /// ``instantPlacement``. Kotlin twin: `InstantPlacementPoint.trackingMethod`.
    @Published public private(set) var trackingMethod: ARPlacementTrackingMethod?
    /// Non-nil exactly while the placement is approximate. Internal for tests.
    var instant: ARInstantPlacement.Runtime?
    /// Auto-place delay, refinement throttle, landing backoff and abandonment recovery.
    private var instantState = ARInstantPlacement.State()
    /// The ease toward a refined spot, or toward the landing point; `nil` at rest. Internal
    /// for tests; only the instant-placement section and the gesture handlers write it.
    var follow: ARInstantPlacement.Follow?

    /// - Parameters:
    ///   - alignment: Floors (`.horizontal`, the default) or walls (`.vertical`).
    ///   - instantPlacement: Place before a plane is detected and refine; see
    ///     ``instantPlacement``. Default `false`.
    public init(alignment: ARPlacementAlignment = .horizontal, instantPlacement: Bool = false) {
        self.alignment = alignment
        _instantPlacement = Published(initialValue: instantPlacement)
        super.init()
    }

    public func selectModel() -> ARPlacementAssetTicket {
        selectedAssetReady = false
        return lifecycle.select()
    }
    public func acceptsAsset(_ ticket: ARPlacementAssetTicket) -> Bool { lifecycle.accepts(ticket) }

    /// Returns false without changing the current model for a stale result or invalid bounds.
    /// Grounding and collision shapes are applied recursively, including asynchronous content.
    @discardableResult
    public func setModel(_ entity: Entity, ticket: ARPlacementAssetTicket, previewSize: Float? = 0.3) -> Bool {
        guard lifecycle.accepts(ticket), let prepared = Self.prepare(entity, alignment: alignment, previewSize: previewSize) else { return false }
        cancelGestures()
        model?.removeFromParent()
        model = prepared
        selectedAssetReady = true
        pivot.addChild(prepared)
        if !hasPlacement { lifecycle.requested = true }
        return true
    }

    // A wrapper keeps the authored hierarchy untouched and makes its surface contact the pivot.
    static func prepare(_ entity: Entity, alignment: ARPlacementAlignment, previewSize: Float?) -> Entity? {
        let bounds = entity.visualBounds(relativeTo: nil)
        let longest = max(bounds.extents.x, max(bounds.extents.y, bounds.extents.z))
        guard longest.isFinite, longest > 0,
              previewSize == nil || (previewSize!.isFinite && previewSize! > 0) else { return nil }
        let wrapper = Entity()
        wrapper.addChild(entity)
        if let previewSize { entity.scale *= SIMD3<Float>(repeating: previewSize / longest) }
        let fitted = entity.visualBounds(relativeTo: wrapper)
        let contact = alignment == .horizontal
            ? SIMD3<Float>(fitted.center.x, fitted.min.y, fitted.center.z)
            : SIMD3<Float>(fitted.center.x, fitted.min.y, fitted.min.z)
        entity.position -= contact
        entity.generateCollisionShapes(recursive: true)
        // RealityKit grounding shadows project downward; they are not wall contact shading.
        if alignment == .horizontal { ARSceneView.Coordinator.applyGroundingShadow(to: entity) }
        return wrapper
    }

    /// Idempotent while armed; a standing placement must be explicitly reset before re-placement.
    public func requestPlacement() {
        guard !lifecycle.dismissed, !hasPlacement else { return }
        lifecycle.requested = true
    }
    /// Resets the placement for a camera-session restart (``ARSceneView``'s coaching "Start
    /// Over"). The host re-runs the session; this only rewinds the placement flow.
    func resetForSessionRestart() {
        guard !lifecycle.dismissed else { return }
        resetPlacement()
        lifecycle.restartSession()
        publishPhase()
    }

    public func resetPlacement() {
        guard !lifecycle.dismissed else { return }
        cancelGestures()
        removeAnchor()
        pivot.transform = .identity
        scaleDisplay.reset()
        publishScale()
        visibility = 0
        visibilityTarget = 0
        pivot.components.set(OpacityComponent(opacity: 0))
        selection = false
        result = nil
        lifecycle.reset()
        publishPhase()
    }
    public func keepScanning() {
        guard lifecycle.phase == .noSurface else { return }
        lifecycle.searchSince = nil
        lifecycle.phase = .scanning
        publishPhase()
    }
    public func dismiss() {
        cancelGestures()
        removeAnchor()
        for recognizer in recognizers { arView?.removeGestureRecognizer(recognizer) }
        recognizers.removeAll()
        arView = nil
        renderSubscription?.cancel()
        renderSubscription = nil
        visibility = 0
        visibilityTarget = 0
        model?.removeFromParent()
        model = nil
        selectedAssetReady = false
        pivot = Entity()
        lifecycle.dismiss()
        lastTimestamp = nil
        result = nil
        scaleDisplay.reset()
        publishScale()
        selection = false
        publishPhase()
    }
    private func removeAnchor() {
        pivot.removeFromParent()
        if let anchor { arView?.scene.removeAnchor(anchor) }
        anchor = nil
        surface = nil
        pendingPlacementResult = nil
        pendingAnchorSince = nil
        stopInstantPlacement()
    }
    private func publishScale() {
        if scale != scaleDisplay.scale { scale = scaleDisplay.scale }
    }
    private func applyShownScale() {
        pivot.scale = SIMD3<Float>(repeating: scaleDisplay.shown)
    }
    /// A placement was committed: a first one grows in from ``ARPlacementFeel/appearScale``.
    private func commitPlacement() {
        let before = lifecycle.placementsCreated
        lifecycle.commit()
        selection = true
        guard lifecycle.placementsCreated > before else { return }
        scaleDisplay.beginAppear(reduceMotion: UIAccessibility.isReduceMotionEnabled)
        applyShownScale()
    }
    private func publishPhase() {
        if phase != lifecycle.phase { phase = lifecycle.phase }
        let method = ARInstantPlacement.trackingMethod(hasPlacement: hasPlacement, onSurface: surface != nil)
        if trackingMethod != method { trackingMethod = method }
    }

    func attach(to view: ARView) {
        guard arView !== view else { return }
        if arView != nil { dismiss() }
        arView = view
        renderSubscription = view.scene.subscribe(to: SceneEvents.Update.self) { [weak self] event in
            MainActor.assumeIsolated {
                guard let self else { return }
                // motion-fade: 300 ms, opacity only, including when tracking stops delivering frames.
                let step = Float(event.deltaTime / 0.3)
                let next = self.visibility < self.visibilityTarget
                    ? min(self.visibilityTarget, self.visibility + step)
                    : max(self.visibilityTarget, self.visibility - step)
                if next != self.visibility {
                    self.visibility = next
                    self.pivot.components.set(OpacityComponent(opacity: next))
                }
                self.stepScale(by: event.deltaTime)
                self.stepGlide(by: event.deltaTime)
                self.stepTwistCoast(by: event.deltaTime)
                self.stepFollow(by: event.deltaTime)
            }
        }
        pivot.components.set(OpacityComponent(opacity: 0))
        let tap = UITapGestureRecognizer(target: self, action: #selector(tap(_:)))
        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(doubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        tap.require(toFail: doubleTap)
        let pan = UIPanGestureRecognizer(target: self, action: #selector(pan(_:)))
        pan.maximumNumberOfTouches = 1
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(pinch(_:)))
        let twist = UIRotationGestureRecognizer(target: self, action: #selector(twist(_:)))
        recognizers = [tap, doubleTap, pan, pinch, twist]
        for recognizer in recognizers {
            recognizer.delegate = self
            view.addGestureRecognizer(recognizer)
        }
    }

    func sessionEvent(_ event: ARSessionEvent) {
        switch event {
        case .interrupted:
            _ = lifecycle.frame(now: 0, tracking: false, anchorTracking: false, assetReady: selectedAssetReady)
            instantState.interrupted()
            cancelGestures()
            visibilityTarget = 0
        case .failed:
            cancelGestures()
            lifecycle.phase = .cameraError
            visibilityTarget = 0
        default: break
        }
        publishPhase()
    }

    func update(frame: ARFrame, in view: ARView) {
        attach(to: view)
        guard lifecycle.acceptsFrames, lastTimestamp != frame.timestamp else { return }
        lastTimestamp = frame.timestamp
        let normal: Bool
        if case .normal = frame.camera.trackingState { normal = true } else { normal = false }
        let planes = frame.anchors.compactMap { $0 as? ARPlaneAnchor }
        if let id = surface?.identifier, let updated = planes.first(where: { $0.identifier == id }) { surface = updated }
        // An approximate placement rides a world anchor: it has no plane to lose yet.
        let anchored = instant != nil ? anchor?.isAnchored == true
            : anchor?.isAnchored == true && planes.contains { $0.identifier == surface?.identifier }
        let search = lifecycle.frame(now: frame.timestamp, tracking: normal, anchorTracking: anchored, assetReady: selectedAssetReady)
        instantState.observe(tracking: normal, placed: hasPlacement, now: frame.timestamp)
        if !normal || (hasPlacement && !anchored) { cancelGestures() }
        visibilityTarget = normal && hasPlacement && anchored ? 1 : 0
        if normal, anchored, let move = pendingMove, move.anchor.isAnchored {
            if let currentPlane = planes.first(where: { $0.identifier == move.plane.identifier }),
               valid(plane: currentPlane, point: move.contact.columns.3.xyz, frame: frame, view: view) {
                applyMove(anchor: move.anchor, plane: currentPlane, contact: move.contact, transform: move.transform,
                          speed: move.speed, easeRotation: move.easeRotation)
                pendingMove = nil
            } else { discardPendingMove() }
        }
        if search, anchor != nil {
            let usable = planes.first(where: { $0.identifier == surface?.identifier }).flatMap { plane in
                pendingPlacementResult.map {
                    valid(plane: plane, point: $0.worldTransform.columns.3.xyz, frame: frame, view: view)
                }
            } ?? false
            switch ARPlacementLifecycle.pendingAnchorDecision(anchored: anchored,
                                                              surfaceUsable: usable,
                                                              now: frame.timestamp,
                                                              since: pendingAnchorSince) {
            case .commit:
                result = pendingPlacementResult
                pendingPlacementResult = nil
                pendingAnchorSince = nil
                commitPlacement()
            case .wait:
                // RealityKit resolves the plane target asynchronously; the anchor stays as long as
                // its surface does. Dropping it here would re-create one anchor per frame forever.
                break
            case .reject:
                // The target became unusable, or never resolved within the documented budget.
                // Release it before offering another candidate on the following update.
                removeAnchor()
                result = nil
            }
        } else if search, let candidate = candidate(in: view, frame: frame, planes: planes) {
            // AnchorEntity targets the actual ARPlaneAnchor identity. No world-only stand-in.
            let anchor = AnchorEntity(anchor: candidate.plane)
            anchor.addChild(pivot)
            pivot.transform = Transform(matrix: candidate.plane.transform.inverse * candidate.transform)
            view.scene.addAnchor(anchor)
            self.anchor = anchor
            surface = candidate.plane
            pendingAnchorSince = frame.timestamp
            pendingPlacementResult = .init(surfaceIdentifier: candidate.plane.identifier,
                           anchor: anchor,
                           worldTransform: candidate.transform)
            // Do not report success until RealityKit resolves the plane target on a render update.
            if anchor.isAnchored {
                result = pendingPlacementResult
                pendingPlacementResult = nil
                pendingAnchorSince = nil
                commitPlacement()
            }
        } else if search, instantPlacement, instantState.autoPlaceDue(now: frame.timestamp) {
            // A detected plane, above, wins at once; the estimated floor only after the delay.
            placeApproximately(in: view, frame: frame, planes: planes)
        }
        if normal, instant != nil { refineApproximatePlacement(in: view, frame: frame) }
        publishPhase()
    }

    struct Candidate { let plane: ARPlaneAnchor; let transform: simd_float4x4 }

    private func candidate(in view: ARView, frame: ARFrame, planes: [ARPlaneAnchor]) -> Candidate? {
        let center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
        if let hit = raycast(center, in: view, frame: frame) { return hit }
        return planes.compactMap { plane -> (Candidate, Float)? in
            let point = (plane.transform * SIMD4<Float>(plane.center, 1)).xyz
            guard valid(plane: plane, point: point, frame: frame, view: view),
                  let projected = view.project(point) else { return nil }
            let dx = Float((projected.x - center.x) / (view.bounds.width / 2))
            let dy = Float((projected.y - center.y) / (view.bounds.height / 2))
            return (Candidate(plane: plane, transform: contactTransform(plane: plane, point: point, camera: frame.camera.transform)), dx * dx + dy * dy)
        }.min(by: { $0.1 < $1.1 })?.0
    }

    private func raycast(_ point: CGPoint, in view: ARView, frame: ARFrame) -> Candidate? {
        let matches = view.raycast(from: point, allowing: .existingPlaneGeometry,
                                   alignment: alignment == .horizontal ? .horizontal : .vertical)
        for hit in matches {
            guard let plane = hit.anchor as? ARPlaneAnchor, valid(plane: plane, point: hit.worldTransform.columns.3.xyz, frame: frame, view: view) else { continue }
            return Candidate(plane: plane, transform: contactTransform(plane: plane, point: hit.worldTransform.columns.3.xyz, camera: frame.camera.transform))
        }
        return nil
    }

    private func valid(plane: ARPlaneAnchor, point: SIMD3<Float>, frame: ARFrame, view: ARView,
                       maxDistance: Float = 3) -> Bool {
        guard view.bounds.width > 0, view.bounds.height > 0,
              frame.anchors.contains(where: { $0.identifier == plane.identifier }) else { return false }
        let normal = plane.transform.columns.1.xyz
        guard plane.classification != .ceiling else { return false }
        guard alignment == .vertical ? plane.alignment == .vertical : (plane.alignment == .horizontal && normal.y > 0.5) else { return false }
        // ARKit may report an unclassified ceiling with a +Y transform. Its underside
        // is never an upward-facing usable surface, even without semantic classification.
        if alignment == .horizontal && simd_dot(frame.camera.transform.columns.3.xyz - point, normal) <= 0 { return false }
        let cameraPoint = (frame.camera.transform.inverse * SIMD4<Float>(point, 1)).xyz
        let distance = simd_length(cameraPoint)
        guard cameraPoint.z < 0, distance >= 0.25, distance <= maxDistance,
              let screen = view.project(point), view.bounds.contains(screen) else { return false }
        let local = (plane.transform.inverse * SIMD4<Float>(point, 1)).xyz
        let polygon = plane.geometry.boundaryVertices.map { SIMD2<Float>($0.x, $0.z) }
        return Self.contains(SIMD2<Float>(local.x, local.z), polygon: polygon)
    }

    /// Polygon containment includes boundary points; never substitutes the rectangular extent.
    static func contains(_ point: SIMD2<Float>, polygon: [SIMD2<Float>]) -> Bool {
        guard polygon.count >= 3, point.x.isFinite, point.y.isFinite else { return false }
        let twiceArea = polygon.indices.reduce(Float(0)) { sum, i in
            let a = polygon[i], b = polygon[(i + 1) % polygon.count]
            return sum + a.x * b.y - b.x * a.y
        }
        guard twiceArea.isFinite, abs(twiceArea) > 0.000001 else { return false }
        var inside = false
        var previous = polygon.last!
        for current in polygon {
            let edge = current - previous
            let offset = point - previous
            let length = simd_dot(edge, edge)
            if length > 0 {
                let t = max(0, min(1, simd_dot(offset, edge) / length))
                if simd_length(offset - t * edge) < 0.0001 { return true }
            }
            if (current.y > point.y) != (previous.y > point.y),
               point.x < (previous.x - current.x) * (point.y - current.y) / (previous.y - current.y) + current.x { inside.toggle() }
            previous = current
        }
        return inside
    }

    private func contactTransform(plane: ARPlaneAnchor, point: SIMD3<Float>, camera: simd_float4x4) -> simd_float4x4 {
        var transform = plane.transform
        if alignment == .vertical {
            transform = Self.wallContactTransform(point: point, normal: plane.transform.columns.1.xyz,
                                                  towardCamera: camera.columns.3.xyz - point)
        } else { transform.columns.3 = SIMD4<Float>(point, 1) }
        return transform
    }

    /// Authored +Z faces the camera side, +Y is gravity-up projected into the wall.
    /// No floor, semantic classification or floor-relative height participates.
    static func wallContactTransform(point: SIMD3<Float>, normal: SIMD3<Float>,
                                     towardCamera: SIMD3<Float>) -> simd_float4x4 {
        var facing = simd_normalize(normal)
        if simd_dot(facing, towardCamera) < 0 { facing = -facing }
        let right = simd_normalize(simd_cross(SIMD3<Float>(0, 1, 0), facing))
        let up = simd_cross(facing, right)
        return simd_float4x4(SIMD4<Float>(right, 0), SIMD4<Float>(up, 0),
                             SIMD4<Float>(facing, 0), SIMD4<Float>(point, 1))
    }

    static func tangentOffset(_ offset: SIMD3<Float>, normal: SIMD3<Float>) -> SIMD3<Float> {
        let n = simd_normalize(normal)
        return offset - n * simd_dot(offset, n)
    }

    // MARK: Surface-constrained manipulation
    private var canManipulate: Bool { hasPlacement && (phase == .placed || phase == .adjusting) }
    private var normalAxis: SIMD3<Float> { alignment == .horizontal ? [0, 1, 0] : [0, 0, 1] }
    func owns(_ entity: Entity?) -> Bool {
        var current = entity
        while let node = current { if node === pivot { return true }; current = node.parent }
        return false
    }
    public func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard canManipulate, let view = arView else { return false }
        if gestureRecognizer is UITapGestureRecognizer { return true }
        // A second gesture may join the already selected object without re-hit-testing its midpoint.
        return !activeGestures.isEmpty || owns(view.entity(at: gestureRecognizer.location(in: view)))
    }
    /// Any finger landing on the view stops a glide or a spin where it is (ARMV `touchDown`).
    public func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        touchDown()
        return true
    }
    public func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                                  shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        (gestureRecognizer is UIPinchGestureRecognizer && otherGestureRecognizer is UIRotationGestureRecognizer)
            || (gestureRecognizer is UIRotationGestureRecognizer && otherGestureRecognizer is UIPinchGestureRecognizer)
    }
    private func isActive<Recognizer: UIGestureRecognizer>(_ kind: Recognizer.Type) -> Bool {
        recognizers.contains { $0 is Recognizer && activeGestures.contains(ObjectIdentifier($0)) }
    }
    private func begin(_ recognizer: UIGestureRecognizer) {
        activeGestures.insert(ObjectIdentifier(recognizer))
        selection = true
        lifecycle.phase = .adjusting
        publishPhase()
    }
    private func end(_ recognizer: UIGestureRecognizer) {
        activeGestures.remove(ObjectIdentifier(recognizer))
        if activeGestures.isEmpty {
            discardPendingMove()
            if lifecycle.phase == .adjusting { lifecycle.phase = .placed }
            invalidMovement = false
            publishPhase()
        }
    }
    private func discardPendingMove() {
        if let pendingMove { arView?.scene.removeAnchor(pendingMove.anchor) }
        pendingMove = nil
    }
    private func cancelGestures() {
        stopCoasts()
        discardPendingMove()
        for recognizer in recognizers where activeGestures.contains(ObjectIdentifier(recognizer)) {
            recognizer.isEnabled = false
            recognizer.isEnabled = true
        }
        // `.cancelled` reaches `pan` after this, with the set already empty: a drag cut short
        // still dropped the model somewhere new, as a drag that ends does.
        if instant != nil, isActive(UIPanGestureRecognizer.self) { instantState.moved() }
        activeGestures.removeAll()
        invalidMovement = false
        if lifecycle.phase == .adjusting { lifecycle.phase = .placed }
    }
    @objc private func tap(_ recognizer: UITapGestureRecognizer) {
        guard canManipulate, let view = arView else { return }
        selection = owns(view.entity(at: recognizer.location(in: view)))
    }
    @objc private func doubleTap(_ recognizer: UITapGestureRecognizer) {
        toggleScale()
    }
    /// A finger lands (ARMV `touchDown`): whatever still glides or spins stops where it is; a
    /// glide that stops re-arms the landing, as a drag that ends does.
    func touchDown() {
        stopCoasts()
    }
    private func stopCoasts() {
        stopGlide()
        twistCoast.stop()
    }
    /// The pixel position ARMV measures velocities in.
    private func pixels(_ point: CGPoint, in view: UIView) -> [Float] {
        [Float(point.x * view.contentScaleFactor), Float(point.y * view.contentScaleFactor)]
    }
    @objc private func pan(_ recognizer: UIPanGestureRecognizer) {
        guard canManipulate, let view = arView, let frame = view.session.currentFrame else { return }
        let point = recognizer.location(in: view)
        let now = CACurrentMediaTime()
        let active = activeGestures.contains(ObjectIdentifier(recognizer))
        if recognizer.state == .began {
            // Compute the contact-plane intersection, not a model bounding-box centre.
            guard let ray = view.ray(through: point) else { return }
            let transform = pivot.transformMatrix(relativeTo: nil)
            let normal = simd_normalize((transform * SIMD4<Float>(normalAxis, 0)).xyz)
            let denominator = simd_dot(ray.direction, normal)
            guard abs(denominator) > 0.0001 else { return }
            let t = simd_dot(transform.columns.3.xyz - ray.origin, normal) / denominator
            guard t > 0 else { return }
            grabOffset = transform.columns.3.xyz - (ray.origin + t * ray.direction)
            handFollowToFinger()
            moveCoast.reset()
            moveCoast.addSample(time: now, pixels(point, in: view))
            begin(recognizer)
        } else if recognizer.state == .changed, active {
            moveCoast.addSample(time: now, pixels(point, in: view))
            invalidMovement = !drag(to: point, in: view, frame: frame)
        } else if recognizer.state == .ended, active {
            // A throw glides on (ARMV `moveCoast`); landing is re-armed when the glide ends.
            moveCoast.addSample(time: now, pixels(point, in: view))
            if moveCoast.release(time: now) {
                glidePointer = point
            } else if instant != nil {
                // Dropped somewhere new: landing there gets a fresh set of attempts.
                instantState.moved()
            }
            end(recognizer)
        } else if [.ended, .cancelled, .failed].contains(recognizer.state) {
            moveCoast.reset()
            if instant != nil, active { instantState.moved() }
            end(recognizer)
        }
    }
    /// Moves the model under `point` (a finger, or the glide's virtual one): onto the detected
    /// plane there, or along the estimated floor while approximate. `false` when refused.
    private func drag(to point: CGPoint, in view: ARView, frame: ARFrame) -> Bool {
        if instant != nil { return dragApproximately(to: point, in: view, frame: frame) }
        guard let hit = raycast(point, in: view, frame: frame) else { return false }
        var proposed = hit.transform
        let n = alignment == .horizontal ? proposed.columns.1.xyz : proposed.columns.2.xyz
        proposed.columns.3 = SIMD4<Float>(proposed.columns.3.xyz + Self.tangentOffset(grabOffset, normal: n), 1)
        guard valid(plane: hit.plane, point: proposed.columns.3.xyz, frame: frame, view: view) else { return false }
        commitMove(to: proposed, plane: hit.plane)
        return true
    }
    /// One render frame of the glide (ARMV `glide`): the virtual finger carries on, slowing
    /// down, and the model follows it at ``ARPlacementFeel/dragSpeed``. It stops where the
    /// finger would leave the screen or find no surface.
    func stepGlide(by delta: TimeInterval) {
        guard moveCoast.isCoasting else { return }
        guard canManipulate, let view = arView, let frame = view.session.currentFrame,
              let pointer = glidePointer else { stopGlide(); return }
        let (step, goesOn) = moveCoast.advance(by: Float(delta))
        guard let next = ARPlacementFeel.glide(pointer: pointer, step: step, pixelScale: view.contentScaleFactor,
                                               bounds: view.bounds),
              drag(to: next, in: view, frame: frame) else { stopGlide(); return }
        glidePointer = next
        if !goesOn { stopGlide() }
    }
    /// Ends a glide where it is and re-arms the landing (ARMV `requestAnchor`).
    private func stopGlide() {
        guard moveCoast.isCoasting || glidePointer != nil else { return }
        moveCoast.stop()
        glidePointer = nil
        if activeGestures.isEmpty { discardPendingMove() }
        if instant != nil { instantState.moved() }
    }
    /// Moves the model to the contact `transform` on `plane`, easing at `speed` per second.
    /// `rotation` (world) replaces the kept one; `easeRotation` turns it with the position.
    private func commitMove(to transform: simd_float4x4, plane: ARPlaneAnchor, rotation: simd_quatf? = nil,
                            speed: Float = ARPlacementFeel.dragSpeed, easeRotation: Bool = false) {
        guard let view = arView else { return }
        let current = pivot.transformMatrix(relativeTo: nil)
        // Preserve the rotation relative to the old surface frame and the scale around contact.
        let oldContact = result?.worldTransform ?? current
        let relativeRotation = simd_quatf(oldContact).inverse * Transform(matrix: current).rotation
        var next = Transform(matrix: transform)
        next.rotation = alignment == .horizontal
            ? Transform(matrix: current).rotation
            : simd_quatf(transform) * relativeRotation
        if let rotation { next.rotation = rotation }
        if surface?.identifier != plane.identifier {
            let replacement: AnchorEntity
            if let pendingMove, pendingMove.plane.identifier == plane.identifier {
                replacement = pendingMove.anchor
            } else {
                discardPendingMove()
                replacement = AnchorEntity(anchor: plane)
                view.scene.addAnchor(replacement)
            }
            guard replacement.isAnchored else {
                pendingMove = (replacement, plane, transform, next, speed, easeRotation)
                return
            }
            pendingMove = nil
            applyMove(anchor: replacement, plane: plane, contact: transform, transform: next,
                      speed: speed, easeRotation: easeRotation)
        } else if let anchor {
            discardPendingMove()
            applyMove(anchor: anchor, plane: plane, contact: transform, transform: next,
                      speed: speed, easeRotation: easeRotation)
        }
    }
    /// The pivot keeps its world pose across the re-parent and eases to `transform` (world) at
    /// `speed` — ARMV `moveTo(…, DragSpeed)` under the finger. The scale is the display's.
    private func applyMove(anchor replacement: AnchorEntity, plane: ARPlaneAnchor,
                           contact: simd_float4x4, transform: Transform, speed: Float, easeRotation: Bool) {
        if anchor !== replacement {
            let previousParent = pivot.parent?.orientation(relativeTo: nil) ?? simd_quatf(angle: 0, axis: [0, 1, 0])
            replacement.addChild(pivot, preservingWorldTransform: true)
            twistBaseline = simd_normalize(replacement.orientation(relativeTo: nil).inverse * previousParent * twistBaseline)
            if let anchor { arView?.scene.removeAnchor(anchor) }
            anchor = replacement
        }
        if !easeRotation { pivot.setOrientation(transform.rotation, relativeTo: nil) }
        follow = ARInstantPlacement.Follow(target: transform.translation,
                                           rotation: easeRotation ? transform.rotation : nil, speed: speed)
        surface = plane
        result = .init(surfaceIdentifier: plane.identifier, anchor: replacement, worldTransform: contact)
    }
    @objc private func pinch(_ recognizer: UIPinchGestureRecognizer) {
        guard canManipulate else { return }
        if recognizer.state == .began { scaleDisplay.pinchBegan(); begin(recognizer) }
        if recognizer.state == .changed {
            // The pinch snaps to exactly 100 % within ±4 %, with a short elastic rebound; the
            // public ``scale(to:)`` stays exact.
            send(scaleDisplay.pinch(ratio: Float(recognizer.scale)))
        }
        if [.ended, .cancelled, .failed].contains(recognizer.state) { end(recognizer) }
    }
    private func send(_ events: [ARHapticEvent]) {
        applyShownScale()
        publishScale()
        for event in events { hapticEvents.send(event) }
    }
    /// One render frame of the scale animations: rebound, double-tap ease, grow-in.
    private func stepScale(by delta: TimeInterval) {
        guard scaleDisplay.animating else { return }
        scaleDisplay.step(by: delta, reduceMotion: UIAccessibility.isReduceMotionEnabled)
        applyShownScale()
    }
    @objc private func twist(_ recognizer: UIRotationGestureRecognizer) {
        guard canManipulate else { return }
        let now = CACurrentMediaTime()
        let active = activeGestures.contains(ObjectIdentifier(recognizer))
        if recognizer.state == .began {
            handFollowRotationToTwist()
            twistCoast.reset()
            twistCoast.addSample(time: now, [0])
            begin(recognizer)
        }
        if recognizer.state == .changed {
            pivot.orientation = twistBaseline * simd_quatf(angle: -Float(recognizer.rotation), axis: normalAxis)
            // ARMV samples the cumulative twist in degrees, the coast's unit.
            twistCoast.addSample(time: now, [-Float(recognizer.rotation) * 180 / .pi])
        }
        if recognizer.state == .ended, active { twistCoast.release(time: now) }
        if [.cancelled, .failed].contains(recognizer.state) { twistCoast.reset() }
        if [.ended, .cancelled, .failed].contains(recognizer.state) { end(recognizer) }
    }
    /// One render frame of the spin after a flicked twist (ARMV `twistCoast`).
    func stepTwistCoast(by delta: TimeInterval) {
        guard twistCoast.isCoasting else { return }
        guard canManipulate else { twistCoast.stop(); return }
        let degrees = twistCoast.advance(by: Float(delta)).step[0]
        turn(by: degrees * .pi / 180)
    }
    /// Accessibility alternative: rotate around the grounded contact pivot, in radians.
    public func rotate(by radians: Float) {
        guard canManipulate, radians.isFinite else { return }
        turn(by: radians)
    }
    private func turn(by radians: Float) {
        let turn = simd_quatf(angle: radians, axis: normalAxis)
        pivot.orientation *= turn
        // A rotation still being eased toward turns with it, instead of overwriting the turn.
        if let rotation = follow?.rotation { follow?.rotation = simd_normalize(rotation * turn) }
    }
    /// Uniform multiplier of the chosen base size (25–400%), preserving surface contact.
    public func scale(to value: Float) {
        guard canManipulate, value.isFinite else { return }
        send(scaleDisplay.set(value))
    }
    /// The double tap (ARMV `toggleScale`): back to 100 % from any other size, or from 100 %
    /// back to the size the user had chosen, eased over 260 ms. With nowhere to go, the 100 %
    /// rebound plays. Also an accessibility alternative to the gesture.
    public func toggleScale() {
        guard canManipulate else { return }
        send(scaleDisplay.toggle(reduceMotion: UIAccessibility.isReduceMotionEnabled))
    }
    /// Accessibility alternative: metres along the detected surface's two tangent axes — or,
    /// while the placement is approximate, along the estimated floor (or wall). A refused move
    /// plays ``ARHapticEvent/invalidMove`` and raises ``invalidMovement``.
    @discardableResult
    public func move(by offset: SIMD2<Float>) -> Bool {
        guard canManipulate, let view = arView, let frame = view.session.currentFrame else { return false }
        if let runtime = instant { return moveApproximately(by: offset, contact: runtime.contact, frame: frame) }
        guard let plane = surface, let contact = result?.worldTransform else { return false }
        var next = contact
        let tangent = alignment == .horizontal ? contact.columns.2.xyz : contact.columns.1.xyz
        next.columns.3 += SIMD4<Float>(contact.columns.0.xyz * offset.x + tangent * offset.y, 0)
        guard valid(plane: plane, point: next.columns.3.xyz, frame: frame, view: view) else {
            // Every refused button press is felt, not only the first one that raises the flag.
            hapticEvents.send(.invalidMove)
            invalidMovement = true
            return false
        }
        handFollowToFinger()
        commitMove(to: next, plane: plane)
        invalidMovement = false
        return true
    }

    // MARK: Instant placement — rules and constants in ARInstantPlacement.swift

    /// The model's largest side at its current scale, in metres: what its reach is made of.
    private var largestSide: Float {
        guard let model else { return 0 }
        let extents = model.visualBounds(relativeTo: pivot).extents * scale
        return max(extents.x, max(extents.y, extents.z))
    }

    /// Stands the model on the estimated floor along the centre ray (walls: on the estimated
    /// wall under the centre). Runs only when no detected plane is usable this frame and tracking
    /// has run for ``ARInstantPlacement/autoPlaceDelay``.
    private func placeApproximately(in view: ARView, frame: ARFrame, planes: [ARPlaneAnchor]) {
        guard view.bounds.width > 0, view.bounds.height > 0 else { return }
        let camera = frame.camera.transform
        let center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
        let estimate = estimatedHit(at: center, in: view, camera: camera)
        let point: SIMD3<Float>
        var wallNormal: SIMD3<Float>?
        switch alignment {
        case .horizontal:
            guard let onFloor = floorPointAhead(in: view, frame: frame, planes: planes, estimate: estimate?.point)
            else { return }
            point = onFloor
        case .vertical:
            // No imagined wall: a wall object waits for ARKit's estimate of the wall in view.
            guard let estimate else { return }
            point = estimate.point
            wallNormal = estimate.normal
        }
        let contact = ARInstantPlacement.approximateContact(point: point, camera: camera, wallNormal: wallNormal)
        let anchor = AnchorEntity(world: contact)
        anchor.addChild(pivot)
        pivot.transform = Transform(scale: SIMD3<Float>(repeating: scale))
        view.scene.addAnchor(anchor)
        self.anchor = anchor
        surface = nil
        result = nil
        follow = nil
        instant = ARInstantPlacement.Runtime(contact: contact)
        commitPlacement()
    }

    /// Where the centre ray meets the floor as far as ARKit knows it, between half the model's
    /// reach and its reach ahead (ARMV `floorPointAlong`); `nil` when the ray never gets there.
    private func floorPointAhead(in view: ARView, frame: ARFrame, planes: [ARPlaneAnchor],
                                 estimate: SIMD3<Float>?) -> SIMD3<Float>? {
        guard let ray = view.ray(through: CGPoint(x: view.bounds.midX, y: view.bounds.midY)) else { return nil }
        let floors = planes.filter {
            $0.alignment == .horizontal && $0.transform.columns.1.y > 0.5 && $0.classification != .ceiling
        }.map { ($0.transform * SIMD4<Float>($0.center, 1)).y }
        let floorY = ARInstantPlacement.floorHeight(cameraY: frame.camera.transform.columns.3.y,
                                                    surfaceHeights: floors + (estimate.map { [$0.y] } ?? []))
        return ARInstantPlacement.floorPoint(origin: ray.origin, direction: ray.direction, floorY: floorY,
                                             reach: ARInstantPlacement.reach(forLargestSide: largestSide))
    }

    /// A usable detected floor plane at `point`'s height (±10 cm) that contains it, and the
    /// point on it.
    private func plane(under point: SIMD3<Float>, planes: [ARPlaneAnchor], frame: ARFrame,
                       view: ARView) -> (plane: ARPlaneAnchor, point: SIMD3<Float>)? {
        for plane in planes where plane.alignment == .horizontal {
            let local = (plane.transform.inverse * SIMD4<Float>(point, 1)).xyz
            guard abs(local.y) < 0.1 else { continue }
            let onPlane = (plane.transform * SIMD4<Float>(local.x, 0, local.z, 1)).xyz
            if valid(plane: plane, point: onPlane, frame: frame, view: view) { return (plane, onPlane) }
        }
        return nil
    }

    /// An ARKit estimated-plane hit at `screen` that may carry the model, else `nil`.
    private func estimatedHit(at screen: CGPoint, in view: ARView,
                              camera: simd_float4x4) -> (point: SIMD3<Float>, normal: SIMD3<Float>)? {
        guard let query = view.makeRaycastQuery(from: screen, allowing: .estimatedPlane,
                                                alignment: alignment == .horizontal ? .horizontal : .vertical),
              let hit = view.session.raycast(query).first else { return nil }
        let point = hit.worldTransform.columns.3.xyz
        let normal = hit.worldTransform.columns.1.xyz
        guard ARInstantPlacement.acceptsEstimatedHit(point: point, normal: normal, camera: camera,
                                                     alignment: alignment) else { return nil }
        return (point, normal)
    }

    /// Where the model's own pixel meets a usable detected plane (`planeID`, or any), within
    /// ``ARInstantPlacement/maxSurfaceDistance`` — the spot it lands on, so it keeps its place
    /// on screen and only its depth corrects (an ARCore instant point does the same).
    private func landingSpot(on planeID: UUID?, in view: ARView, frame: ARFrame) -> (plane: ARPlaneAnchor, point: SIMD3<Float>)? {
        guard let screen = view.project(pivot.position(relativeTo: nil)), view.bounds.contains(screen) else { return nil }
        for hit in view.raycast(from: screen, allowing: .existingPlaneGeometry,
                                alignment: alignment == .horizontal ? .horizontal : .vertical) {
            guard let plane = hit.anchor as? ARPlaneAnchor, planeID == nil || plane.identifier == planeID else { continue }
            let point = hit.worldTransform.columns.3.xyz
            if valid(plane: plane, point: point, frame: frame, view: view,
                     maxDistance: ARInstantPlacement.maxSurfaceDistance) { return (plane, point) }
        }
        return nil
    }

    /// Re-casts the model's screen position (at most every
    /// ``ARInstantPlacement/refineInterval``): a detected plane there lands it (never during a
    /// gesture, with backoff only for anchors that do not resolve); an estimated surface there
    /// moves its follow target. Nothing here is asynchronous, so nothing can outlive a reset:
    /// ``stopInstantPlacement()`` removes a pending anchor with the rest.
    private func refineApproximatePlacement(in view: ARView, frame: ARFrame) {
        guard var runtime = instant else { return }
        let now = frame.timestamp
        guard instantState.refineTick(now: now) else { return }
        if let pending = runtime.pending {
            let spot = landingSpot(on: pending.plane, in: view, frame: frame)
            switch instantState.step(gesturesActive: manipulating, anchored: pending.anchor.isAnchored,
                                     overSurface: spot != nil, now: now, since: pending.since) {
            case .commit:
                land(on: spot!.plane, anchor: pending.anchor, point: spot!.point, frame: frame)
            case .hold, .wait:
                break
            case .lost:
                view.scene.removeAnchor(pending.anchor)
                instant?.pending = nil
                instantState.lost(now: now, plane: pending.plane, area: pending.area)
            case .retry:
                view.scene.removeAnchor(pending.anchor)
                instant?.pending = nil
                instantState.failed(now: now, plane: pending.plane, area: pending.area)
            case .abandon:
                abandonAnchorRecovery()
            }
            return
        }
        // The finger owns the position while dragging or gliding; refinement resumes after.
        guard !isActive(UIPanGestureRecognizer.self), !moveCoast.isCoasting else { return }
        if let spot = landingSpot(on: nil, in: view, frame: frame),
           instantState.mayAttemptLanding(on: spot.plane.identifier, area: Self.area(of: spot.plane), now: now) {
            let target = AnchorEntity(anchor: spot.plane)
            view.scene.addAnchor(target)
            runtime.pending = (target, spot.plane.identifier, Self.area(of: spot.plane), now)
            instant = runtime
            if target.isAnchored, !manipulating { land(on: spot.plane, anchor: target, point: spot.point, frame: frame) }
            return
        }
        guard let screen = view.project(pivot.position(relativeTo: nil)), view.bounds.contains(screen),
              let estimate = estimatedHit(at: screen, in: view, camera: frame.camera.transform) else { return }
        if follow == nil { follow = ARInstantPlacement.Follow(target: pivot.position(relativeTo: nil)) }
        if follow?.retarget(estimate.point) == true { follow?.speed = ARInstantPlacement.settleSpeed }
    }

    /// A gesture or one of its coasts holds the pivot.
    private var manipulating: Bool { !activeGestures.isEmpty || moveCoast.isCoasting || twistCoast.isCoasting }

    /// ARMV `abandonAnchorRecovery`: the landing anchor that never resolved goes, and the model
    /// stays exactly where it stands — nothing pulls it back, nothing retries on a timer. The
    /// next move by the user re-arms the landing (see ``ARInstantPlacement/State``).
    private func abandonAnchorRecovery() {
        guard let pending = instant?.pending else { return }
        arView?.scene.removeAnchor(pending.anchor)
        instant?.pending = nil
        instantState.abandonAnchorRecovery(plane: pending.plane, area: pending.area)
    }

    private static func area(of plane: ARPlaneAnchor) -> Float {
        plane.planeExtent.width * plane.planeExtent.height
    }

    /// A drag or a move takes the pivot over. A rotation still easing (a wall landing re-facing
    /// the wall) is applied first, so the model never keeps a partial yaw. While approximate the
    /// position stops where it is. After landing, the glide to the landing point goes on until
    /// a move is accepted (``applyMove`` ends it): a drag or move refused at a young plane's edge
    /// must not leave the model frozen between the estimated height and the floor.
    func handFollowToFinger() {
        if let rotation = follow?.rotation { pivot.setOrientation(rotation, relativeTo: nil) }
        if instant != nil { follow = nil } else { follow?.rotation = nil }
    }

    /// A twist takes the rotation over from where it is on screen — no snap to the rest of an
    /// easing rotation. The position keeps easing.
    func handFollowRotationToTwist() {
        follow?.rotation = nil
        twistBaseline = pivot.orientation
    }

    /// Moves the pivot onto the resolved plane anchor without a visible jump, then eases it to
    /// the landing spot. From here on the placement is a normal plane placement.
    private func land(on plane: ARPlaneAnchor, anchor target: AnchorEntity, point: SIMD3<Float>, frame: ARFrame) {
        guard let runtime = instant else { return }
        let surfaceContact = contactTransform(plane: plane, point: point, camera: frame.camera.transform)
        let rotation = ARInstantPlacement.refinedRotation(alignment: alignment,
                                                          model: pivot.orientation(relativeTo: nil),
                                                          approximateContact: simd_quatf(runtime.contact),
                                                          surfaceContact: simd_quatf(surfaceContact))
        let previousParent = pivot.parent?.orientation(relativeTo: nil) ?? simd_quatf(angle: 0, axis: [0, 1, 0])
        target.addChild(pivot, preservingWorldTransform: true)
        // Landing waits for gestures to end; should one still be running, its baseline moves
        // into the new parent's space with the pivot, so the next change cannot jump.
        twistBaseline = simd_normalize(target.orientation(relativeTo: nil).inverse * previousParent * twistBaseline)
        if let previous = anchor, previous !== target { arView?.scene.removeAnchor(previous) }
        anchor = target
        surface = plane
        result = .init(surfaceIdentifier: plane.identifier, anchor: target, worldTransform: surfaceContact)
        instant = nil
        instantState.moved()
        follow = ARInstantPlacement.Follow(target: point, rotation: alignment == .vertical ? rotation : nil)
        hapticEvents.send(.placed)
    }

    /// Drag while approximate (Android `floorUnder`): onto a detected plane under the finger
    /// when there is one, else along the plane the model stands on, within
    /// ``ARInstantPlacement/maxSurfaceDistance``.
    private func dragApproximately(to screen: CGPoint, in view: ARView, frame: ARFrame) -> Bool {
        let current = pivot.transformMatrix(relativeTo: nil)
        let next: SIMD3<Float>?
        if let hit = raycast(screen, in: view, frame: frame) {
            let n = alignment == .horizontal ? hit.transform.columns.1.xyz : hit.transform.columns.2.xyz
            next = hit.transform.columns.3.xyz + Self.tangentOffset(grabOffset, normal: n)
        } else if let ray = view.ray(through: screen) {
            let normal = simd_normalize((current * SIMD4<Float>(normalAxis, 0)).xyz)
            next = ARInstantPlacement.pointOnContactPlane(rayOrigin: ray.origin, rayDirection: ray.direction,
                                                          point: current.columns.3.xyz, normal: normal)
                .map { $0 + Self.tangentOffset(grabOffset, normal: normal) }
        } else { next = nil }
        guard let next, ARInstantPlacement.withinReach(next, from: frame.camera.transform.columns.3.xyz) else {
            return false
        }
        // Smoothed under the finger at ARMV `DragSpeed`, never set raw.
        if follow == nil {
            follow = ARInstantPlacement.Follow(target: next, speed: ARPlacementFeel.dragSpeed)
        } else {
            follow?.track(next)
            follow?.speed = ARPlacementFeel.dragSpeed
        }
        return true
    }

    /// ``move(by:)`` while approximate: along the estimated contact's tangent axes.
    private func moveApproximately(by offset: SIMD2<Float>, contact: simd_float4x4, frame: ARFrame) -> Bool {
        let tangent = alignment == .horizontal ? contact.columns.2.xyz : contact.columns.1.xyz
        let from = follow?.target ?? pivot.position(relativeTo: nil)
        let next = from + contact.columns.0.xyz * offset.x + tangent * offset.y
        let camera = frame.camera.transform
        let eye = camera.columns.3.xyz
        guard offset.x.isFinite, offset.y.isFinite, (camera.inverse * SIMD4<Float>(next, 1)).z < 0,
              simd_length(next - eye) >= 0.25, ARInstantPlacement.withinReach(next, from: eye) else {
            hapticEvents.send(.invalidMove)
            invalidMovement = true
            return false
        }
        handFollowToFinger()
        pivot.setPosition(next, relativeTo: nil)
        instantState.moved()
        invalidMovement = false
        return true
    }

    /// One render frame of the follow: exponential, interruptible, never waited on.
    func stepFollow(by delta: TimeInterval) {
        guard let follow, pivot.parent != nil else { return }
        let alpha = follow.alpha(delta: delta, reduceMotion: UIAccessibility.isReduceMotionEnabled)
        guard alpha > 0 else { return }
        pivot.setPosition(follow.position(from: pivot.position(relativeTo: nil), alpha: alpha), relativeTo: nil)
        // The twist owns the rotation while it runs; it took the eased rotation when it began.
        if let rotation = follow.rotation, !isActive(UIRotationGestureRecognizer.self) {
            pivot.setOrientation(simd_slerp(pivot.orientation(relativeTo: nil), rotation, alpha), relativeTo: nil)
        }
        if follow.arrived(at: pivot.position(relativeTo: nil), rotation: pivot.orientation(relativeTo: nil)) {
            pivot.setPosition(follow.target, relativeTo: nil)
            if let rotation = follow.rotation { pivot.setOrientation(rotation, relativeTo: nil) }
            self.follow = nil
        }
    }

    // MARK: Recenter

    /// Brings the model back in front of the user (ARMV `recenter`): it glides at
    /// ``ARInstantPlacement/settleSpeed`` to where the centre ray meets the floor, between half
    /// its reach and its reach ahead — on the detected plane there, else (``instantPlacement``
    /// on, or already approximate) on the estimated floor. Walls: the wall under the centre.
    /// The pinch and the twist are kept: a floor model turns with the camera's bearing, so the
    /// side the user saw still faces them. Plays ``ARHapticEvent/placed``.
    ///
    /// - Returns: `false`, with nothing changed, when nothing is placed or no surface is in view.
    @discardableResult
    public func recenter() -> Bool {
        guard canManipulate, let view = arView, let frame = view.session.currentFrame,
              view.bounds.width > 0, view.bounds.height > 0 else { return false }
        let camera = frame.camera.transform
        let eye = camera.columns.3.xyz
        let center = CGPoint(x: view.bounds.midX, y: view.bounds.midY)
        let planes = frame.anchors.compactMap { $0 as? ARPlaneAnchor }
        let current = pivot.transformMatrix(relativeTo: nil)
        let currentRotation = follow?.rotation ?? pivot.orientation(relativeTo: nil)
        let oldContact = instant?.contact ?? result?.worldTransform ?? current
        func rotation(at contact: simd_float4x4) -> simd_quatf {
            alignment == .horizontal
                ? ARPlacementFeel.recenteredRotation(currentRotation, from: current.columns.3.xyz,
                                                     to: contact.columns.3.xyz, eye: eye)
                : simd_normalize(simd_quatf(contact) * simd_quatf(oldContact).inverse * currentRotation)
        }
        let estimate = estimatedHit(at: center, in: view, camera: camera)
        // Plane mode: a detected plane ahead, at the floor point or under the centre.
        var onPlane: Candidate?
        var approximate: (point: SIMD3<Float>, wallNormal: SIMD3<Float>?)?
        switch alignment {
        case .horizontal:
            let ahead = floorPointAhead(in: view, frame: frame, planes: planes, estimate: estimate?.point)
            if instant == nil {
                if let ahead, let spot = plane(under: ahead, planes: planes, frame: frame, view: view) {
                    onPlane = Candidate(plane: spot.plane, transform: contactTransform(plane: spot.plane, point: spot.point,
                                                                                       camera: camera))
                } else {
                    onPlane = raycast(center, in: view, frame: frame)
                }
            }
            if onPlane == nil, instant != nil || instantPlacement, let ahead { approximate = (ahead, nil) }
        case .vertical:
            if instant == nil { onPlane = raycast(center, in: view, frame: frame) }
            if onPlane == nil, instant != nil || instantPlacement, let estimate { approximate = (estimate.point, estimate.normal) }
        }
        guard onPlane != nil || approximate != nil else { return false }
        cancelGestures()
        if let onPlane {
            commitMove(to: onPlane.transform, plane: onPlane.plane, rotation: rotation(at: onPlane.transform),
                       speed: ARInstantPlacement.settleSpeed, easeRotation: true)
        } else if let approximate {
            let contact = ARInstantPlacement.approximateContact(point: approximate.point, camera: camera,
                                                                wallNormal: approximate.wallNormal)
            let target = AnchorEntity(world: contact)
            view.scene.addAnchor(target)
            let previousParent = pivot.parent?.orientation(relativeTo: nil) ?? simd_quatf(angle: 0, axis: [0, 1, 0])
            target.addChild(pivot, preservingWorldTransform: true)
            twistBaseline = simd_normalize(target.orientation(relativeTo: nil).inverse * previousParent * twistBaseline)
            if let pending = instant?.pending { view.scene.removeAnchor(pending.anchor) }
            if let previous = anchor, previous !== target { view.scene.removeAnchor(previous) }
            anchor = target
            surface = nil
            result = nil
            instant = ARInstantPlacement.Runtime(contact: contact)
            instantState.moved()
            follow = ARInstantPlacement.Follow(target: approximate.point, rotation: rotation(at: contact),
                                               speed: ARInstantPlacement.settleSpeed)
        }
        invalidMovement = false
        hapticEvents.send(.placed)
        publishPhase()
        return true
    }

    private func stopInstantPlacement() {
        if let pending = instant?.pending { arView?.scene.removeAnchor(pending.anchor) }
        instant = nil
        follow = nil
        instantState.reset()
    }
}

private extension SIMD4 where Scalar == Float {
    var xyz: SIMD3<Float> { SIMD3<Float>(x, y, z) }
}
#endif
