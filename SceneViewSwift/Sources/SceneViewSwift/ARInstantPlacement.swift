#if os(iOS)
import ARKit
import RealityKit
import UIKit

/// How precisely a placement made by ``ARPlacementController`` is tracked — the Swift twin of
/// ARCore's `InstantPlacementPoint.TrackingMethod`.
///
/// With ``ARPlacementController/instantPlacement`` on, the model appears at once on an
/// estimated surface (``approximate``) and moves to the real surface once ARKit detects it
/// (``fullTracking``). Without instant placement every placement is ``fullTracking`` from
/// the start. Read ``ARPlacementController/trackingMethod`` to show a "Refining…" hint:
///
/// ```swift
/// if placement.trackingMethod == .approximate {
///     Text("Refining…")
/// }
/// ```
public enum ARPlacementTrackingMethod: String, Sendable {
    /// The pose is an estimate: an ARKit estimated plane under the screen centre, or a point
    /// 1 m in front of the camera when even that misses.
    /// Kotlin: `SCREENSPACE_WITH_APPROXIMATE_DISTANCE`. Dragging waits for ``fullTracking``.
    case approximate
    /// Anchored to a detected plane. Never goes back to ``approximate``. Kotlin: `FULL_TRACKING`.
    case fullTracking
}

/// The device-free rules of instant placement, pinned by unit tests on the Simulator.
///
/// ``ARPlacementController`` feeds them ARKit values; nothing here touches a session.
enum ARInstantPlacement {
    /// Where a placement comes from, in precedence order — Kotlin `PlacementHitSource`, with
    /// the instant branch split in two because ARKit has no screen-space instant point.
    enum Source: Equatable {
        /// A detected plane: always preferred, it is the accurate answer.
        case plane
        /// No plane yet; an ARKit estimated plane under the screen centre.
        case estimatedSurface
        /// Nothing under the centre at all; ``approximateDistance`` along the centre ray.
        case fixedDistance
        /// Nothing to place on: instant placement is off and no plane is usable.
        case none
    }

    /// The accurate answer wins; the fallbacks only fill the gap, and only when opted in.
    static func source(hasPlane: Bool, instantPlacement: Bool, hasEstimatedSurface: Bool) -> Source {
        if hasPlane { return .plane }
        guard instantPlacement else { return .none }
        return hasEstimatedSurface ? .estimatedSurface : .fixedDistance
    }

    /// Distance of the fallback point along the centre ray, in metres — Kotlin
    /// `INSTANT_APPROXIMATE_DISTANCE_M`, ARCore's own starting guess.
    static let approximateDistance: Float = 1

    /// Same 0.25–3 m window as the plane path, so a refinement never lands somewhere a plane
    /// placement would have been refused.
    static let distanceRange: ClosedRange<Float> = 0.25...3

    /// Duration of the glide from the estimated pose to the refined one: `motion-fade`
    /// (DESIGN.md). Reduce Motion snaps instead.
    static let refineDuration: TimeInterval = 0.3

    /// Derived, never stored: a placement is approximate exactly while it has no surface.
    static func trackingMethod(hasPlacement: Bool, onSurface: Bool) -> ARPlacementTrackingMethod? {
        guard hasPlacement else { return nil }
        return onSurface ? .fullTracking : .approximate
    }

    /// Whether an estimated-plane hit may carry the model: in front of the camera, within
    /// ``distanceRange``, and of the requested orientation (an upward face seen from above for
    /// floors — never a ceiling — or a near-vertical face for walls).
    static func acceptsEstimatedHit(point: SIMD3<Float>, normal: SIMD3<Float>,
                                    camera: simd_float4x4, alignment: ARPlacementAlignment) -> Bool {
        guard point.x.isFinite, point.y.isFinite, point.z.isFinite,
              simd_length(normal) > 0.0001 else { return false }
        let n = simd_normalize(normal)
        let cameraPoint = (camera.inverse * SIMD4<Float>(point, 1))
        guard cameraPoint.z < 0, distanceRange.contains(simd_length(SIMD3(cameraPoint.x, cameraPoint.y, cameraPoint.z)))
        else { return false }
        switch alignment {
        case .horizontal:
            let toCamera = SIMD3(camera.columns.3.x, camera.columns.3.y, camera.columns.3.z) - point
            return n.y > 0.5 && simd_dot(toCamera, n) > 0
        case .vertical:
            return abs(n.y) < 0.5
        }
    }

    /// The contact frame for an estimated pose: +Y up, +Z facing the camera (ARCore
    /// `LOCAL_Y_UP`). Floors and walls share it — a wall object stands upright, facing the user.
    /// A wall hit with a usable normal faces along that normal instead.
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
            // Looking straight down: face the way the top of the phone points.
            toCamera = -SIMD3(camera.columns.1.x, 0, camera.columns.1.z)
            if simd_length(toCamera) < 0.0001 { toCamera = [0, 0, 1] }
        }
        return ARPlacementController.wallContactTransform(point: point, normal: toCamera,
                                                         towardCamera: eye - point)
    }

    /// The fallback point: ``approximateDistance`` along the camera's forward ray (−Z).
    static func fixedDistancePoint(camera: simd_float4x4, distance: Float = approximateDistance) -> SIMD3<Float> {
        let eye = SIMD3(camera.columns.3.x, camera.columns.3.y, camera.columns.3.z)
        let forward = -simd_normalize(SIMD3(camera.columns.2.x, camera.columns.2.y, camera.columns.2.z))
        return eye + forward * distance
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

    /// A short eased glide between two poses in the same parent space. Scale is left alone:
    /// it belongs to the pinch.
    struct Glide: Equatable {
        let fromTranslation: SIMD3<Float>
        let fromRotation: simd_quatf
        let toTranslation: SIMD3<Float>
        let toRotation: simd_quatf
        let duration: TimeInterval
        private(set) var elapsed: TimeInterval = 0

        init(from: Transform, to: Transform, duration: TimeInterval) {
            fromTranslation = from.translation
            fromRotation = from.rotation
            toTranslation = to.translation
            toRotation = to.rotation
            self.duration = duration
        }

        var isFinished: Bool { duration <= 0 || elapsed >= duration }

        /// Cubic ease-out progress in 0…1.
        var progress: Float {
            guard duration > 0 else { return 1 }
            let t = Float(min(1, max(0, elapsed / duration)))
            return 1 - pow(1 - t, 3)
        }

        var translation: SIMD3<Float> { simd_mix(fromTranslation, toTranslation, SIMD3(repeating: progress)) }
        var rotation: simd_quatf { simd_slerp(fromRotation, toRotation, progress) }

        mutating func advance(by delta: TimeInterval) {
            guard delta.isFinite, delta > 0 else { return }
            elapsed = min(duration, elapsed + delta)
        }
    }

    /// What an approximate placement carries until it lands on a detected plane.
    struct Runtime {
        /// The centre ray the placement was made along, in world space. Refinement stays on it,
        /// as an ARCore instant-placement point does.
        var origin = SIMD3<Float>.zero
        var direction = SIMD3<Float>(0, 0, -1)
        /// The estimated contact frame, in world space.
        var contact = matrix_identity_float4x4
        /// ARKit's own refinement of the estimated surface along ``origin``/``direction``.
        var trackedRaycast: ARTrackedRaycast?
        /// The latest tracked-raycast result not applied yet.
        var estimate: simd_float4x4?
        /// A plane anchor created for the refinement, waiting for RealityKit to resolve it.
        var pending: (anchor: AnchorEntity, plane: ARPlaneAnchor, point: SIMD3<Float>, since: TimeInterval)?
    }
}
#endif
