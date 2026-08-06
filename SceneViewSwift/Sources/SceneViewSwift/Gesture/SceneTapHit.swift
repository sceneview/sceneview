#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import simd

/// What a tap landed on, and where that thing is.
///
/// Produced by ``SceneView/onEntityTapped(hit:)``. The distinction from the entity-only
/// ``SceneView/onEntityTapped(_:)`` is the whole point of it: the entity tells you
/// *what* was hit, this adds *where it is*, which is what a host needs to report a
/// position and a distance-from-camera.
///
/// ### Read ``worldPosition``'s definition before you rely on it
///
/// It is the centre of the tapped entity's visual bounds — **not** the exact point on
/// the surface where the finger landed. That is a RealityKit limit, not a shortcut:
/// outside visionOS, `SpatialTapGesture.Value` carries only a 2D `location`, the 3D
/// `location3D` and `EntityTargetValue.convert(_:from:to:)` are visionOS-only, and a
/// SwiftUI `RealityView` exposes no scene raycast to make up the difference.
///
/// It is still the useful value, and materially better than the obvious alternative:
/// `entity.position(relativeTo: nil)` is the entity's *origin*, which on a model authored
/// around the world origin is `(0, 0, 0)` for every tap anywhere on it. The bounds
/// centre at least tracks the tapped mesh — on a multi-mesh model, `targetedToAnyEntity`
/// reports the individual mesh child that was hit, so the value follows which part of
/// the model was touched.
///
/// **This diverges from SceneView Android**, whose `HitResult` is a true ray-surface
/// intersection. A cross-platform façade over the two must say so rather than let the
/// difference be discovered as an inaccuracy.
///
/// Not `Sendable`: it carries a RealityKit `Entity`, which is main-actor state. The
/// handler is called on the main actor and the value is not meant to leave it.
public struct SceneTapHit {

    /// The entity under the tap. The same value ``SceneView/onEntityTapped(_:)`` gets —
    /// possibly a deep mesh child rather than the model root.
    public let entity: Entity

    /// World-space centre of ``entity``'s visual bounds.
    ///
    /// See the type's own documentation: this is *not* the exact surface point of the
    /// tap, and cannot be on iOS or macOS.
    public let worldPosition: SIMD3<Float>

    public init(entity: Entity, worldPosition: SIMD3<Float>) {
        self.entity = entity
        self.worldPosition = worldPosition
    }

    /// Builds a hit for `entity`, resolving ``worldPosition`` from its visual bounds.
    ///
    /// Falls back to the entity's origin when the bounds are empty — an entity with no
    /// mesh of its own reports a zero-extent box whose centre is meaningless, and the
    /// origin is at least a real point in the right place.
    @MainActor
    public init(entity: Entity) {
        let bounds = entity.visualBounds(relativeTo: nil)
        let extents = bounds.extents
        let isEmpty = !extents.x.isFinite || !extents.y.isFinite || !extents.z.isFinite
            || (extents.x == 0 && extents.y == 0 && extents.z == 0)
        self.entity = entity
        self.worldPosition = isEmpty ? entity.position(relativeTo: nil) : bounds.center
    }
}
#endif
