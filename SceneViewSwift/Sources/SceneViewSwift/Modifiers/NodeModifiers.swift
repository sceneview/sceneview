#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation

/// SwiftUI-style modifiers for common entity operations.
///
/// Provides a fluent, chainable API for configuring entities similar
/// to SwiftUI view modifiers. Works on any `Entity` type.
///
/// ```swift
/// let entity = ModelEntity()
/// entity
///     .positioned(at: [0, 1, -2])
///     .scaled(to: 0.5)
///     .rotated(by: .pi / 4, around: [0, 1, 0])
///     .named("myEntity")
///     .enabled(true)
/// ```
extension Entity {

    /// Sets the position and returns self for chaining.
    ///
    /// - Parameter position: World-space position.
    /// - Returns: Self for chaining.
    @discardableResult
    public func positioned(at position: SIMD3<Float>) -> Self {
        self.position = position
        return self
    }

    /// Sets uniform scale and returns self for chaining.
    ///
    /// - Parameter factor: Uniform scale factor.
    /// - Returns: Self for chaining.
    @discardableResult
    public func scaled(to factor: Float) -> Self {
        self.scale = .init(repeating: factor)
        return self
    }

    /// Sets per-axis scale and returns self for chaining.
    ///
    /// - Parameter scale: Per-axis scale factors.
    /// - Returns: Self for chaining.
    @discardableResult
    public func scaled(to scale: SIMD3<Float>) -> Self {
        self.scale = scale
        return self
    }

    /// Rotates by angle around axis and returns self for chaining.
    ///
    /// - Parameters:
    ///   - angle: Rotation angle in radians.
    ///   - axis: Rotation axis (unit vector).
    /// - Returns: Self for chaining.
    @discardableResult
    public func rotated(by angle: Float, around axis: SIMD3<Float>) -> Self {
        self.orientation = simd_quatf(angle: angle, axis: axis)
        return self
    }

    /// Sets the orientation quaternion and returns self for chaining.
    ///
    /// - Parameter quaternion: Orientation quaternion.
    /// - Returns: Self for chaining.
    @discardableResult
    public func oriented(to quaternion: simd_quatf) -> Self {
        self.orientation = quaternion
        return self
    }

    /// Sets the entity name and returns self for chaining.
    ///
    /// - Parameter name: The entity name.
    /// - Returns: Self for chaining.
    @discardableResult
    public func named(_ name: String) -> Self {
        self.name = name
        return self
    }

    /// Sets the enabled state and returns self for chaining.
    ///
    /// - Parameter enabled: Whether the entity is enabled.
    /// - Returns: Self for chaining.
    @discardableResult
    public func enabled(_ enabled: Bool) -> Self {
        self.isEnabled = enabled
        return self
    }

    /// Points the entity at a target position and returns self for chaining.
    ///
    /// - Parameters:
    ///   - target: World-space target position.
    ///   - from: Position to look from. Default is current position.
    /// - Returns: Self for chaining.
    @discardableResult
    public func looking(at target: SIMD3<Float>, from: SIMD3<Float>? = nil) -> Self {
        self.look(at: target, from: from ?? self.position, relativeTo: nil)
        return self
    }

    /// Adds a child entity and returns self for chaining.
    ///
    /// - Parameter child: The child entity to add.
    /// - Returns: Self for chaining.
    @discardableResult
    public func withChild(_ child: Entity) -> Self {
        self.addChild(child)
        return self
    }

    /// Adds multiple children and returns self for chaining.
    ///
    /// - Parameter children: The child entities to add.
    /// - Returns: Self for chaining.
    @discardableResult
    public func withChildren(_ children: [Entity]) -> Self {
        for child in children {
            self.addChild(child)
        }
        return self
    }
}

// MARK: - ModelEntity convenience modifiers

extension ModelEntity {

    /// Generates collision shapes and returns self for chaining.
    ///
    /// - Parameter recursive: Whether to generate shapes for children too.
    /// - Returns: Self for chaining.
    @discardableResult
    public func withCollision(recursive: Bool = true) -> Self {
        self.generateCollisionShapes(recursive: recursive)
        return self
    }

    /// Adds a grounding shadow component and returns self for chaining.
    ///
    /// - Returns: Self for chaining.
    @discardableResult
    public func withShadow() -> Self {
        if #available(iOS 18.0, visionOS 2.0, *) {
            self.components.set(GroundingShadowComponent(castsShadow: true))
        }
        return self
    }
}

// MARK: - SwiftUI gesture targeting

extension Entity {

    /// Makes this entity and its whole subtree eligible for SwiftUI's
    /// `targetedToAnyEntity()` gestures.
    ///
    /// A `CollisionComponent` alone is **not** enough. SwiftUI's entity-targeted gestures
    /// — which is how ``SceneView/onEntityTapped(_:)``, ``SceneView/onEntityTapHit(_:)``
    /// and the whole `NodeGesture` dispatch reach an entity — additionally require an
    /// `InputTargetComponent`. Without one the gesture simply never fires: no error, no
    /// warning, and a scene that looks completely correct until someone taps it.
    ///
    /// Measured on the iOS 26.3 simulator against a `.usdz` loaded through
    /// ``ModelNode/load(_:enableCollision:)`` with `enableCollision: true`: taps on the
    /// model produced no callback at all until this component was set, and fired on the
    /// first try afterwards.
    ///
    /// Applied recursively because `generateCollisionShapes(recursive:)` puts the
    /// collision shapes on the mesh descendants, and it is those descendants the hit-test
    /// resolves to.
    @discardableResult
    func makeInputTargetable() -> Self {
        components.set(InputTargetComponent())
        for child in children {
            child.makeInputTargetable()
        }
        return self
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
