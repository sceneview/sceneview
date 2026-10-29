#if os(iOS)
import ARKit
import RealityKit
import UIKit

/// How precisely a placement made by ``ARPlacementController`` is tracked — the Swift twin of
/// ARCore's `InstantPlacementPoint.TrackingMethod`.
///
/// With ``ARPlacementController/instantPlacement`` on, the model appears on an estimated floor
/// (``approximate``) and moves onto the real surface once ARKit detects it (``fullTracking``).
/// Without instant placement every placement is ``fullTracking`` from the start. Read
/// ``ARPlacementController/trackingMethod`` to show a "Refining…" hint:
///
/// ```swift
/// if placement.trackingMethod == .approximate {
///     Text("Refining…")
/// }
/// ```
public enum ARPlacementTrackingMethod: String, Sendable {
    /// The pose is an estimate: the floor as far as ARKit knows it (its lowest detected or
    /// estimated horizontal surface, else a phone-height guess), at a distance that suits the
    /// model's size. Drag, pinch and twist already work.
    /// ARCore: `SCREENSPACE_WITH_APPROXIMATE_DISTANCE`.
    case approximate
    /// Anchored to a detected plane. Never goes back to ``approximate``. ARCore: `FULL_TRACKING`.
    case fullTracking

    /// The ARCore `InstantPlacementPoint.TrackingMethod` constant this state mirrors.
    var arCoreName: String {
        switch self {
        case .approximate: return "SCREENSPACE_WITH_APPROXIMATE_DISTANCE"
        case .fullTracking: return "FULL_TRACKING"
        }
    }
}

/// The device-free rules of instant placement, pinned by unit tests on the Simulator.
///
/// ``ARPlacementController`` feeds them ARKit values; nothing here touches a session. The
/// behaviour and the shared constants follow the SceneView Android AR model viewer's
/// `InstantPlacement`: auto-place on a real surface at once, else on the estimated floor after
/// ``autoPlaceDelay``; ease toward refined spots at ``settleSpeed``; back off anchor retries.
enum ARInstantPlacement {
    // MARK: Constants shared with Android (same names, Swift-cased)

    /// A surface farther than this is ignored, horizontally — ARKit planes get unreliable far away.
    static let maxSurfaceDistance: Float = 5
    /// How long the camera tracks before the model places itself on the estimated floor when no
    /// detected plane showed up. A detected plane places it at once.
    static let autoPlaceDelay: TimeInterval = 0.6
    /// How fast the model eases toward a refined spot, per second: slow, so it reads as a glide.
    static let settleSpeed: Float = 6
    /// Landing on a detected plane backs off for a few seconds, then leaves the model where it is.
    static let anchorRetryLimit = 6
    static let anchorRetryBase: TimeInterval = 0.1
    static let anchorRetryCap: TimeInterval = 0.8

    // MARK: SceneView iOS values

    /// The closest the estimated placement comes, as a share of ``reach(forLargestSide:)``.
    static let nearShare: Float = 0.5
    /// Assumed floor depth below the phone while ARKit knows no floor yet.
    static let phoneHeightGuess: Float = 1.3
    /// A horizontal surface counts as floor only this far below the phone (a table at chest
    /// height does not).
    static let floorClearance: Float = 0.4
    /// Refined spots closer than this to the current one are ignored: ARKit noise, not news.
    static let followDeadband: Float = 0.01

    /// How far ahead the estimated placement stands, horizontally, from the model's largest
    /// side in metres as shown (its preview size times ``ARPlacementController/scale``): far
    /// enough that a large model never surrounds the camera, never closer than 1.2 m, never
    /// farther than 4 m. A model at the default 0.3 m preview stands 1.2 m away; a 2 m sofa
    /// stands 2.9 m away only at real-world size (`previewSize: nil`) or a 2 m preview.
    static func reach(forLargestSide side: Float) -> Float {
        guard side.isFinite, side > 0 else { return 1.2 }
        return min(4, max(1.2, side + 0.9))
    }

    /// The floor height as ARKit knows it: the lowest horizontal surface well below the phone
    /// (detected planes, plus the estimated surface under the screen centre), else the
    /// phone-height guess.
    static func floorHeight(cameraY: Float, surfaceHeights: [Float]) -> Float {
        surfaceHeights.filter { $0.isFinite && $0 < cameraY - floorClearance }.min()
            ?? (cameraY - phoneHeightGuess)
    }

