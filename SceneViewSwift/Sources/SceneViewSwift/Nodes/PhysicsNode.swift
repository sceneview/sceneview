#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation

/// Adds rigid-body physics simulation to an entity.
///
/// Mirrors SceneView Android's physics integration — wraps RealityKit's
/// `PhysicsBodyComponent` and `PhysicsMotionComponent` for gravity, collisions,
/// and forces.
///
/// ```swift
/// SceneView { content in
///     // A falling cube with physics
///     let cube = GeometryNode.cube(size: 0.2, color: .red)
///     PhysicsNode.dynamic(cube.entity, mass: 1.0)
///         .position(.init(x: 0, y: 3, z: -2))
///     content.addChild(cube.entity)
///
///     // A static floor
///     let floor = GeometryNode.plane(width: 10, depth: 10, color: .gray)
///     PhysicsNode.static(floor.entity)
///     content.addChild(floor.entity)
/// }
/// ```
public struct PhysicsNode: Sendable {

    /// Physics body mode — mirrors Android's PhysicsBody type.
    public enum Mode: Sendable {
        /// Affected by forces and gravity. Participates in collisions.
        case dynamic
        /// Not affected by forces, but participates in collisions (e.g. floors, walls).
        case `static`
        /// Moved only by code (position/velocity), but participates in collisions.
        case kinematic
    }

    // MARK: - Factory methods

    /// Applies dynamic physics to an entity (affected by gravity and forces).
    ///
    /// - Parameters:
    ///   - entity: The entity to make dynamic.
    ///   - mass: Mass in kilograms.
    ///   - restitution: Bounciness (0 = no bounce, 1 = fully elastic).
    ///   - friction: Surface friction coefficient.
    /// - Returns: A `PhysicsNode` wrapping the configured entity.
    @discardableResult
    public static func `dynamic`(
        _ entity: Entity,
        mass: Float = 1.0,
        restitution: Float = 0.5,
        friction: Float = 0.5
    ) -> PhysicsNode {
        applyPhysics(
            to: entity,
            mode: .dynamic,
            mass: mass,
            restitution: restitution,
            friction: friction
        )
    }

    /// Applies static physics to an entity (immovable collider, e.g. floors).
    ///
    /// - Parameters:
    ///   - entity: The entity to make static.
    ///   - restitution: Bounciness for objects hitting this surface.
    ///   - friction: Surface friction coefficient.
    /// - Returns: A `PhysicsNode` wrapping the configured entity.
    @discardableResult
    public static func `static`(
        _ entity: Entity,
        restitution: Float = 0.5,
        friction: Float = 0.5
    ) -> PhysicsNode {
        applyPhysics(
            to: entity,
            mode: .static,
            mass: 0,
            restitution: restitution,
            friction: friction
        )
    }

    /// Applies kinematic physics to an entity (script-driven, collides but no gravity).
    ///
    /// - Parameters:
    ///   - entity: The entity to make kinematic.
    ///   - restitution: Bounciness for objects hitting this entity.
    ///   - friction: Surface friction coefficient.
    /// - Returns: A `PhysicsNode` wrapping the configured entity.
    @discardableResult
    public static func kinematic(
        _ entity: Entity,
        restitution: Float = 0.5,
        friction: Float = 0.5
    ) -> PhysicsNode {
        applyPhysics(
            to: entity,
            mode: .kinematic,
            mass: 1.0,
            restitution: restitution,
            friction: friction
        )
    }

    // MARK: - Force application

    /// Applies an impulse to the entity (instantaneous force).
    ///
    /// Only works on dynamic bodies.
    ///
    /// - Parameters:
    ///   - entity: The physics-enabled entity.
    ///   - impulse: Force vector in Newton-seconds.
    public static func applyImpulse(
        to entity: Entity,
        impulse: SIMD3<Float>
    ) {
        guard let modelEntity = entity as? ModelEntity else { return }
        modelEntity.addForce(impulse, relativeTo: nil)
    }

    /// Sets the linear velocity of a dynamic or kinematic entity.
    ///
    /// - Parameters:
    ///   - entity: The physics-enabled entity.
    ///   - velocity: Linear velocity in meters per second.
    public static func setVelocity(
        _ entity: Entity,
        velocity: SIMD3<Float>
    ) {
        if var motion: PhysicsMotionComponent = entity.components[PhysicsMotionComponent.self] {
            motion.linearVelocity = velocity
            entity.components.set(motion)
        }
    }

    /// Sets the angular velocity of a dynamic or kinematic entity.
    ///
    /// - Parameters:
    ///   - entity: The physics-enabled entity.
    ///   - angularVelocity: Angular velocity in radians per second (axis-angle).
    public static func setAngularVelocity(
        _ entity: Entity,
        angularVelocity: SIMD3<Float>
    ) {
        if var motion: PhysicsMotionComponent = entity.components[PhysicsMotionComponent.self] {
            motion.angularVelocity = angularVelocity
            entity.components.set(motion)
        }
    }

    // MARK: - Internal

    /// The underlying entity with physics applied.
    public let entity: Entity

    /// The physics mode applied to this entity.
    public let mode: Mode

    @discardableResult
    private static func applyPhysics(
        to entity: Entity,
        mode: Mode,
        mass: Float,
        restitution: Float,
        friction: Float
    ) -> PhysicsNode {
        // Ensure collision shapes exist
        if entity.components[CollisionComponent.self] == nil {
            if let modelEntity = entity as? ModelEntity {
                modelEntity.generateCollisionShapes(recursive: true)
            }
        }

        // Configure physics material
        let physicsMaterial = PhysicsMaterialResource.generate(
            staticFriction: friction,
            dynamicFriction: friction,
            restitution: restitution
        )

        // Configure physics body
        let physicsMode: PhysicsBodyMode
        switch mode {
        case .dynamic:
            physicsMode = .dynamic
        case .static:
            physicsMode = .static
        case .kinematic:
            physicsMode = .kinematic
        }

        let physicsBody = PhysicsBodyComponent(
            massProperties: mass > 0
                ? .init(mass: mass)
                : .default,
            material: physicsMaterial,
            mode: physicsMode
        )
        entity.components.set(physicsBody)

        // Add motion component for dynamic/kinematic bodies
        if mode == .dynamic || mode == .kinematic {
            entity.components.set(PhysicsMotionComponent())
        }

        return PhysicsNode(entity: entity, mode: mode)
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
