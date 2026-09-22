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
    mutating func frame(now: TimeInterval, tracking: Bool, anchorTracking: Bool, assetReady: Bool) -> Bool {
        guard acceptsFrames else { return false }
        guard tracking else {
            phase = .trackingLost
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
    public var hasPlacement: Bool { lifecycle.hasPlacement }

    private var lifecycle = ARPlacementLifecycle()
    private weak var arView: ARView?
    private var anchor: AnchorEntity?
    private var surface: ARPlaneAnchor?
    private var pendingPlacementResult: ARPlacementResult?
    /// Frame timestamp at which the still-unresolved anchor was created. See
    /// ``ARPlacementLifecycle/pendingAnchorDecision(anchored:surfaceUsable:now:since:)``.
    private var pendingAnchorSince: TimeInterval?
    private var pendingMove: (anchor: AnchorEntity, plane: ARPlaneAnchor, contact: simd_float4x4, transform: Transform)?
    private var pivot = Entity()
    private var model: Entity?
    private var selectedAssetReady = false
    private var recognizers: [UIGestureRecognizer] = []
    private var activeGestures: Set<ObjectIdentifier> = []
    private var grabOffset = SIMD3<Float>.zero
    private var pinchBaseline: Float = 1
    private var twistBaseline = simd_quatf(angle: 0, axis: [0, 1, 0])
    private var lastTimestamp: TimeInterval?
    private var visibility: Float = 0
    private var visibilityTarget: Float = 0
    private var renderSubscription: (any Cancellable)?

    public init(alignment: ARPlacementAlignment = .horizontal) {
        self.alignment = alignment
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
    public func resetPlacement() {
        guard !lifecycle.dismissed else { return }
        cancelGestures()
        removeAnchor()
        pivot.transform = .identity
        scale = 1
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
        scale = 1
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
    }
    private func publishPhase() { if phase != lifecycle.phase { phase = lifecycle.phase } }

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
            }
        }
        pivot.components.set(OpacityComponent(opacity: 0))
        let tap = UITapGestureRecognizer(target: self, action: #selector(tap(_:)))
        let pan = UIPanGestureRecognizer(target: self, action: #selector(pan(_:)))
        pan.maximumNumberOfTouches = 1
        let pinch = UIPinchGestureRecognizer(target: self, action: #selector(pinch(_:)))
        let twist = UIRotationGestureRecognizer(target: self, action: #selector(twist(_:)))
        recognizers = [tap, pan, pinch, twist]
        for recognizer in recognizers {
            recognizer.delegate = self
            view.addGestureRecognizer(recognizer)
        }
    }

    func sessionEvent(_ event: ARSessionEvent) {
        switch event {
        case .interrupted:
            _ = lifecycle.frame(now: 0, tracking: false, anchorTracking: false, assetReady: selectedAssetReady)
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
        let anchored = anchor?.isAnchored == true && planes.contains { $0.identifier == surface?.identifier }
        let search = lifecycle.frame(now: frame.timestamp, tracking: normal, anchorTracking: anchored, assetReady: selectedAssetReady)
        if !normal || (hasPlacement && !anchored) { cancelGestures() }
        visibilityTarget = normal && hasPlacement && anchored ? 1 : 0
        if normal, anchored, let move = pendingMove, move.anchor.isAnchored {
            if let currentPlane = planes.first(where: { $0.identifier == move.plane.identifier }),
               valid(plane: currentPlane, point: move.contact.columns.3.xyz, frame: frame, view: view) {
                applyMove(anchor: move.anchor, plane: currentPlane, contact: move.contact, transform: move.transform)
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
                lifecycle.commit()
                selection = true
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
                lifecycle.commit()
                selection = true
            }
        }
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

    private func valid(plane: ARPlaneAnchor, point: SIMD3<Float>, frame: ARFrame, view: ARView) -> Bool {
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
        guard cameraPoint.z < 0, distance >= 0.25, distance <= 3,
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
    public func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                                  shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        (gestureRecognizer is UIPinchGestureRecognizer && otherGestureRecognizer is UIRotationGestureRecognizer)
            || (gestureRecognizer is UIRotationGestureRecognizer && otherGestureRecognizer is UIPinchGestureRecognizer)
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
        discardPendingMove()
        for recognizer in recognizers where activeGestures.contains(ObjectIdentifier(recognizer)) {
            recognizer.isEnabled = false
            recognizer.isEnabled = true
        }
        activeGestures.removeAll()
        invalidMovement = false
        if lifecycle.phase == .adjusting { lifecycle.phase = .placed }
    }
    @objc private func tap(_ recognizer: UITapGestureRecognizer) {
        guard canManipulate, let view = arView else { return }
        selection = owns(view.entity(at: recognizer.location(in: view)))
    }
    @objc private func pan(_ recognizer: UIPanGestureRecognizer) {
        guard canManipulate, let view = arView, let frame = view.session.currentFrame else { return }
        let point = recognizer.location(in: view)
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
            begin(recognizer)
        } else if recognizer.state == .changed, activeGestures.contains(ObjectIdentifier(recognizer)) {
            guard let hit = raycast(point, in: view, frame: frame) else { invalidMovement = true; return }
            var proposed = hit.transform
            let n = alignment == .horizontal ? proposed.columns.1.xyz : proposed.columns.2.xyz
            proposed.columns.3 = SIMD4<Float>(proposed.columns.3.xyz + Self.tangentOffset(grabOffset, normal: n), 1)
            guard valid(plane: hit.plane, point: proposed.columns.3.xyz, frame: frame, view: view) else { invalidMovement = true; return }
            commitMove(to: proposed, plane: hit.plane)
            invalidMovement = false
        } else if [.ended, .cancelled, .failed].contains(recognizer.state) { end(recognizer) }
    }
    private func commitMove(to transform: simd_float4x4, plane: ARPlaneAnchor) {
        guard let view = arView else { return }
        let current = pivot.transformMatrix(relativeTo: nil)
        // Preserve the rotation relative to the old surface frame and the scale around contact.
        let oldContact = result?.worldTransform ?? current
        let relativeRotation = simd_quatf(oldContact).inverse * Transform(matrix: current).rotation
        var next = Transform(matrix: transform)
        next.rotation = alignment == .horizontal
            ? Transform(matrix: current).rotation
            : simd_quatf(transform) * relativeRotation
        next.scale = SIMD3<Float>(repeating: scale)
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
                pendingMove = (replacement, plane, transform, next)
                return
            }
            pendingMove = nil
            applyMove(anchor: replacement, plane: plane, contact: transform, transform: next)
        } else if let anchor {
            discardPendingMove()
            applyMove(anchor: anchor, plane: plane, contact: transform, transform: next)
        }
    }
    private func applyMove(anchor replacement: AnchorEntity, plane: ARPlaneAnchor,
                           contact: simd_float4x4, transform: Transform) {
        if anchor !== replacement {
            replacement.addChild(pivot)
            if let anchor { arView?.scene.removeAnchor(anchor) }
            anchor = replacement
        }
        pivot.setTransformMatrix(transform.matrix, relativeTo: nil)
        surface = plane
        result = .init(surfaceIdentifier: plane.identifier, anchor: replacement, worldTransform: contact)
    }
    @objc private func pinch(_ recognizer: UIPinchGestureRecognizer) {
        guard canManipulate else { return }
        if recognizer.state == .began { pinchBaseline = scale; begin(recognizer) }
        if recognizer.state == .changed { scale(to: pinchBaseline * Float(recognizer.scale)) }
        if [.ended, .cancelled, .failed].contains(recognizer.state) { end(recognizer) }
    }
    @objc private func twist(_ recognizer: UIRotationGestureRecognizer) {
        guard canManipulate else { return }
        if recognizer.state == .began { twistBaseline = pivot.orientation; begin(recognizer) }
        if recognizer.state == .changed { pivot.orientation = twistBaseline * simd_quatf(angle: -Float(recognizer.rotation), axis: normalAxis) }
        if [.ended, .cancelled, .failed].contains(recognizer.state) { end(recognizer) }
    }
    /// Accessibility alternative: rotate around the grounded contact pivot, in radians.
    public func rotate(by radians: Float) {
        guard canManipulate, radians.isFinite else { return }
        pivot.orientation *= simd_quatf(angle: radians, axis: normalAxis)
    }
    /// Uniform multiplier of the chosen base size (25–400%), preserving surface contact.
    public func scale(to value: Float) {
        guard canManipulate, value.isFinite else { return }
        scale = max(0.25, min(4, value))
        pivot.scale = SIMD3<Float>(repeating: scale)
    }
    /// Accessibility alternative: metres along the detected surface's two tangent axes.
    @discardableResult
    public func move(by offset: SIMD2<Float>) -> Bool {
        guard canManipulate, let view = arView, let frame = view.session.currentFrame,
              let plane = surface, let contact = result?.worldTransform else { return false }
        var next = contact
        let tangent = alignment == .horizontal ? contact.columns.2.xyz : contact.columns.1.xyz
        next.columns.3 += SIMD4<Float>(contact.columns.0.xyz * offset.x + tangent * offset.y, 0)
        guard valid(plane: plane, point: next.columns.3.xyz, frame: frame, view: view) else { invalidMovement = true; return false }
        commitMove(to: next, plane: plane)
        invalidMovement = false
        return true
    }
}

private extension SIMD4 where Scalar == Float {
    var xyz: SIMD3<Float> { SIMD3<Float>(x, y, z) }
}
#endif