    /// Where the centre ray meets the floor, kept between ``nearShare`` × `reach` and `reach`
    /// ahead (horizontally) and always on the floor — never in mid-air along the ray. A ray
    /// above the horizon stands the model `reach` ahead. `nil` when the ray has no horizontal
    /// heading (looking straight down): the next frame decides.
    static func floorPoint(origin: SIMD3<Float>, direction: SIMD3<Float>, floorY: Float, reach: Float) -> SIMD3<Float>? {
        let heading = SIMD2<Float>(direction.x, direction.z)
        let length = simd_length(heading)
        guard length > 0.001, origin.x.isFinite, origin.y.isFinite, origin.z.isFinite, floorY.isFinite else { return nil }
        let unit = heading / length
        var distance = reach
        if direction.y < -0.0001 {
            let t = (floorY - origin.y) / direction.y
            if t > 0 { distance = min(reach, max(reach * nearShare, t * length)) }
        }
        return SIMD3(origin.x + unit.x * distance, floorY, origin.z + unit.y * distance)
    }

    /// Where a screen ray meets the plane the model stands on (through `point`, along `normal`),
    /// while the placement is still approximate — Android `floorUnder` without a detected plane.
    /// `nil` behind the camera or beyond ``maxSurfaceDistance`` horizontally.
    static func pointOnContactPlane(rayOrigin: SIMD3<Float>, rayDirection: SIMD3<Float>,
                                    point: SIMD3<Float>, normal: SIMD3<Float>) -> SIMD3<Float>? {
        let denominator = simd_dot(rayDirection, normal)
        guard abs(denominator) > 0.0001 else { return nil }
        let t = simd_dot(point - rayOrigin, normal) / denominator
        guard t > 0, t.isFinite else { return nil }
        let hit = rayOrigin + t * rayDirection
        return withinReach(hit, from: rayOrigin) ? hit : nil
    }

    /// Horizontal distance from the camera within ``maxSurfaceDistance``.
    static func withinReach(_ point: SIMD3<Float>, from eye: SIMD3<Float>) -> Bool {
        simd_length(SIMD2(point.x - eye.x, point.z - eye.z)) <= maxSurfaceDistance
    }

    /// Derived, never stored: a placement is approximate exactly while it has no surface.
    static func trackingMethod(hasPlacement: Bool, onSurface: Bool) -> ARPlacementTrackingMethod? {
        guard hasPlacement else { return nil }
        return onSurface ? .fullTracking : .approximate
    }

    /// Whether an estimated-plane hit may carry the model: in front of the camera, at least
    /// 0.25 m away and within ``maxSurfaceDistance``, and of the requested orientation (an
    /// upward face seen from above for floors — never a ceiling — or a near-vertical face for
    /// walls).
    static func acceptsEstimatedHit(point: SIMD3<Float>, normal: SIMD3<Float>,
                                    camera: simd_float4x4, alignment: ARPlacementAlignment) -> Bool {
        guard point.x.isFinite, point.y.isFinite, point.z.isFinite,
              simd_length(normal) > 0.0001 else { return false }
        let n = simd_normalize(normal)
        let eye = SIMD3(camera.columns.3.x, camera.columns.3.y, camera.columns.3.z)
        let cameraPoint = camera.inverse * SIMD4<Float>(point, 1)
        guard cameraPoint.z < 0, simd_length(point - eye) >= 0.25, withinReach(point, from: eye) else { return false }
        switch alignment {
        case .horizontal: return n.y > 0.5 && simd_dot(eye - point, n) > 0
        case .vertical: return abs(n.y) < 0.5
        }
    }

    /// The contact frame for an estimated pose: +Y up, +Z facing the camera (ARCore
    /// `LOCAL_Y_UP`, Android `yawToward`). A wall hit with a usable normal faces along it.
    @MainActor
    static func approximateContact(point: SIMD3<Float>, camera: simd_float4x4,
                                   wallNormal: SIMD3<Float>? = nil) -> simd_float4x4 {
        let eye = SIMD3(camera.columns.3.x, camera.columns.3.y, camera.columns.3.z)
        var toCamera = eye - point
        toCamera.y = 0
        if let wallNormal, simd_length(SIMD3(wallNormal.x, 0, wallNormal.z)) > 0.0001 {
            toCamera = SIMD3(wallNormal.x, 0, wallNormal.z)
        }
        if simd_length(toCamera) < 0.0001 {
            // Straight above the point: face back along the camera's viewing direction.
            toCamera = SIMD3(camera.columns.2.x, 0, camera.columns.2.z)
            if simd_length(toCamera) < 0.0001 { toCamera = [0, 0, 1] }
        }
        return ARPlacementController.wallContactTransform(point: point, normal: toCamera,
                                                         towardCamera: eye - point)
    }

    /// The model's world rotation once it lands on the real surface. A floor keeps exactly the
    /// rotation the user saw — no spin while it settles; a wall re-faces the detected wall and
    /// keeps the user's twist relative to the estimated contact.
    static func refinedRotation(alignment: ARPlacementAlignment, model: simd_quatf,
                                approximateContact: simd_quatf, surfaceContact: simd_quatf) -> simd_quatf {
        switch alignment {
        case .horizontal: return model
        case .vertical: return simd_normalize(surfaceContact * (approximateContact.inverse * model))
        }
    }

    /// An interruptible, continuous ease toward the latest refined spot — Android
    /// `moveTo(…, SettleSpeed)`. Each frame covers `1 − e^(−settleSpeed·dt)` of what is left,
    /// so a new target simply bends the path: no restart, nothing to wait for, no gesture
    /// blocked. Scale is never touched (it belongs to the pinch).
    struct Follow {
        private(set) var target: SIMD3<Float>
        /// A world rotation still to reach (a wall landing re-facing the wall), else `nil`.
        var rotation: simd_quatf?

        init(target: SIMD3<Float>, rotation: simd_quatf? = nil) {
            self.target = target
            self.rotation = rotation
        }

        /// Moves the target; `false` (target kept) for a change under ``followDeadband``.
        @discardableResult
        mutating func retarget(_ point: SIMD3<Float>) -> Bool {
            guard point.x.isFinite, point.y.isFinite, point.z.isFinite,
                  simd_length(point - target) >= followDeadband else { return false }
            target = point
            return true
        }

        /// Share of the remaining way covered in `delta` seconds; 1 for Reduce Motion.
        static func alpha(delta: TimeInterval, reduceMotion: Bool = false) -> Float {
            if reduceMotion { return 1 }
            guard delta.isFinite, delta > 0 else { return 0 }
            return 1 - exp(-settleSpeed * Float(min(delta, 0.1)))
        }

        func position(from current: SIMD3<Float>, alpha: Float) -> SIMD3<Float> {
            simd_mix(current, target, SIMD3(repeating: alpha))
        }

        /// Close enough to stop easing (1 mm).
        func arrived(at current: SIMD3<Float>, rotation currentRotation: simd_quatf) -> Bool {
            simd_length(current - target) < 0.001
                && rotation.map { abs(simd_dot($0.vector, currentRotation.vector)) > 0.99999 } ?? true
        }
    }

    /// What the landing on a detected plane does this refinement tick.
    enum LandingStep: Equatable {
        /// A gesture holds the pivot: landing waits for it to end, whatever the anchor does.
        case hold
        /// The plane anchor resolved and the model is over the plane: land now.
        case commit
        /// The anchor has not resolved yet and is still inside ``anchorResolveWindow``.
        case wait
        /// The model is no longer over that plane (a young plane's edge, a camera move): drop
        /// the anchor without counting a failure — unless it keeps happening (``lostLimit``).
        case lost
        /// The anchor did not resolve in time: drop it and back off before the next attempt.
        case retry
        /// ``anchorRetryLimit`` anchors failed to resolve: pause landing on that plane.
        case abandon
    }

    /// Android's anchor backoff: 0.1, 0.2, 0.4, 0.8, 0.8… s after failure 1, 2, 3, 4, 5…
    static func anchorRetryDelay(attempt: Int) -> TimeInterval {
        let shift = max(0, min(attempt - 1, 16))
        return min(anchorRetryCap, anchorRetryBase * Double(1 << shift))
    }

    /// How long one landing anchor may take to resolve before it counts as a failure — the
    /// same budget as a plane placement's anchor (RealityKit resolves anchors asynchronously).
    static let anchorResolveWindow: TimeInterval = ARPlacementLifecycle.pendingAnchorTimeout
    /// After ``anchorRetryLimit`` failures, landing on that plane pauses this long — or until
    /// ARKit reports a different plane there, or that plane grows by ``abandonRegrowth``.
    /// Android settles instead; here the model would otherwise stay approximate for good.
    static let abandonCooldown: TimeInterval = 3
    static let abandonRegrowth: Float = 1.25
    /// Losses of the same plane in a row that count as one failure: an anchor that resolves
    /// slowly on a flickering edge would otherwise be created and dropped for good.
    static let lostLimit = 3
    /// The two raycasts that refine an approximate placement run at most this often; the
    /// follow itself still eases every frame.
    static let refineInterval: TimeInterval = 0.1

    /// The landing state machine across placements. Holds no ARKit object, so it is tested
    /// whole: auto-place delay, refinement throttle, landing backoff, gesture hold, loss and
    /// abandonment recovery.
    struct State {
        /// When tracking became normal with nothing placed. Feeds ``autoPlaceDue(now:)``.
        private(set) var trackingSince: TimeInterval?
        /// Anchors that failed to resolve since the last success, move or reset.
        private(set) var failures = 0
        private(set) var nextAttemptAt: TimeInterval = 0
        /// The plane (and its area, m²) landing gave up on, and until when.
        private(set) var abandoned: (plane: UUID, area: Float, until: TimeInterval)?
        private(set) var lastRefineAt: TimeInterval?
        /// Losses in a row of the same plane, see ``lostLimit``.
        private(set) var lostStreak: (plane: UUID, count: Int)?
        /// The last tick a gesture held the landing: the resolve window starts again from it.
        private(set) var heldAt: TimeInterval?

        mutating func observe(tracking: Bool, placed: Bool, now: TimeInterval) {
            if placed { return }
            if !tracking { trackingSince = nil } else if trackingSince == nil { trackingSince = now }
        }

        /// The session was interrupted: the auto-place delay starts again once tracking is back,
        /// whether or not a model is placed.
        mutating func interrupted() {
            trackingSince = nil
        }

        /// The estimated placement may happen: tracking has been normal for ``autoPlaceDelay``.
        func autoPlaceDue(now: TimeInterval) -> Bool {
            guard let trackingSince else { return false }
            return now - trackingSince >= autoPlaceDelay
        }

        /// `true` at most once per ``refineInterval`` (and again after a clock jump back).
        mutating func refineTick(now: TimeInterval) -> Bool {
            if let lastRefineAt, now >= lastRefineAt, now - lastRefineAt < refineInterval { return false }
            lastRefineAt = now
            return true
        }

        /// Whether a landing anchor may be created on `plane` (of `area` m²) now. An abandoned
        /// plane is tried again once the cooldown ran out, or at once when it is another plane
        /// or has grown — so a user who never drags is not left approximate for good.
        mutating func mayAttemptLanding(on plane: UUID, area: Float, now: TimeInterval) -> Bool {
            if let abandoned {
                let grew = area.isFinite && area >= abandoned.area * abandonRegrowth
                guard plane != abandoned.plane || grew || now >= abandoned.until else { return false }
                moved()
            }
            return now >= nextAttemptAt
        }

        /// The attempt the current landing anchor is, 1-based.
        var attempt: Int { failures + 1 }

        /// The anchor's resolve window runs from `since`, or from the end of a gesture that
        /// held the landing, whichever is later: a long pinch does not use the window up.
        mutating func step(gesturesActive: Bool, anchored: Bool, overSurface: Bool,
                           now: TimeInterval, since: TimeInterval) -> LandingStep {
            if gesturesActive { heldAt = now; return .hold }
            if !overSurface { return .lost }
            if anchored { return .commit }
            if now - max(since, heldAt ?? since) < anchorResolveWindow { return .wait }
            return attempt >= anchorRetryLimit ? .abandon : .retry
        }

        /// The model left `plane` before its anchor resolved: no failure, only a short pause so
        /// an edge flickering in and out does not create an anchor per tick — until it happens
        /// ``lostLimit`` times in a row on that plane, which counts as one failure. `true` when
        /// that failure was the last one allowed (see ``failed(now:plane:area:)``).
        @discardableResult
        mutating func lost(now: TimeInterval, plane: UUID, area: Float) -> Bool {
            let count = lostStreak?.plane == plane ? (lostStreak?.count ?? 0) + 1 : 1
            if count >= lostLimit { return failed(now: now, plane: plane, area: area) }
            lostStreak = (plane, count)
            nextAttemptAt = now + anchorRetryBase
            return false
        }

        /// Records an anchor that did not resolve; `true` when it was the last one allowed,
        /// which pauses landing on `plane` (see ``abandonCooldown``).
        @discardableResult
        mutating func failed(now: TimeInterval, plane: UUID, area: Float) -> Bool {
            lostStreak = nil
            failures += 1
            if failures >= anchorRetryLimit {
                abandoned = (plane, area, now + abandonCooldown)
                return true
            }
            nextAttemptAt = now + anchorRetryDelay(attempt: failures)
            return false
        }

        /// The model moved under the user's hand, or landed: retry from scratch.
        mutating func moved() {
            failures = 0
            nextAttemptAt = 0
            abandoned = nil
            lostStreak = nil
            heldAt = nil
        }

        mutating func reset() {
            trackingSince = nil
            lastRefineAt = nil
            moved()
        }
    }

    /// What an approximate placement carries until it lands on a detected plane.
    struct Runtime {
        /// The estimated contact frame, in world space (the world anchor's pose).
        var contact = matrix_identity_float4x4
        /// A plane anchor created for the landing, waiting for RealityKit to resolve it.
        var pending: (anchor: AnchorEntity, plane: UUID, area: Float, since: TimeInterval)?
    }
}
#endif
